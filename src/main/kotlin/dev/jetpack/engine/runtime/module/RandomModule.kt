package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.*
import kotlin.random.Random

class RandomModule {

    fun spec(): ModuleSpec = ModuleSpec(
        name = "random",
        value = asValue(),
        fields = mapOf(
            "decimal" to callable(JetType.TFloat, signature(), signature(JetType.TInt, JetType.TInt), signature(JetType.TInt, JetType.TFloat), signature(JetType.TFloat, JetType.TInt), signature(JetType.TFloat, JetType.TFloat)),
            "integer" to callable(JetType.TInt, signature(JetType.TInt, JetType.TInt)),
            "select" to callable(JetType.TUnknown, signature(JetType.TList(JetType.TUnknown))),
            "shuffle" to callable(JetType.TList(JetType.TUnknown), signature(JetType.TList(JetType.TUnknown))),
        ),
    )

    fun asValue(): JetValue.JModule = JModule(
        mutableMapOf(
            "decimal"  to builtin(::randomDecimal),
            "integer"  to builtin(::randomInteger),
            "select"   to builtin(::select),
            "shuffle"  to builtin(::shuffle),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue = JBuiltin { handler(it) }

    private fun randomDecimal(args: List<JetValue>): JetValue = when (args.size) {
        0 -> JFloat(Random.nextDouble())
        2 -> {
            val min = requireNumeric(args[0], "random.decimal")
            val max = requireNumeric(args[1], "random.decimal")
            if (min > max) throw RuntimeException("Function 'random.decimal': min must not exceed max")
            JFloat(min + (max - min) * Random.nextDouble())
        }
        else -> throw RuntimeException("Function 'random.decimal' expects 0 or 2 arguments")
    }

    private fun randomInteger(args: List<JetValue>): JetValue = when (args.size) {
        2 -> {
            val min = requireInt(args[0], "random.integer")
            val max = requireInt(args[1], "random.integer")
            if (min > max) throw RuntimeException("Function 'random.integer': min must not exceed max")
            JInt(randomIntegerInRange(min, max))
        }
        else -> throw RuntimeException("Function 'random.integer' expects exactly 2 arguments")
    }

    private fun select(args: List<JetValue>): JetValue {
        val listArg = args[0] as JList
        val list = listArg.elements
        if (list.isEmpty())
            throw RuntimeException("Function 'random.select' cannot select from an empty list")
        return list[Random.nextInt(list.size)]
    }

    private fun shuffle(args: List<JetValue>): JetValue {
        val listArg = args[0] as JList
        return JList(listArg.elements.shuffled().toMutableList(), declaredElementType = listArg.declaredElementType)
    }

    private fun requireNumeric(value: JetValue, fn: String): Double = when (value) {
        is JInt   -> value.value.toDouble()
        is JFloat -> value.value
        else -> throw RuntimeException("Function '$fn' expects numeric arguments")
    }

    private fun requireInt(value: JetValue, fn: String): Int = when (value) {
        is JInt -> value.value
        else -> throw RuntimeException("Function '$fn' expects integer arguments")
    }

    private fun randomIntegerInRange(min: Int, max: Int): Int {
        if (min == max) return min
        if (max < Int.MAX_VALUE) {
            return Random.nextInt(min, max + 1)
        }
        if (min > Int.MIN_VALUE) {
            val sentinel = min - 1
            val sampled = Random.nextInt(sentinel, Int.MAX_VALUE)
            return if (sampled == sentinel) Int.MAX_VALUE else sampled
        }
        return Random.nextInt()
    }
}
