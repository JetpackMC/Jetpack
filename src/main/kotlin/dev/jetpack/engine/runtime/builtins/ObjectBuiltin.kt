package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*

class ObjectBuiltin : Builtin {
    override fun methodType(targetType: JetType, method: String): JetType? {
        if (targetType != JetType.TObject) return null
        return when (method) {
            "keys"   -> callable(JetType.TList(JetType.TString), signature())
            "has"    -> callable(JetType.TBool, signature(JetType.TString))
            "length" -> callable(JetType.TInt, signature())
            "remove" -> callable(JetType.TBool, signature(JetType.TString))
            "set"    -> callable(JetType.TBool, signature(JetType.TString, JetType.TUnknown))
            "get"    -> callable(JetType.TUnknown, signature(JetType.TString))
            "append" -> callable(JetType.TObject, signature(JetType.TObject))
            else     -> null
        }
    }

    override fun resolveGlobal(name: String): JetFn? = null

    override fun resolveMethod(target: JetValue, method: String): JetFn? {
        if (target !is JObject) return null
        return when (method) {
            "keys" -> { _ -> JList(target.fieldNames().map { JString(it) }.toMutableList()) }
            "has" -> { args ->
                JBool(target.hasField((args[0] as JString).value))
            }
            "length" -> { _ -> JInt(target.fieldNames().size) }
            "remove" -> { args ->
                if (!target.supportsStructuralMutation())
                    throw RuntimeException("Object method 'remove' cannot modify this object")
                JBool(target.fields.remove((args[0] as JString).value) != null)
            }
            "set" -> { args ->
                if (target.isReadOnly)
                    throw RuntimeException("Object method 'set' cannot modify a read-only object")
                target.setField((args[0] as JString).value, args[1])
                JBool(true)
            }
            "get" -> { args ->
                target.getField((args[0] as JString).value) ?: JNull
            }
            "append" -> { args ->
                val source = args[0] as JObject
                val newFields = linkedMapOf<String, JetValue>()
                for (key in target.fieldNames()) newFields[key] = target.getField(key) ?: JNull
                for (key in source.fieldNames()) newFields[key] = source.getField(key) ?: JNull
                JObject(newFields)
            }
            else -> null
        }
    }
}
