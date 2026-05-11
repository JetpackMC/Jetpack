package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.parser.ast.BuiltinTypeProvider
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.runtimeTypeOf
import org.bukkit.plugin.Plugin
import java.util.UUID


interface RegistrationHandle {
    fun unregister(): Boolean
}

class BuiltinRegistry : BuiltinTypeProvider {

    private data class ModuleRegistration(
        val id: UUID,
        val owner: Plugin?,
        val module: Builtin,
    )

    private val modules = mutableListOf<ModuleRegistration>()
    private val moduleTypes = mutableMapOf<String, Map<String, JetType>>()

    fun registerModuleType(name: String, fields: Map<String, JetType>) {
        moduleTypes[name] = fields
    }

    fun removeModuleType(name: String) {
        moduleTypes.remove(name)
    }

    fun register(module: Builtin) {
        val declaredGlobals = module.globalNames()
        val existingNames = allGlobalNames()
        for (name in declaredGlobals) {
            if (existingNames.contains(name)) {
                throw IllegalArgumentException("Builtin global '$name' is already registered")
            }
        }
        modules += ModuleRegistration(
            id = UUID.randomUUID(),
            owner = null,
            module = wrapModule(module),
        )
    }

    fun register(owner: Plugin, module: Builtin): RegistrationHandle {
        val declaredGlobals = module.globalNames()
        val existingNames = allGlobalNames()
        for (name in declaredGlobals) {
            if (existingNames.contains(name)) {
                throw IllegalArgumentException("Builtin global '$name' is already registered")
            }
        }

        val registration = ModuleRegistration(
            id = UUID.randomUUID(),
            owner = owner,
            module = wrapModule(module),
        )
        modules += registration

        return object : RegistrationHandle {
            override fun unregister(): Boolean = unregister(registration.id)
        }
    }

    fun unregister(id: UUID): Boolean =
        modules.removeIf { registration -> registration.id == id }

    fun unregisterAll(owner: Plugin): Boolean =
        modules.removeIf { registration -> registration.owner == owner }

    private fun activeModules(): Sequence<Builtin> =
        modules.asSequence().map { registration -> registration.module }

    private fun wrapModule(module: Builtin): Builtin {
        val declaredGlobals = module.globalNames().toSet()
        for (name in declaredGlobals) {
            val type = module.globalType(name)
                ?: throw IllegalArgumentException("Builtin global '$name' is missing type metadata")
            if (type !is JetType.TCallable) {
                throw IllegalArgumentException("Builtin global '$name' must use a callable type")
            }
            if (module.resolveGlobal(name) == null) {
                throw IllegalArgumentException("Builtin global '$name' is missing an implementation")
            }
        }

        return object : Builtin {
            override fun resolveGlobal(name: String): JetFn? {
                if (name !in declaredGlobals) return null
                val type = module.globalType(name)
                    ?: throw RuntimeException("Builtin global '$name' is missing type metadata")
                val callable = type as? JetType.TCallable
                    ?: throw RuntimeException("Builtin global '$name' must use a callable type")
                val fn = module.resolveGlobal(name)
                    ?: throw RuntimeException("Builtin global '$name' is missing an implementation")
                return validatedFunction("builtin global '$name'", callable, fn)
            }

            override fun resolveMethod(target: JetValue, method: String): JetFn? {
                val targetType = runtimeTypeOf(target)
                val type = module.methodType(targetType, method) ?: return null
                val callable = type as? JetType.TCallable
                    ?: throw RuntimeException("Builtin method '$method' on '$targetType' must use a callable type")
                val fn = module.resolveMethod(target, method)
                    ?: throw RuntimeException("Builtin method '$method' on '$targetType' is missing an implementation")
                return validatedFunction("builtin method '$method' on '${target.typeName()}'", callable, fn)
            }

            override fun globalType(name: String): JetType? =
                if (name in declaredGlobals) module.globalType(name) else null

            override fun methodType(targetType: JetType, method: String): JetType? =
                module.methodType(targetType, method)

            override fun globalNames(): Set<String> = declaredGlobals
        }
    }

    fun resolveGlobal(name: String): JetFn? {
        for (module in activeModules()) {
            module.resolveGlobal(name)?.let { return it }
        }
        return null
    }

    fun resolveMethod(target: JetValue, method: String): JetFn? {
        for (module in activeModules()) {
            module.resolveMethod(target, method)?.let { return it }
        }
        return null
    }

    override fun globalType(name: String): JetType? {
        for (module in activeModules()) {
            module.globalType(name)?.let { return it }
        }
        return null
    }

    override fun methodType(targetType: JetType, method: String): JetType? {
        for (module in activeModules()) {
            module.methodType(targetType, method)?.let { return it }
        }
        return null
    }

    override fun moduleFieldType(moduleName: String, field: String): JetType? =
        moduleTypes[moduleName]?.get(field)

    override fun isKnownGlobal(name: String): Boolean =
        activeModules().any { it.globalType(name) != null || it.globalNames().contains(name) }

    fun allGlobalNames(): Set<String> = activeModules().flatMapTo(mutableSetOf()) { module -> module.globalNames() }

    companion object {
        fun createDefault(): BuiltinRegistry {
            val registry = BuiltinRegistry()
            registry.register(StringBuiltin())
            registry.register(ListBuiltin())
            registry.register(ObjectBuiltin())
            registry.register(IntervalBuiltin())
            registry.register(ListenerBuiltin())
            registry.register(CommandBuiltin())
            registry.register(TypeBuiltin())
            registry.register(ConversionBuiltin())
            return registry
        }
    }
}
