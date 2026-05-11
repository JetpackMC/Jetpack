package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import dev.jetpack.engine.runtime.coerceValueForList
import dev.jetpack.engine.runtime.describeListElementExpectation
import dev.jetpack.engine.runtime.firstNonNullListElement
import dev.jetpack.engine.runtime.jetEquals
import dev.jetpack.engine.runtime.listAcceptsValue
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature

class ListBuiltin : Builtin {
    private companion object {
        private fun compareJetValues(a: JetValue, b: JetValue): Int = when {
            a is JInt && b is JInt       -> a.value.compareTo(b.value)
            a is JFloat && b is JFloat   -> a.value.compareTo(b.value)
            a is JInt && b is JFloat     -> a.value.toDouble().compareTo(b.value)
            a is JFloat && b is JInt     -> a.value.compareTo(b.value.toDouble())
            a is JString && b is JString -> a.value.compareTo(b.value)
            else -> throw RuntimeException("List 'ascend'/'descend' requires all elements to be int, float, or string")
        }

        private val DESCEND_COMPARATOR = Comparator<JetValue> { a, b -> compareJetValues(b, a) }
    }

    override fun methodType(targetType: JetType, method: String): JetType? {
        if (targetType !is JetType.TList) return null
        return when (method) {
            "length"                               -> callable(JetType.TInt, signature())
            "contains"                             -> callable(JetType.TBool, signature(targetType.elementType))
            "append"                               -> callable(targetType, signature(targetType.elementType))
            "remove"                               -> callable(targetType, signature(JetType.TInt))
            "ascend", "descend", "reverse"         -> callable(targetType, signature())
            "slice"                                -> callable(targetType, signature(JetType.TInt, JetType.TInt))
            "first", "last"                        -> callable(targetType.elementType, signature())
            "indexOf"                              -> callable(JetType.TInt, signature(targetType.elementType), signature(targetType.elementType, JetType.TInt))
            "lastIndexOf"                          -> callable(JetType.TInt, signature(targetType.elementType))
            "join"                                 -> callable(JetType.TString, signature(JetType.TString))
            else                                   -> null
        }
    }

    override fun resolveGlobal(name: String): JetFn? = null

    override fun resolveMethod(target: JetValue, method: String): JetFn? {
        if (target !is JList) return null
        return when (method) {
            "length" -> { _ -> JInt(target.elements.size) }
            "contains" -> { args ->
                var found = false
                for (element in target.elements) {
                    if (jetEquals(element, args[0])) {
                        found = true
                        break
                    }
                }
                JBool(found)
            }
            "append" -> { args ->
                val anchor = firstNonNullListElement(target)
                val expectedElement = describeListElementExpectation(target, anchor) ?: "unknown"
                if (!listAcceptsValue(target, args[0], anchor)) {
                    throw RuntimeException(
                        "Cannot append '${args[0].typeName()}' to list of '$expectedElement'"
                    )
                }
                val coerced = coerceValueForList(target, args[0], anchor)
                val newElements = target.elements.toMutableList()
                newElements.add(coerced)
                JList(newElements, declaredElementType = target.declaredElementType)
            }
            "remove" -> { args ->
                val index = (args[0] as JInt).value
                if (index < 0 || index >= target.elements.size)
                    throw RuntimeException("List index is out of range")
                val newElements = target.elements.toMutableList()
                newElements.removeAt(index)
                JList(newElements, declaredElementType = target.declaredElementType)
            }
            "first" -> { args ->
                if (target.elements.isEmpty()) throw RuntimeException("Cannot call 'first' on an empty list")
                target.elements.first()
            }
            "last" -> { args ->
                if (target.elements.isEmpty()) throw RuntimeException("Cannot call 'last' on an empty list")
                target.elements.last()
            }
            "indexOf" -> { args ->
                val fromIndex = if (args.size == 2) (args[1] as JInt).value.coerceAtLeast(0) else 0
                var result = -1
                for (i in fromIndex until target.elements.size) {
                    if (jetEquals(target.elements[i], args[0])) {
                        result = i
                        break
                    }
                }
                JInt(result)
            }
            "lastIndexOf" -> { args ->
                var result = -1
                for (i in target.elements.lastIndex downTo 0) {
                    if (jetEquals(target.elements[i], args[0])) {
                        result = i
                        break
                    }
                }
                JInt(result)
            }
            "ascend" -> { _ ->
                JList(target.elements.sortedWith(::compareJetValues).toMutableList(), declaredElementType = target.declaredElementType)
            }
            "descend" -> { _ ->
                JList(target.elements.sortedWith(DESCEND_COMPARATOR).toMutableList(), declaredElementType = target.declaredElementType)
            }
            "slice" -> { args ->
                val from = (args[0] as JInt).value
                val to = (args[1] as JInt).value
                if (from < 0 || to < 0 || from > target.elements.size || to > target.elements.size || from > to)
                    throw RuntimeException("List method 'slice' index out of range")
                JList(target.elements.subList(from, to).toMutableList(), declaredElementType = target.declaredElementType)
            }
            "reverse" -> { _ ->
                JList(target.elements.reversed().toMutableList(), declaredElementType = target.declaredElementType)
            }
            "join" -> { args ->
                val delimiter = (args[0] as JString).value
                JString(target.elements.joinToString(delimiter) { it.toString() })
            }
            else -> null
        }
    }

}
