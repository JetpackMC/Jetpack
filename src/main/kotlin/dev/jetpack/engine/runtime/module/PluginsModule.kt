package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JString
import dev.jetpack.engine.runtime.nativeapi.NativeAccessException
import dev.jetpack.engine.runtime.nativeapi.NativeBridge
import org.bukkit.plugin.PluginManager
import java.lang.reflect.Modifier

class PluginsModule(private val pluginManager: PluginManager) {

    fun spec(): ModuleSpec = ModuleSpec(
        name = "plugins",
        value = asValue(),
        fields = mapOf(
            "get" to callable(JetType.TNullable(JetType.TUnknown), signature(JetType.TString)),
            "enabled" to callable(JetType.TBool, signature(JetType.TString)),
            "type" to callable(JetType.TUnknown, signature(JetType.TString, JetType.TString)),
        ),
    )

    fun asValue(): JModule = JModule(
        mutableMapOf(
            "get" to builtin(::get),
            "enabled" to builtin(::enabled),
            "type" to builtin(::type),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue = JBuiltin { handler(it) }

    private fun get(args: List<JetValue>): JetValue {
        val owner = findEnabledPlugin((args[0] as JString).value) ?: return JNull
        return NativeBridge.wrapOwned(owner, owner)
    }

    private fun enabled(args: List<JetValue>): JetValue =
        JBool.of(findEnabledPlugin((args[0] as JString).value) != null)

    private fun type(args: List<JetValue>): JetValue {
        val pluginName = (args[0] as JString).value
        val className = (args[1] as JString).value
        val owner = findEnabledPlugin(pluginName)
            ?: throw NativeAccessException("Plugin '$pluginName' is not enabled")
        val ownerClassLoader = owner.javaClass.classLoader
        val type = try {
            Class.forName(className, false, ownerClassLoader)
        } catch (error: ClassNotFoundException) {
            throw NativeAccessException("Class '$className' was not found in plugin '$pluginName'")
        } catch (error: LinkageError) {
            throw NativeAccessException(
                "Class '$className' could not be loaded from plugin '$pluginName': " +
                    (error.message ?: error.javaClass.simpleName),
            )
        } catch (error: SecurityException) {
            throw NativeAccessException("Class '$className' cannot be accessed in plugin '$pluginName'")
        }

        if (type.classLoader !== ownerClassLoader) {
            throw NativeAccessException("Class '$className' is not owned by plugin '$pluginName'")
        }
        if (!Modifier.isPublic(type.modifiers)) {
            throw NativeAccessException("Class '$className' is not public")
        }
        return NativeBridge.wrapOwnedClass(type, owner)
    }

    private fun findEnabledPlugin(name: String) =
        pluginManager.getPlugin(name)?.takeIf { it.isEnabled }
}
