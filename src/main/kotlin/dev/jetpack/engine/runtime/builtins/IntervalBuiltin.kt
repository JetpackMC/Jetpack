package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JInterval
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature

class IntervalBuiltin : Builtin {
    override fun methodType(targetType: JetType, method: String): JetType? {
        if (targetType != JetType.TInterval) return null
        return when (method) {
            "activate", "deactivate", "destroy", "isActive" -> callable(JetType.TBool, signature())
            "trigger" -> callable(JetType.TNull, signature())
            else -> null
        }
    }

    override fun resolveGlobal(name: String): JetFn? = null

    override fun resolveMethod(target: JetValue, method: String): JetFn? {
        if (target !is JInterval) return null
        return when (method) {
            "activate" -> { _ -> JBool(target.handle.activate()) }
            "deactivate" -> { _ -> JBool(target.handle.deactivate()) }
            "destroy" -> { _ -> JBool(target.handle.destroy()) }
            "trigger" -> { _ ->
                target.handle.trigger()
                JNull
            }
            "isActive" -> { _ -> JBool(target.handle.isActive()) }
            else -> null
        }
    }
}
