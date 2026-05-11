package dev.jetpack.script

import dev.jetpack.engine.parser.ast.AccessModifier
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.Statement
import dev.jetpack.engine.resolver.NameResolver
import dev.jetpack.engine.resolver.TypeChecker
import dev.jetpack.engine.runtime.CommandHandle
import dev.jetpack.engine.runtime.IntervalHandle
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.ListenerHandle
import dev.jetpack.engine.runtime.ScopeException

internal class ScriptImportBinder(
    private val extensions: ExtensionModuleRegistry,
    private val reportError: (
        scriptName: String,
        message: String,
        sourceLines: List<String>,
        line: Int,
        prevLine: Int?,
    ) -> Unit,
) {

    private data class ImportPlan(
        val roots: LinkedHashMap<String, ModuleTreeNode>,
        val aliases: LinkedHashMap<String, ScriptModule>,
    )

    fun resolveImports(
        module: ScriptModule,
        modulesByCanonicalPath: Map<String, ScriptModule>,
        modulesByLogicalPath: Map<String, ScriptModule>,
    ) {
        if (module.validationState == false) return
        val resolved = mutableListOf<ResolvedImport>()
        var ok = true
        for (stmt in module.stmts.filterIsInstance<Statement.Using>()) {
            val targets = try {
                resolveImportTargets(module, stmt, modulesByCanonicalPath, modulesByLogicalPath)
            } catch (e: UsingError) {
                report(module, e.message ?: "Using error", e.line)
                ok = false
                emptyList()
            }
            if (targets.isNotEmpty()) {
                resolved += ResolvedImport(stmt, targets.distinctBy { it.meta.file.canonicalPath })
            }
        }
        module.resolvedImports = resolved
        if (!ok) module.validationState = false
    }

    fun validateModule(module: ScriptModule, stack: ArrayDeque<ScriptModule>): Boolean {
        module.validationState?.let { return it }
        if (module in stack) {
            val cycle = stack.dropWhile { it != module } + module
            val cyclePath = cycle.joinToString(" -> ") { it.displayPath }
            report(module, "Circular import detected: $cyclePath", 0)
            return false
        }

        stack.addLast(module)
        var ok = true

        for (resolved in module.resolvedImports) {
            for (dependency in resolved.targetModules) {
                ok = validateModule(dependency, stack) && ok
            }
        }

        if (ok) {
            val importTypes = try {
                buildImportTypeBindings(module)
            } catch (e: UsingError) {
                report(module, e.message ?: "Using error", e.line)
                ok = false
                emptyMap()
            }

            if (ok) {
                val predefinedNames = importTypes.keys
                val reservedNames = predefinedNames.toSet() + extensions.builtinGlobalNames()
                val resolverErrors = NameResolver(reservedNames).resolve(module.stmts, predefinedNames.toSet())
                for (err in resolverErrors) {
                    report(module, err.message, err.line, err.prevLine)
                }

                val typeErrors = if (resolverErrors.isEmpty()) {
                    TypeChecker(extensions.builtinRegistry).check(module.stmts, importTypes).also { errs ->
                        for (err in errs) {
                            report(module, err.message, err.line)
                        }
                    }
                } else emptyList()
                ok = resolverErrors.isEmpty() && typeErrors.isEmpty()
            }
        }

        stack.removeLast()
        module.validationState = ok
        return ok
    }

    fun buildRuntimeImportBindings(module: ScriptModule): Map<String, JetValue.JModule> {
        val plan = buildImportPlan(module)
        val bindings = linkedMapOf<String, JetValue.JModule>()

        for ((alias, targetModule) in plan.aliases) {
            val declaredType = buildModuleType(
                ModuleTreeNode(module = targetModule),
                alias,
                module.resolvedImports.firstOrNull { it.using.alias == alias }?.using?.line ?: 0,
            )
            bindings[alias] = buildModuleValue(
                ModuleTreeNode(module = targetModule),
                alias,
                declaredType,
            )
        }

        for ((rootName, node) in plan.roots) {
            bindings[rootName] = buildModuleValue(node, rootName, buildModuleType(node, rootName, 0))
        }

        for (stmt in module.stmts.filterIsInstance<Statement.Using>()) {
            if (stmt.relativeDots == 0 && stmt.path.size == 1 && !stmt.recursive) {
                val name = stmt.path[0]
                val registration = extensions.namedModule(name) ?: continue
                val alias = stmt.alias ?: name
                bindings[alias] = registration.value
            }
        }

        return bindings
    }

    private fun resolveImportTargets(
        module: ScriptModule,
        stmt: Statement.Using,
        modulesByCanonicalPath: Map<String, ScriptModule>,
        modulesByLogicalPath: Map<String, ScriptModule>,
    ): List<ScriptModule> {
        if (stmt.relativeDots == 0 && stmt.path.size == 1 && !stmt.recursive && stmt.path[0] in extensions.namedModuleNames()) {
            return emptyList()
        }
        val pathSegments = UsingResolver.resolvePath(stmt, module.pathSegments)
        if (stmt.recursive) {
            val matches = modulesByCanonicalPath.values
                .filter { it.pathSegments.startsWithPrefix(pathSegments) }
                .sortedBy { it.displayPath }
            if (matches.isEmpty()) {
                throw UsingError("Using target '${UsingResolver.displayPath(stmt)}' does not exist", stmt.line)
            }
            return matches
        }

        val key = pathSegments.joinToString(".")
        val target = modulesByLogicalPath[key]
            ?: throw UsingError("Using target '${UsingResolver.displayPath(stmt)}' does not exist", stmt.line)
        return listOf(target)
    }

    private fun buildImportTypeBindings(module: ScriptModule): Map<String, JetType> {
        val plan = buildImportPlan(module)
        val bindings = linkedMapOf<String, JetType>()

        for ((alias, targetModule) in plan.aliases) {
            bindings[alias] = buildModuleType(
                ModuleTreeNode(module = targetModule),
                alias,
                module.resolvedImports.firstOrNull { it.using.alias == alias }?.using?.line ?: 0,
            )
        }

        for ((rootName, node) in plan.roots) {
            bindings[rootName] = buildModuleType(node, rootName, 0)
        }

        for (stmt in module.stmts.filterIsInstance<Statement.Using>()) {
            if (stmt.relativeDots == 0 && stmt.path.size == 1 && !stmt.recursive) {
                val name = stmt.path[0]
                val registration = extensions.namedModule(name) ?: continue
                val alias = stmt.alias ?: name
                if (alias in bindings) throw UsingError("Module '$alias' is already declared", stmt.line)
                bindings[alias] = JetType.TModule(registration.fields)
            }
        }

        return bindings
    }

    private fun buildImportPlan(module: ScriptModule): ImportPlan {
        val roots = linkedMapOf<String, ModuleTreeNode>()
        val aliases = linkedMapOf<String, ScriptModule>()
        val reservedNames = extensions.builtinGlobalNames()

        for (resolved in module.resolvedImports) {
            if (resolved.targetModules.isEmpty()) continue
            val alias = resolved.using.alias
            if (alias != null) {
                if (alias in reservedNames) {
                    throw UsingError("Module '$alias' collides with a built-in name", resolved.using.line)
                }
                if (alias in aliases || alias in roots) {
                    throw UsingError("Module '$alias' is already declared", resolved.using.line)
                }
                aliases[alias] = resolved.targetModules.single()
                continue
            }

            for (targetModule in resolved.targetModules) {
                addToModuleTree(roots, targetModule)
            }
        }

        for (rootName in roots.keys) {
            val usingLine = module.resolvedImports
                .firstOrNull { resolved ->
                    resolved.using.alias == null &&
                        resolved.targetModules.any { it.pathSegments.firstOrNull() == rootName }
                }
                ?.using?.line ?: 0
            if (rootName in reservedNames) {
                throw UsingError("Module '$rootName' collides with a built-in name", usingLine)
            }
            if (rootName in aliases) {
                throw UsingError("Module '$rootName' is already declared", usingLine)
            }
        }

        return ImportPlan(roots, aliases)
    }

    private fun addToModuleTree(
        roots: LinkedHashMap<String, ModuleTreeNode>,
        module: ScriptModule,
    ) {
        var current = roots.getOrPut(module.pathSegments.first()) { ModuleTreeNode() }
        for (segment in module.pathSegments.drop(1)) {
            current = current.children.getOrPut(segment) { ModuleTreeNode() }
        }
        current.module = module
    }

    private fun buildModuleType(
        node: ModuleTreeNode,
        modulePath: String,
        line: Int,
    ): JetType.TModule {
        val fields = linkedMapOf<String, JetType>()
        val moduleRef = node.module
        if (moduleRef != null) {
            for (definition in moduleRef.exportDefinitions.values) {
                if (!definition.visibleInModule) continue
                if (definition.name in node.children) {
                    throw UsingError("Module member collision at '$modulePath.${definition.name}'", line)
                }
                fields[definition.name] = definition.type
            }
        }
        for ((childName, childNode) in node.children) {
            if (childName in fields) {
                throw UsingError("Module member collision at '$modulePath.$childName'", line)
            }
            val childPath = "$modulePath.$childName"
            fields[childName] = buildModuleType(childNode, childPath, line)
        }
        return JetType.TModule(fields)
    }

    private fun buildModuleValue(
        node: ModuleTreeNode,
        modulePath: String,
        declaredType: JetType.TModule,
    ): JetValue.JModule {
        val childObjects = linkedMapOf<String, JetValue.JModule>()
        for ((childName, childNode) in node.children) {
            val childPath = "$modulePath.$childName"
            val childType = declaredType.fields[childName] as? JetType.TModule
                ?: buildModuleType(childNode, childPath, 0)
            childObjects[childName] = buildModuleValue(childNode, childPath, childType)
        }

        val moduleRef = node.module
        val visibleExports = moduleRef?.exportDefinitions
            ?.values
            ?.filter { it.visibleInModule }
            ?.associateBy { it.name }
            .orEmpty()
        val writableExports = visibleExports.values.filterNot { it.isReadOnly }.associateBy { it.name }

        return JetValue.JModule(
            fields = linkedMapOf<String, JetValue>().apply {
                for ((key, value) in childObjects) {
                    this[key] = value
                }
            },
            declaredType = declaredType,
            memberResolver = resolver@{ name ->
                childObjects[name]?.let { return@resolver it }
                val definition = visibleExports[name] ?: return@resolver null
                readModuleExport(moduleRef!!, definition)
            },
            memberNamesProvider = {
                linkedSetOf<String>().apply {
                    addAll(childObjects.keys)
                    addAll(visibleExports.keys)
                }
            },
            memberAssigner = if (writableExports.isNotEmpty()) { name, value ->
                val definition = writableExports[name]
                    ?: throw RuntimeException("Cannot assign module member '$name'")
                writeModuleExport(moduleRef!!, definition.name, value)
            } else null,
            cacheResolvedMembers = false,
        )
    }

    private fun readModuleExport(module: ScriptModule, definition: ModuleExportDefinition): JetValue {
        if (definition.name !in module.initializedExports) {
            throw RuntimeException("Cannot access export '${definition.name}' from module '${module.displayPath}' before it is initialized")
        }
        val scope = module.scope ?: throw RuntimeException("Module '${module.displayPath}' is not loaded")
        val value = try {
            scope.get(definition.name)
        } catch (e: ScopeException) {
            throw RuntimeException(e.message ?: "Failed to access export '${definition.name}'")
        }
        return if (definition.access == AccessModifier.PROTECTED) {
            toProtectedView(value)
        } else {
            value
        }
    }

    private fun writeModuleExport(module: ScriptModule, name: String, value: JetValue) {
        val definition = module.exportDefinitions[name]
            ?: throw RuntimeException("Module member '$name' does not exist")
        if (definition.isReadOnly) {
            throw RuntimeException("Cannot assign read-only export '$name'")
        }
        val scope = module.scope ?: throw RuntimeException("Module '${module.displayPath}' is not loaded")
        try {
            scope.set(name, value)
            module.initializedExports += name
        } catch (e: ScopeException) {
            throw RuntimeException(e.message ?: "Failed to assign export '$name'")
        }
    }

    private fun toProtectedView(value: JetValue): JetValue = when (value) {
        is JetValue.JList -> JetValue.JList(
            value.elements.map(::toProtectedView).toMutableList(),
            isReadOnly = true,
            declaredElementType = value.declaredElementType,
        )
        is JetValue.JModule -> wrapProtectedModule(value)
        is JetValue.JObject -> wrapProtectedObject(value)
        is JetValue.JCommand -> wrapProtectedCommand(value)
        is JetValue.JInterval -> JetValue.JInterval(object : IntervalHandle {
            override fun destroy(): Boolean = throw RuntimeException("Cannot destroy a protected interval from another file")
            override fun activate(): Boolean = throw RuntimeException("Cannot activate a protected interval from another file")
            override fun deactivate(): Boolean = throw RuntimeException("Cannot deactivate a protected interval from another file")
            override fun trigger(): Boolean = value.handle.trigger()
            override fun isActive(): Boolean = value.handle.isActive()
        })
        is JetValue.JListener -> JetValue.JListener(object : ListenerHandle {
            override fun activate(): Boolean = throw RuntimeException("Cannot activate a protected listener from another file")
            override fun deactivate(): Boolean = throw RuntimeException("Cannot deactivate a protected listener from another file")
            override fun destroy(): Boolean = throw RuntimeException("Cannot destroy a protected listener from another file")
            override fun trigger(sender: JetValue): Boolean = value.handle.trigger(sender)
            override fun isActive(): Boolean = value.handle.isActive()
        })
        else -> value
    }

    private fun wrapProtectedObject(base: JetValue.JObject): JetValue.JObject {
        val visibleNames: Set<String> = base.fieldNames().filterTo(linkedSetOf()) { name ->
            base.getField(name) !is JetValue.JBuiltin
        }
        return JetValue.JObject(
            fields = mutableMapOf(),
            isReadOnly = true,
            memberResolver = { name ->
                if (name !in visibleNames) null
                else base.getField(name)?.let(::toProtectedView)
            },
            memberNamesProvider = { visibleNames },
            cacheResolvedMembers = false,
        )
    }

    private fun wrapProtectedModule(base: JetValue.JModule): JetValue.JModule {
        val visibleNames = base.fieldNames()
        return JetValue.JModule(
            fields = mutableMapOf(),
            declaredType = base.declaredType,
            memberResolver = { name ->
                if (name !in visibleNames) null
                else base.getField(name)?.let(::toProtectedView)
            },
            memberNamesProvider = { visibleNames },
            cacheResolvedMembers = false,
        )
    }

    private fun wrapProtectedCommand(base: JetValue.JCommand): JetValue.JCommand {
        val wrappedHandle = object : CommandHandle {
            override val hasSender = base.handle.hasSender

            override fun activate(): Boolean =
                throw RuntimeException("Cannot activate a protected command from another file")

            override fun deactivate(): Boolean =
                throw RuntimeException("Cannot deactivate a protected command from another file")

            override fun destroy(): Boolean =
                throw RuntimeException("Cannot destroy a protected command from another file")

            override fun trigger(sender: JetValue, args: List<JetValue>): Boolean =
                base.handle.trigger(sender, args)

            override fun triggerPath(path: List<String>, sender: JetValue, args: List<JetValue>): Boolean =
                base.handle.triggerPath(path, sender, args)

            override fun isActive(): Boolean = base.handle.isActive()
        }

        return JetValue.JCommand(
            handle = wrappedHandle,
            path = base.path,
            subcommands = base.subcommands.mapValues { (_, subcommand) -> wrapProtectedCommand(subcommand) },
        )
    }

    private fun report(module: ScriptModule, message: String, line: Int, prevLine: Int? = null) {
        reportError(module.meta.scriptId, message, module.sourceLines, line, prevLine)
    }

    private fun List<String>.startsWithPrefix(prefix: List<String>): Boolean {
        if (prefix.size > size) return false
        return indices.take(prefix.size).all { this[it] == prefix[it] }
    }
}
