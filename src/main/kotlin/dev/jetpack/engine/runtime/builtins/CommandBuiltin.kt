package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JCommand
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature

class CommandBuiltin : Builtin {
    override fun methodType(targetType: JetType, method: String): JetType? {
        if (targetType != JetType.TCommand) return null
        return when (method) {
            "activate", "deactivate", "destroy", "isActive" -> callable(JetType.TBool, signature())
            "trigger" -> callable(JetType.TNull, signature(variadicType = JetType.TUnknown))
            else -> null
        }
    }

    override fun resolveGlobal(name: String): JetFn? = null

    override fun resolveMethod(target: JetValue, method: String): JetFn? {
        if (target !is JCommand) return null
        return when (method) {
            "activate" -> { _ -> JBool(target.handle.activate()) }
            "deactivate" -> { _ -> JBool(target.handle.deactivate()) }
            "destroy" -> { _ -> JBool(target.handle.destroy()) }
            "trigger" -> { args ->
                val (sender, callArgs) = if (target.handle.hasSender) {
                    val senderArg = args.firstOrNull() ?: JNull
                    Pair(senderArg, if (args.isEmpty()) emptyList() else args.drop(1))
                } else {
                    Pair(JNull, args)
                }
                target.handle.triggerPath(target.path, sender, callArgs)
                JNull
            }
            "isActive" -> { _ -> JBool(target.handle.isActive()) }
            else -> null
        }
    }
}
