package dev.jetpack.engine.runtime

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.paramsToCallSignature
import dev.jetpack.engine.runtime.JetValue.JBool
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JCommand
import dev.jetpack.engine.runtime.JetValue.JFloat
import dev.jetpack.engine.runtime.JetValue.JFunction
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JInterval
import dev.jetpack.engine.runtime.JetValue.JList
import dev.jetpack.engine.runtime.JetValue.JListener
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.JetValue.JString

fun runtimeTypeOf(value: JetValue): JetType = when (value) {
    is JInt -> JetType.TInt
    is JFloat -> JetType.TFloat
    is JString -> JetType.TString
    is JBool -> JetType.TBool
    is JList -> JetType.TList(value.declaredElementType ?: inferListElementType(value.elements))
    is JModule -> value.declaredType
    is JObject -> JetType.TObject
    JNull -> JetType.TNull
    is JFunction -> JetType.TCallable(value.returnType ?: JetType.TUnknown, listOf(paramsToCallSignature(value.params)))
    is JBuiltin -> JetType.TFunction
    is JInterval -> JetType.TInterval
    is JListener -> JetType.TListener
    is JCommand -> JetType.TCommand
}

fun coerceValueToType(value: JetValue, expectedType: JetType?): JetValue = when (expectedType) {
    null,
    JetType.TUnknown,
    JetType.TString,
    JetType.TBool,
    JetType.TObject,
    JetType.TNull,
    JetType.TFunction,
    JetType.TInterval,
    JetType.TListener,
    JetType.TCommand,
    is JetType.TModule,
    is JetType.TCallable -> value

    JetType.TInt -> when (value) {
        is JFloat -> JInt(value.value.toInt())
        else -> value
    }

    JetType.TFloat -> when (value) {
        is JInt -> JFloat(value.value.toDouble())
        else -> value
    }

    is JetType.TNullable -> if (value is JNull) JNull else coerceValueToType(value, expectedType.innerType)

    is JetType.TList -> when (value) {
        is JList -> {
            for (index in value.elements.indices) {
                value.elements[index] = coerceValueToType(value.elements[index], expectedType.elementType)
            }
            if (value.declaredElementType == null || value.declaredElementType == JetType.TUnknown) {
                value.declaredElementType = expectedType.elementType
            }
            value
        }
        else -> value
    }
}

fun firstNonNullListElement(list: JList): JetValue? =
    list.elements.firstOrNull { it !is JNull }

fun listAcceptsValue(list: JList, value: JetValue, anchor: JetValue? = firstNonNullListElement(list)): Boolean {
    if (anchor != null) return listElementCompatible(anchor, value)

    val declaredElementType = list.declaredElementType ?: return true
    return declaredElementType.accepts(runtimeTypeOf(value))
}

fun coerceValueForList(list: JList, value: JetValue, anchor: JetValue? = firstNonNullListElement(list)): JetValue {
    return when {
        anchor != null -> coerceValueToAnchor(anchor, value)
        list.declaredElementType != null -> coerceValueToType(value, list.declaredElementType)
        else -> value
    }
}

fun describeListElementExpectation(list: JList, anchor: JetValue? = firstNonNullListElement(list)): String? =
    anchor?.typeName() ?: list.declaredElementType?.toString()

private fun listElementCompatible(existing: JetValue, newValue: JetValue): Boolean = when {
    existing is JNull || newValue is JNull -> true
    existing.isNumeric() && newValue.isNumeric() -> true
    existing is JList && newValue is JList -> {
        val existingAnchor = existing.elements.firstOrNull { it !is JNull }
        val newAnchor = newValue.elements.firstOrNull { it !is JNull }
        existingAnchor == null || newAnchor == null || listElementCompatible(existingAnchor, newAnchor)
    }
    existing.typeName() == newValue.typeName() -> true
    else -> false
}

private fun coerceValueToAnchor(anchor: JetValue, value: JetValue): JetValue = when {
    anchor is JInt && value is JFloat -> JInt(value.value.toInt())
    anchor is JFloat && value is JInt -> JFloat(value.value.toDouble())
    else -> value
}

private fun inferListElementType(elements: List<JetValue>): JetType {
    val elementTypes = elements.map(::runtimeTypeOf)
    if (elementTypes.isEmpty()) return JetType.TUnknown

    val nonUnknown = elementTypes.filter { it != JetType.TUnknown }
    if (nonUnknown.isEmpty()) return JetType.TUnknown
    if (nonUnknown.all { it == JetType.TInt }) return JetType.TInt
    if (nonUnknown.all { it.isNumeric() }) return JetType.TFloat

    val first = nonUnknown.first()
    return if (nonUnknown.all { first.accepts(it) }) first else JetType.TUnknown
}
