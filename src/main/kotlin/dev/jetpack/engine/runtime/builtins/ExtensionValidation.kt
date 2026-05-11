package dev.jetpack.engine.runtime.builtins

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.runtimeTypeOf

internal fun validatedFunction(
    label: String,
    callable: JetType.TCallable,
    fn: JetFn,
): JetFn = { args ->
    validateArguments(label, callable, args)
    val result = fn(args)
    validateReturn(label, callable, result)
    result
}

private fun validateArguments(
    label: String,
    callable: JetType.TCallable,
    args: List<JetValue>,
) {
    if (callable.signatures.isEmpty()) return

    val argTypes = args.map(::runtimeTypeOf)
    val matches = callable.signatures.any { signature -> signature.matchScore(argTypes) != null }
    if (matches) return

    val expected = callable.signatures.joinToString(" or ") { signature -> signature.describe() }
    val actual = "(${argTypes.joinToString(", ")})"
    throw RuntimeException("$label expects $expected but got $actual")
}

private fun validateReturn(
    label: String,
    callable: JetType.TCallable,
    result: JetValue,
) {
    val expected = callable.returnType
    if (expected == JetType.TUnknown) return

    val actual = runtimeTypeOf(result)
    if (!expected.accepts(actual)) {
        throw RuntimeException("$label returned '$actual' but expected '$expected'")
    }
}
