package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature

class ConversionBuiltin : Builtin {
    override fun methodType(targetType: JetType, method: String): JetType? = when (targetType) {
        JetType.TInt   -> when (method) {
            "toFloat"    -> callable(JetType.TFloat, signature())
            "toString"   -> callable(JetType.TString, signature())
            "toBool"     -> callable(JetType.TBool, signature())
            else         -> null
        }
        JetType.TFloat -> when (method) {
            "toInt"      -> callable(JetType.TInt, signature())
            "toString"   -> callable(JetType.TString, signature())
            else         -> null
        }
        JetType.TBool  -> when (method) {
            "toInt"      -> callable(JetType.TInt, signature())
            else         -> null
        }
        JetType.TString -> when (method) {
            "toInt"      -> callable(JetType.TInt, signature())
            "toFloat"    -> callable(JetType.TFloat, signature())
            else         -> null
        }
        else -> null
    }

    override fun resolveGlobal(name: String): JetFn? = null

    override fun resolveMethod(target: JetValue, method: String): JetFn? = when (target) {
        is JInt -> when (method) {
            "toFloat"  -> { _ -> JFloat(target.value.toDouble()) }
            "toString" -> { _ -> JString(target.toString()) }
            "toBool"   -> { _ -> JBool(target.value != 0) }
            else -> null
        }
        is JFloat -> when (method) {
            "toInt" -> { _ -> JInt(target.value.toInt()) }
            "toString" -> { _ -> JString(target.toString()) }
            else -> null
        }
        is JBool -> when (method) {
            "toInt" -> { _ -> JInt(if (target.value) 1 else 0) }
            else -> null
        }
        is JString -> when (method) {
            "toInt" -> { _ ->
                JInt(target.value.toIntOrNull()
                    ?: throw RuntimeException("String value '${target.value}' cannot be converted to int"))
            }
            "toFloat" -> { _ ->
                JFloat(target.value.toDoubleOrNull()
                    ?: throw RuntimeException("String value '${target.value}' cannot be converted to float"))
            }
            else -> null
        }
        else -> null
    }
}
