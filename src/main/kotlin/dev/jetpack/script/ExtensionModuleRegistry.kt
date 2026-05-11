package dev.jetpack.script

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.builtins.Builtin
import dev.jetpack.engine.runtime.builtins.BuiltinRegistry
import dev.jetpack.engine.runtime.builtins.RegistrationHandle
import dev.jetpack.engine.runtime.builtins.validatedFunction
import dev.jetpack.engine.runtime.runtimeTypeOf
import org.bukkit.plugin.Plugin
import java.util.UUID

internal data class RegisteredModule(
    val name: String,
    val value: JetValue.JModule,
    val fields: Map<String, JetType>,
)

internal class ExtensionModuleRegistry {

    private data class ModuleRegistration(
        val id: UUID,
        val owner: Plugin,
        val name: String,
        val value: JetValue.JModule,
        val fields: Map<String, JetType>,
    )

    val builtinRegistry: BuiltinRegistry = BuiltinRegistry.createDefault()
    private val namedModules = linkedMapOf<String, ModuleRegistration>()

    fun registerModule(owner: Plugin, builtin: Builtin): RegistrationHandle {
        val duplicate = builtin.globalNames().firstOrNull { name -> name in namedModules }
        if (duplicate != null) {
            throw IllegalArgumentException("Builtin global '$duplicate' collides with module '$duplicate'")
        }
        return builtinRegistry.register(owner, builtin)
    }

    fun registerModule(
        owner: Plugin,
        name: String,
        value: JetValue.JModule,
        fields: Map<String, JetType>,
    ): RegistrationHandle {
        if (name in namedModules) {
            throw IllegalArgumentException("Module '$name' is already registered")
        }
        if (name in builtinRegistry.allGlobalNames()) {
            throw IllegalArgumentException("Module '$name' collides with a built-in global")
        }

        val declaredType = JetType.TModule(fields)
        val validatedValue = validateModule(name, value, declaredType)
        val registration = ModuleRegistration(
            id = UUID.randomUUID(),
            owner = owner,
            name = name,
            value = validatedValue,
            fields = fields,
        )

        builtinRegistry.registerModuleType(name, fields)
        namedModules[name] = registration

        return object : RegistrationHandle {
            override fun unregister(): Boolean = unregisterModule(registration.id)
        }
    }

    fun unregisterExtensions(owner: Plugin): Boolean {
        val removedModules = builtinRegistry.unregisterAll(owner)
        val removedNamedModules = unregisterNamedModules(owner)
        return removedModules || removedNamedModules
    }

    fun namedModuleNames(): Set<String> = namedModules.keys

    fun namedModule(name: String): RegisteredModule? {
        val registration = namedModules[name] ?: return null
        return RegisteredModule(
            name = registration.name,
            value = registration.value,
            fields = registration.fields,
        )
    }

    fun builtinGlobalNames(): Set<String> = builtinRegistry.allGlobalNames()

    private fun unregisterModule(id: UUID): Boolean {
        val entry = namedModules.values.firstOrNull { registration -> registration.id == id } ?: return false
        builtinRegistry.removeModuleType(entry.name)
        namedModules.remove(entry.name)
        return true
    }

    private fun unregisterNamedModules(owner: Plugin): Boolean {
        val targets = namedModules.values.filter { registration -> registration.owner == owner }
        if (targets.isEmpty()) return false

        for (registration in targets) {
            builtinRegistry.removeModuleType(registration.name)
            namedModules.remove(registration.name)
        }
        return true
    }

    private fun validateModule(
        name: String,
        value: JetValue.JModule,
        declaredType: JetType.TModule,
    ): JetValue.JModule {
        val actualFields = value.fieldNames()
        val missing = declaredType.fields.keys.filterNot(actualFields::contains)
        if (missing.isNotEmpty()) {
            throw IllegalArgumentException("Module '$name' is missing declared members: ${missing.joinToString(", ")}")
        }

        val undeclared = actualFields.filterNot(declaredType.fields::containsKey)
        if (undeclared.isNotEmpty()) {
            throw IllegalArgumentException("Module '$name' exposes undeclared members: ${undeclared.joinToString(", ")}")
        }

        val validatedFields = linkedMapOf<String, JetValue>()
        for ((fieldName, fieldType) in declaredType.fields) {
            val fieldValue = value.getField(fieldName)
                ?: throw IllegalArgumentException("Module '$name' is missing declared member '$fieldName'")
            validatedFields[fieldName] = validateModuleField("$name.$fieldName", fieldValue, fieldType)
        }

        return JetValue.JModule(validatedFields, declaredType)
    }

    private fun validateModuleField(
        label: String,
        value: JetValue,
        declaredType: JetType,
    ): JetValue = when (declaredType) {
        is JetType.TCallable -> {
            val builtin = value as? JetValue.JBuiltin
                ?: throw IllegalArgumentException("Module member '$label' must be a builtin function")
            JetValue.JBuiltin(validatedFunction("module member '$label'", declaredType, builtin.fn))
        }
        is JetType.TModule -> {
            val nested = value as? JetValue.JModule
                ?: throw IllegalArgumentException("Module member '$label' must be a module")
            validateModule(label, nested, declaredType)
        }
        else -> {
            val actualType = runtimeTypeOf(value)
            if (declaredType != JetType.TUnknown && !declaredType.accepts(actualType)) {
                throw IllegalArgumentException("Module member '$label' expected '$declaredType' but got '$actualType'")
            }
            value
        }
    }
}
