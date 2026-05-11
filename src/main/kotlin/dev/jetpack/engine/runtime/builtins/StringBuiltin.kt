package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature

class StringBuiltin : Builtin {
    override fun methodType(targetType: JetType, method: String): JetType? {
        if (targetType != JetType.TString) return null
        return when (method) {
            "length"                         -> callable(JetType.TInt, signature())
            "contains"                       -> callable(JetType.TBool, signature(JetType.TString))
            "replace"                        -> callable(JetType.TString, signature(JetType.TString, JetType.TString))
            "lower", "upper", "trim"         -> callable(JetType.TString, signature())
            "substring"                      -> callable(JetType.TString, signature(JetType.TInt, JetType.TInt))
            "split"                          -> callable(JetType.TList(JetType.TString), signature(JetType.TString))
            "indexOf"                        -> callable(JetType.TInt, signature(JetType.TString), signature(JetType.TString, JetType.TInt))
            "lastIndexOf"                    -> callable(JetType.TInt, signature(JetType.TString))
            "count"                          -> callable(JetType.TInt, signature(JetType.TString))
            "startsWith", "endsWith"         -> callable(JetType.TBool, signature(JetType.TString))
            else                             -> null
        }
    }

    override fun resolveGlobal(name: String): JetFn? = null

    override fun resolveMethod(target: JetValue, method: String): JetFn? {
        if (target !is JString) return null
        return when (method) {
            "length" -> { _ -> JInt(target.value.length) }
            "contains" -> { args ->
                JBool(target.value.contains((args[0] as JString).value))
            }
            "replace" -> { args ->
                JString(target.value.replace((args[0] as JString).value, (args[1] as JString).value))
            }
            "split" -> { args ->
                val delim = (args[0] as JString).value
                if (delim.isEmpty()) throw RuntimeException("String method 'split' does not allow an empty delimiter")
                JList(target.value.split(delim).map { JString(it) }.toMutableList())
            }
            "lower" -> { _ -> JString(target.value.lowercase()) }
            "upper" -> { _ -> JString(target.value.uppercase()) }
            "trim" -> { _ -> JString(target.value.trim()) }
            "substring" -> { args ->
                val from = (args[0] as JInt).value
                val to = (args[1] as JInt).value
                if (from < 0 || to < 0 || from > target.value.length || to > target.value.length || from > to)
                    throw RuntimeException("String method 'substring' index out of range")
                JString(target.value.substring(from, to))
            }
            "indexOf" -> { args ->
                val str = (args[0] as JString).value
                val fromIndex = if (args.size == 2) {
                    (args[1] as JInt).value
                } else 0
                JInt(target.value.indexOf(str, fromIndex))
            }
            "lastIndexOf" -> { args ->
                JInt(target.value.lastIndexOf((args[0] as JString).value))
            }
            "startsWith" -> { args ->
                JBool(target.value.startsWith((args[0] as JString).value))
            }
            "endsWith" -> { args ->
                JBool(target.value.endsWith((args[0] as JString).value))
            }
            "count" -> { args ->
                val sub = (args[0] as JString).value
                if (sub.isEmpty()) throw RuntimeException("String method 'count' does not allow an empty substring")
                var count = 0
                var index = 0
                while (true) {
                    index = target.value.indexOf(sub, index)
                    if (index == -1) break
                    count++
                    index += sub.length
                }
                JInt(count)
            }
            else -> null
        }
    }
}
