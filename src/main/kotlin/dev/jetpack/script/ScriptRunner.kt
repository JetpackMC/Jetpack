package dev.jetpack.script

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.runtime.builtins.RegistrationHandle
import dev.jetpack.event.JetpackEvent
import dev.jetpack.event.EventBridge
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.Statement
import dev.jetpack.engine.runtime.CommandHandle
import dev.jetpack.engine.runtime.CommandItem
import dev.jetpack.engine.runtime.CommandNode
import dev.jetpack.engine.runtime.CommandParam
import dev.jetpack.engine.runtime.IntervalHandle
import dev.jetpack.engine.runtime.Interpreter
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.ListenerHandle
import dev.jetpack.engine.runtime.RuntimeError
import dev.jetpack.engine.runtime.Scope
import dev.jetpack.engine.runtime.ScriptEnvironment
import dev.jetpack.engine.runtime.builtins.Builtin
import dev.jetpack.engine.runtime.builtins.BuiltinRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.plugin.Plugin
import java.io.File
import java.util.IdentityHashMap
import kotlin.coroutines.CoroutineContext

class ScriptRunner(private val plugin: JetpackPlugin) {

    private val mainDispatcher = object : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (plugin.server.isPrimaryThread) block.run()
            else plugin.server.scheduler.runTask(plugin, block)
        }
    }
    private val coroutineScope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val threadDispatcher = Dispatchers.Default
    private val extensionRegistry = ExtensionModuleRegistry()
    private val moduleFactory: ScriptModuleFactory
        get() = ScriptModuleFactory(scriptsRoot)
    private val importBinder = ScriptImportBinder(extensionRegistry) { scriptName, message, sourceLines, line, prevLine ->
        plugin.sendScriptError(formatScriptError(scriptName, message, sourceLines, line, prevLine))
    }

    val builtinRegistry: BuiltinRegistry
        get() = extensionRegistry.builtinRegistry
    private var syncMethodResolved = false
    private var cachedSyncMethod: java.lang.reflect.Method? = null
    private var commandRefreshBatchDepth = 0
    private var commandRefreshPending = false
    private val modulesByCanonicalPath = linkedMapOf<String, ScriptModule>()
    private val modulesByLogicalPath = linkedMapOf<String, ScriptModule>()

    fun registerModule(owner: Plugin, builtin: Builtin): RegistrationHandle =
        extensionRegistry.registerModule(owner, builtin)

    fun registerModule(
        owner: Plugin,
        name: String,
        value: JetValue.JModule,
        fields: Map<String, JetType>,
        dynamic: Boolean = false,
    ): RegistrationHandle =
        extensionRegistry.registerModule(owner, name, value, fields, dynamic)

    fun unregisterExtensions(owner: Plugin): Boolean =
        extensionRegistry.unregisterExtensions(owner)

    private val intervals = mutableMapOf<String, MutableList<ManagedInterval>>()
    private val scriptListeners = mutableMapOf<String, MutableList<ListenerHandle>>()
    private val scriptCommandHandles = mutableMapOf<String, MutableList<CommandHandle>>()
    private val scriptCommands = mutableMapOf<String, MutableList<Command>>()

    val scriptsRoot: File
        get() = File(plugin.dataFolder, "scripts")

    fun createBaseScope(): Scope = Scope()

    fun runAll(parsedModules: List<ScriptModule>): Set<String> = withBatchedCommandRefresh {
        modulesByCanonicalPath.clear()
        modulesByLogicalPath.clear()

        for (module in parsedModules) {
            modulesByCanonicalPath[module.meta.file.canonicalPath] = module
            modulesByLogicalPath[module.displayPath] = module
        }

        for (module in parsedModules) {
            importBinder.resolveImports(module, modulesByCanonicalPath, modulesByLogicalPath)
        }

        for (module in parsedModules) {
            importBinder.validateModule(module, ArrayDeque())
        }

        val loaded = linkedSetOf<String>()
        for (module in parsedModules) {
            if (module.validationState == true && ensureLoaded(module)) {
                loaded += module.meta.file.canonicalPath
            }
        }
        return loaded
    }

    fun createParsedModule(
        meta: ScriptMeta,
        stmts: List<Statement>,
        sourceLines: List<String>,
    ): ScriptModule = moduleFactory.createParsedModule(meta, stmts, sourceLines)

    fun stop(file: File) = withBatchedCommandRefresh {
        val canonicalPath = file.canonicalPath
        intervals.remove(canonicalPath)?.forEach { it.destroy() }
        scriptListeners.remove(canonicalPath)?.forEach { it.destroy() }
        scriptCommandHandles.remove(canonicalPath)?.forEach { it.destroy() }
        scriptCommands.remove(canonicalPath)
        modulesByCanonicalPath.remove(canonicalPath)
        modulesByLogicalPath.values.removeIf { it.meta.file.canonicalPath == canonicalPath }
    }

    fun stopAll() = withBatchedCommandRefresh {
        coroutineScope.coroutineContext[Job]?.cancelChildren()
        intervals.values.forEach { list -> list.forEach { it.destroy() } }
        intervals.clear()
        scriptListeners.values.forEach { list -> list.forEach { it.destroy() } }
        scriptListeners.clear()
        scriptCommandHandles.values.forEach { list -> list.forEach { it.destroy() } }
        scriptCommandHandles.clear()
        scriptCommands.clear()
        modulesByCanonicalPath.clear()
        modulesByLogicalPath.clear()
        EventBridge.unregisterAllEvents()
    }

    private fun ensureLoaded(module: ScriptModule): Boolean {
        if (module.validationState != true) return false
        return when (module.state) {
            ModuleLoadState.LOADED -> true
            ModuleLoadState.LOADING -> true
            ModuleLoadState.FAILED -> false
            ModuleLoadState.NOT_LOADED -> loadModule(module)
        }
    }

    private fun loadModule(module: ScriptModule): Boolean {
        module.state = ModuleLoadState.LOADING
        module.initializedExports.clear()

        val scope = createBaseScope()
        module.scope = scope

        val scriptIntervals = mutableListOf<ManagedInterval>()
        val listenerHandles = mutableListOf<ListenerHandle>()
        val commandHandles = mutableListOf<CommandHandle>()
        val canonicalPath = module.meta.file.canonicalPath
        intervals[canonicalPath] = scriptIntervals
        scriptListeners[canonicalPath] = listenerHandles
        scriptCommandHandles[canonicalPath] = commandHandles

        val environment = createEnvironment(module, scriptIntervals, listenerHandles, commandHandles)
        val interpreter = Interpreter(builtins = builtinRegistry, env = environment)

        try {
            interpreter.declareTopLevelDeclarations(module.stmts, scope)
            markDeclarationExportsInitialized(module, scope)

            val runtimeBindings = importBinder.buildRuntimeImportBindings(module)
            for ((name, value) in runtimeBindings) {
                scope.defineReadOnly(name, value)
            }
            for (resolved in module.resolvedImports) {
                for (dependency in resolved.targetModules) {
                    if (!ensureLoaded(dependency)) {
                        throw RuntimeException("Failed to load imported module '${dependency.displayPath}'")
                    }
                }
            }

            runBlocking {
                for (stmt in module.stmts) {
                    when (stmt) {
                        is Statement.Metadata,
                        is Statement.Using,
                        is Statement.Manifest,
                        is Statement.FunctionDecl,
                        is Statement.IntervalDecl,
                        is Statement.ListenerDecl,
                        is Statement.CommandDecl -> Unit
                        else -> {
                            interpreter.executeStmt(stmt, scope)
                            if (stmt is Statement.VarDecl) {
                                module.initializedExports += stmt.name
                            }
                        }
                    }
                }
            }
            module.state = ModuleLoadState.LOADED
            return true
        } catch (e: RuntimeError) {
            reportError(module.meta.scriptId, e.message ?: "Runtime error", e.line, module.sourceLines)
        } catch (e: Exception) {
            reportError(module.meta.scriptId, e.message ?: "Unknown error", 0, module.sourceLines)
        }

        module.state = ModuleLoadState.FAILED
        stop(module.meta.file)
        return false
    }

    private fun markDeclarationExportsInitialized(module: ScriptModule, scope: Scope) {
        for (definition in module.exportDefinitions.values) {
            if (definition.availableAfterDeclaration && scope.isDefined(definition.name)) {
                module.initializedExports += definition.name
            }
        }
    }

    private fun createEnvironment(
        module: ScriptModule,
        scriptIntervals: MutableList<ManagedInterval>,
        listenerHandles: MutableList<ListenerHandle>,
        commandHandles: MutableList<CommandHandle>,
    ): ScriptEnvironment = object : ScriptEnvironment {
        override fun registerInterval(name: String, ms: Int, body: suspend () -> Unit): IntervalHandle {
            val ticks = ((ms + 25) / 50).coerceAtLeast(1)
            val interval = ManagedInterval(
                plugin = plugin,
                periodTicks = ticks,
                onRun = body,
                scope = coroutineScope,
                reportRuntimeError = { error ->
                    reportError(module.meta.scriptId, error.message ?: "Runtime error", error.line, module.sourceLines)
                },
                reportUnknownError = { error ->
                    reportError(module.meta.scriptId, error.message ?: "Unknown error", 0, module.sourceLines)
                },
            )
            scriptIntervals.add(interval)
            return interval
        }

        override fun registerListener(eventType: String, line: Int, body: suspend (JetValue) -> Unit): ListenerHandle {
            if (JetpackEvent.resolve(eventType) == null) {
                reportError(module.meta.scriptId, "Unknown event type '$eventType'", line, module.sourceLines)
                return object : ListenerHandle {
                    override fun activate(): Boolean = false
                    override fun deactivate(): Boolean = false
                    override fun destroy(): Boolean = false
                    override fun trigger(sender: JetValue): Boolean = false
                    override fun isActive(): Boolean = false
                }
            }
            val inner = EventBridge.register(plugin, eventType, module.meta.scriptId) { senderValue ->
                coroutineScope.launch {
                    try {
                        body(senderValue)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RuntimeError) {
                        reportError(module.meta.scriptId, e.message ?: "Runtime error", e.line, module.sourceLines)
                    } catch (e: Exception) {
                        reportError(module.meta.scriptId, e.message ?: "Unknown error", 0, module.sourceLines)
                    }
                }
            }
            return object : ListenerHandle by inner {
                override fun trigger(sender: JetValue): Boolean {
                    val effective = if (sender is JetValue.JNull) buildConsoleSenderObject() else sender
                    return inner.trigger(effective)
                }
            }.also(listenerHandles::add)
        }

        override suspend fun <T> runThread(body: suspend () -> T): T =
            withContext(threadDispatcher) { body() }

        override fun registerCommand(node: CommandNode): CommandHandle {
            val canonicalPath = module.meta.file.canonicalPath
            val cmd = object : Command(node.name) {
                override fun execute(sender: CommandSender, commandLabel: String, args: Array<String>): Boolean {
                    val senderObj = buildSenderObject(sender, commandLabel, args)
                    coroutineScope.launch {
                        try {
                            dispatchCommand(node, senderObj, args.toList())
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RuntimeError) {
                            reportError(module.meta.scriptId, e.message ?: "Runtime error", e.line, module.sourceLines)
                        } catch (e: Exception) {
                            reportError(module.meta.scriptId, e.message ?: "Unknown error", 0, module.sourceLines)
                        }
                    }
                    return true
                }

                override fun tabComplete(sender: CommandSender, alias: String, args: Array<String>): MutableList<String> =
                    buildTabCompletions(node, args.toList()).toMutableList()
            }
            node.annotations.description?.let { cmd.description = it }
            node.annotations.usage?.let { cmd.usage = it }
            node.annotations.permission?.let { cmd.permission = it }
            node.annotations.permissionMessage?.let { cmd.permissionMessage = it }
            if (node.annotations.aliases.isNotEmpty()) cmd.aliases = node.annotations.aliases
            plugin.server.commandMap.register(plugin.name.lowercase(), cmd)
            scriptCommands.getOrPut(canonicalPath) { mutableListOf() }.add(cmd)
            requestCommandTreeRefresh()

            return object : CommandHandle {
                private val lock = Any()
                private var destroyed = false
                private var active = true

                override val hasSender = node.senderName != null

                override fun activate(): Boolean = synchronized(lock) {
                    if (destroyed || active) return@synchronized false
                    plugin.server.commandMap.register(plugin.name.lowercase(), cmd)
                    active = true
                    requestCommandTreeRefresh()
                    true
                }

                override fun deactivate(): Boolean = synchronized(lock) {
                    if (destroyed || !active) return@synchronized false
                    unregisterScriptCommands(plugin.server.commandMap, listOf(cmd))
                    active = false
                    requestCommandTreeRefresh()
                    true
                }

                override fun destroy(): Boolean = synchronized(lock) {
                    if (destroyed) return@synchronized false
                    if (active) {
                        unregisterScriptCommands(plugin.server.commandMap, listOf(cmd))
                        requestCommandTreeRefresh()
                    }
                    active = false
                    destroyed = true
                    true
                }

                override fun trigger(sender: JetValue, args: List<JetValue>): Boolean {
                    synchronized(lock) {
                        if (destroyed || !active) return false
                    }
                    val effectiveSender = when {
                        sender is JetValue.JNull -> buildConsoleSenderObject()
                        sender is JetValue.JObject -> sender
                        else -> buildConsoleSenderObject()
                    }
                    coroutineScope.launch {
                        try {
                            node.directTrigger(effectiveSender, args)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RuntimeError) {
                            reportError(module.meta.scriptId, e.message ?: "Runtime error", e.line, module.sourceLines)
                        } catch (e: Exception) {
                            reportError(module.meta.scriptId, e.message ?: "Unknown error", 0, module.sourceLines)
                        }
                    }
                    return true
                }

                override fun triggerPath(path: List<String>, sender: JetValue, args: List<JetValue>): Boolean {
                    synchronized(lock) {
                        if (destroyed || !active) return false
                    }
                    val effectiveSender = when {
                        sender is JetValue.JNull -> buildConsoleSenderObject()
                        sender is JetValue.JObject -> sender
                        else -> buildConsoleSenderObject()
                    }
                    coroutineScope.launch {
                        try {
                            node.directTriggerPath(path, effectiveSender, args)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: RuntimeError) {
                            reportError(module.meta.scriptId, e.message ?: "Runtime error", e.line, module.sourceLines)
                        } catch (e: Exception) {
                            reportError(module.meta.scriptId, e.message ?: "Unknown error", 0, module.sourceLines)
                        }
                    }
                    return true
                }

                override fun isActive(): Boolean = synchronized(lock) {
                    !destroyed && active
                }
            }.also(commandHandles::add)
        }

    }

    private fun unregisterScriptCommands(commandMap: CommandMap, commands: List<Command>) {
        if (commands.isEmpty()) return

        val knownCommands = commandMap.getKnownCommands()
        val targets = IdentityHashMap<Command, Boolean>(commands.size)
        for (command in commands) {
            command.unregister(commandMap)
            targets[command] = true
        }

        val keysToRemove = mutableListOf<String>()
        for ((key, registered) in knownCommands) {
            if (targets.containsKey(registered)) {
                keysToRemove += key
            }
        }
        for (key in keysToRemove) {
            knownCommands.remove(key)
        }
    }

    private inline fun <T> withBatchedCommandRefresh(block: () -> T): T {
        commandRefreshBatchDepth++
        try {
            return block()
        } finally {
            commandRefreshBatchDepth--
            if (commandRefreshBatchDepth == 0 && commandRefreshPending) {
                commandRefreshPending = false
                refreshCommandTreeNow()
            }
        }
    }

    private fun requestCommandTreeRefresh() {
        if (commandRefreshBatchDepth > 0) {
            commandRefreshPending = true
            return
        }
        refreshCommandTreeNow()
    }

    private fun refreshCommandTreeNow() {
        if (!syncMethodResolved) {
            cachedSyncMethod = plugin.server.javaClass.methods.firstOrNull { method ->
                method.parameterCount == 0 && method.name in setOf("syncCommands", "updateCommands")
            }
            syncMethodResolved = true
        }
        val method = cachedSyncMethod ?: return
        try {
            method.invoke(plugin.server)
        } catch (_: ReflectiveOperationException) {
            return
        }
    }

    private suspend fun dispatchCommand(
        node: CommandNode,
        sender: JetValue.JObject,
        args: List<String>,
        parentScope: Scope? = null,
    ) {
        val requiredCount = node.params.count { it.default == null }
        if (args.size < requiredCount) {
            throw RuntimeError(
                "Command '${node.name}' expects ${formatArity(requiredCount, node.params.size)} but got ${args.size}",
                node.line,
            )
        }
        val converted = convertCommandArgs(node.params, args.take(node.params.size))
        val invocationScope = node.newInvocationScope(sender, converted, parentScope)

        val remainingArgs = args.drop(node.params.size)
        if (remainingArgs.isNotEmpty()) {
            for ((i, item) in node.bodyItems.withIndex()) {
                if (item is CommandItem.Sub && item.node.name.equals(remainingArgs[0], ignoreCase = true)) {
                    if (!node.executePreamble(invocationScope, i)) return
                    dispatchCommand(item.node, sender, remainingArgs.drop(1), invocationScope)
                    return
                }
            }
            throw RuntimeError("Command '${node.name}' received unexpected argument '${remainingArgs[0]}'", node.line)
        }

        node.executeDefault(invocationScope)
    }

    private fun convertCommandArgs(params: List<CommandParam>, rawArgs: List<String>): List<JetValue> =
        rawArgs.take(params.size).mapIndexed { i, raw ->
            convertArg(raw, params[i].typeName)
                ?: throw RuntimeError(
                    "Parameter '${params[i].name}' expected ${params[i].typeName ?: "any"}, got '$raw'",
                    params[i].line,
                )
        }

    private fun convertArg(raw: String, typeName: String?): JetValue? = when (typeName) {
        "int" -> raw.toIntOrNull()?.let { JetValue.JInt(it) }
        "float" -> raw.toDoubleOrNull()?.let { JetValue.JFloat(it) }
        "bool" -> when (raw.lowercase()) {
            "true" -> JetValue.JBool(true)
            "false" -> JetValue.JBool(false)
            else -> null
        }
        "string", null -> JetValue.JString(raw)
        else -> JetValue.JString(raw)
    }

    private fun formatArity(requiredCount: Int, totalCount: Int): String =
        if (requiredCount == totalCount) "$totalCount arguments" else "$requiredCount..$totalCount arguments"

    private fun buildTabCompletions(node: CommandNode, args: List<String>): List<String> {
        if (args.isEmpty()) return emptyList()
        val subItems = node.bodyItems.filterIsInstance<CommandItem.Sub>()
        val paramCount = node.params.size

        if (args.size <= paramCount) {
            return listOf("<${node.params[args.size - 1].typeName ?: "any"}>")
        }

        val remainingArgs = args.drop(paramCount)
        if (remainingArgs.size == 1) {
            val prefix = remainingArgs[0]
            return subItems.map { it.node.name }.filter { it.startsWith(prefix, ignoreCase = true) }
        }

        val sub = subItems.find { it.node.name.equals(remainingArgs[0], ignoreCase = true) }
        return if (sub != null) buildTabCompletions(sub.node, remainingArgs.drop(1)) else emptyList()
    }

    private fun buildSenderObject(sender: CommandSender, label: String, args: Array<String>): JetValue.JObject {
        val senderObject = EventBridge.reflectToJetValue(sender)
        return EventBridge.overlayObject(
            senderObject,
            mapOf("rawtext" to JetValue.JString("/$label ${args.joinToString(" ")}".trimEnd())),
        )
    }

    private fun buildConsoleSenderObject(): JetValue.JObject =
        EventBridge.reflectToJetValue(plugin.server.consoleSender)

    private fun reportError(scriptName: String, message: String, line: Int, sourceLines: List<String>) {
        plugin.sendScriptError(formatScriptError(scriptName, message, sourceLines, line))
    }

    private fun formatScriptError(
        scriptName: String,
        message: String,
        sourceLines: List<String>,
        line: Int,
        prevLine: Int? = null,
    ): net.kyori.adventure.text.Component {
        return ScriptErrorFormatter.formatIssue(
            scriptName = scriptName,
            label = plugin.localeManager.get("script.error_label"),
            message = message,
            sourceLines = sourceLines,
            line = line,
            prevLine = prevLine,
        )
    }

}
