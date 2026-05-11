package dev.jetpack.engine.runtime.module

import dev.jetpack.engine.parser.ast.CallSignature
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JFloat
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JModule
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.math.tan

class MathModule {

    fun spec(): ModuleSpec = ModuleSpec(
        name = NAME,
        value = asValue(),
        fields = fields(),
    )

    fun asValue(): JetValue.JModule = JModule(
        mutableMapOf(
            "PI"       to JFloat(3.141592653589793),
            "E"        to JFloat(2.718281828459045),
            "LN2"      to JFloat(0.6931471805599453),
            "LN10"     to JFloat(2.302585092994046),
            "LOG2E"    to JFloat(1.4426950408889634),
            "LOG10E"   to JFloat(0.4342944819032518),
            "SQRT1_2"  to JFloat(0.7071067811865476),
            "SQRT2"    to JFloat(1.4142135623730951),
            "min"      to builtin(::min),
            "max"      to builtin(::max),
            "clamp"    to builtin(::clamp),
            "sqrt"     to builtin(::sqrtValue),
            "abs"      to builtin(::absValue),
            "round"    to builtin(::roundValue),
            "ceil"     to builtin(::ceilValue),
            "floor"    to builtin(::floorValue),
            "sin"      to builtin(::sinValue),
            "cos"      to builtin(::cosValue),
            "tan"      to builtin(::tanValue),
            "asin"     to builtin(::asinValue),
            "acos"     to builtin(::acosValue),
            "atan"     to builtin(::atanValue),
            "atan2"    to builtin(::atan2Value),
            "hypot"    to builtin(::hypotValue),
            "cbrt"     to builtin(::cbrtValue),
            "log"      to builtin(::logValue),
            "log2"     to builtin(::log2Value),
            "log10"    to builtin(::log10Value),
            "exp"      to builtin(::expValue),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue = JBuiltin { handler(it) }

    private fun min(args: List<JetValue>): JetValue {
        val a = requireNumeric(args[0], "math.min")
        val b = requireNumeric(args[1], "math.min")
        return numericResult(minOf(a.toNumericDouble(), b.toNumericDouble()), a, b)
    }

    private fun max(args: List<JetValue>): JetValue {
        val a = requireNumeric(args[0], "math.max")
        val b = requireNumeric(args[1], "math.max")
        return numericResult(maxOf(a.toNumericDouble(), b.toNumericDouble()), a, b)
    }

    private fun clamp(args: List<JetValue>): JetValue {
        val value = requireNumeric(args[0], "math.clamp")
        val minimum = requireNumeric(args[1], "math.clamp")
        val maximum = requireNumeric(args[2], "math.clamp")
        val result = value.toNumericDouble().coerceIn(minimum.toNumericDouble(), maximum.toNumericDouble())
        return numericResult(result, value, minimum, maximum)
    }

    private fun sqrtValue(args: List<JetValue>): JetValue {
        return JFloat(sqrt(requireNumeric(args[0], "math.sqrt").toNumericDouble()))
    }

    private fun absValue(args: List<JetValue>): JetValue {
        val value = requireNumeric(args[0], "math.abs")
        return if (value is JInt) JInt(abs(value.value)) else JFloat(abs((value as JFloat).value))
    }

    private fun roundValue(args: List<JetValue>): JetValue {
        val value = requireNumeric(args[0], "math.round")
        val digits = if (args.size == 1) 0 else (args[1] as JInt).value
        val factor = 10.0.pow(digits)
        val result = round(value.toNumericDouble() * factor) / factor
        return if (digits == 0) JInt(result.toInt()) else JFloat(result)
    }

    private fun ceilValue(args: List<JetValue>): JetValue {
        return JInt(ceil(requireNumeric(args[0], "math.ceil").toNumericDouble()).toInt())
    }

    private fun floorValue(args: List<JetValue>): JetValue {
        return JInt(floor(requireNumeric(args[0], "math.floor").toNumericDouble()).toInt())
    }

    private fun sinValue(args: List<JetValue>): JetValue {
        return JFloat(sin(requireNumeric(args[0], "math.sin").toNumericDouble()))
    }

    private fun cosValue(args: List<JetValue>): JetValue {
        return JFloat(cos(requireNumeric(args[0], "math.cos").toNumericDouble()))
    }

    private fun tanValue(args: List<JetValue>): JetValue {
        return JFloat(tan(requireNumeric(args[0], "math.tan").toNumericDouble()))
    }

    private fun logValue(args: List<JetValue>): JetValue {
        val value = requireNumeric(args[0], "math.log").toNumericDouble()
        if (value <= 0) throw RuntimeException("Function 'math.log' requires a positive value.")
        return JFloat(ln(value))
    }

    private fun log10Value(args: List<JetValue>): JetValue {
        val value = requireNumeric(args[0], "math.log10").toNumericDouble()
        if (value <= 0) throw RuntimeException("Function 'math.log10' requires a positive value.")
        return JFloat(log10(value))
    }

    private fun log2Value(args: List<JetValue>): JetValue {
        val value = requireNumeric(args[0], "math.log2").toNumericDouble()
        if (value <= 0) throw RuntimeException("Function 'math.log2' requires a positive value.")
        return JFloat(log2(value))
    }

    private fun expValue(args: List<JetValue>): JetValue {
        return JFloat(exp(requireNumeric(args[0], "math.exp").toNumericDouble()))
    }

    private fun asinValue(args: List<JetValue>): JetValue {
        return JFloat(asin(requireNumeric(args[0], "math.asin").toNumericDouble()))
    }

    private fun acosValue(args: List<JetValue>): JetValue {
        return JFloat(acos(requireNumeric(args[0], "math.acos").toNumericDouble()))
    }

    private fun atanValue(args: List<JetValue>): JetValue {
        return JFloat(atan(requireNumeric(args[0], "math.atan").toNumericDouble()))
    }

    private fun atan2Value(args: List<JetValue>): JetValue {
        val y = requireNumeric(args[0], "math.atan2").toNumericDouble()
        val x = requireNumeric(args[1], "math.atan2").toNumericDouble()
        return JFloat(atan2(y, x))
    }

    private fun hypotValue(args: List<JetValue>): JetValue {
        val a = requireNumeric(args[0], "math.hypot").toNumericDouble()
        val b = requireNumeric(args[1], "math.hypot").toNumericDouble()
        return JFloat(hypot(a, b))
    }

    private fun cbrtValue(args: List<JetValue>): JetValue {
        return JFloat(cbrt(requireNumeric(args[0], "math.cbrt").toNumericDouble()))
    }

    private fun requireNumeric(value: JetValue, fn: String): JetValue {
        if (!value.isNumeric()) throw RuntimeException("Function '$fn' expects numeric arguments.")
        return value
    }

    private fun numericResult(result: Double, vararg args: JetValue): JetValue =
        if (args.all { it is JInt } && result >= Int.MIN_VALUE && result <= Int.MAX_VALUE && result == result.toInt().toDouble()) JInt(result.toInt())
        else JFloat(result)

    companion object {
        private const val NAME = "math"
        private val numericTypes = listOf(JetType.TInt, JetType.TFloat)

        private fun numericOverloads(arity: Int): Array<CallSignature> {
            val signatures = mutableListOf<CallSignature>()

            fun build(params: List<JetType>) {
                if (params.size == arity) {
                    val returnType = if (params.all { it == JetType.TInt }) JetType.TInt else JetType.TFloat
                    signatures += signature(*params.toTypedArray(), returnType = returnType)
                    return
                }
                for (type in numericTypes) {
                    build(params + type)
                }
            }

            build(emptyList())
            return signatures.toTypedArray()
        }

        private fun fields(): Map<String, JetType> {
            return mapOf(
                "PI" to JetType.TFloat,
                "E" to JetType.TFloat,
                "LN2" to JetType.TFloat,
                "LN10" to JetType.TFloat,
                "LOG2E" to JetType.TFloat,
                "LOG10E" to JetType.TFloat,
                "SQRT1_2" to JetType.TFloat,
                "SQRT2" to JetType.TFloat,
                "min" to callable(JetType.TFloat, *numericOverloads(2)),
                "max" to callable(JetType.TFloat, *numericOverloads(2)),
                "clamp" to callable(JetType.TFloat, *numericOverloads(3)),
                "sqrt" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "abs" to callable(
                    JetType.TFloat,
                    signature(JetType.TInt, returnType = JetType.TInt),
                    signature(JetType.TFloat, returnType = JetType.TFloat),
                ),
                "round" to callable(
                    JetType.TFloat,
                    signature(JetType.TInt, returnType = JetType.TInt),
                    signature(JetType.TFloat, returnType = JetType.TInt),
                    signature(JetType.TInt, JetType.TInt, returnType = JetType.TFloat),
                    signature(JetType.TFloat, JetType.TInt, returnType = JetType.TFloat),
                ),
                "ceil" to callable(JetType.TInt, *numericUnarySignatures(JetType.TInt)),
                "floor" to callable(JetType.TInt, *numericUnarySignatures(JetType.TInt)),
                "sin" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "cos" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "tan" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "asin" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "acos" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "atan" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "atan2" to callable(JetType.TFloat, *numericBinarySignatures(JetType.TFloat)),
                "hypot" to callable(JetType.TFloat, *numericBinarySignatures(JetType.TFloat)),
                "cbrt" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "log" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "log2" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "log10" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
                "exp" to callable(JetType.TFloat, *numericUnarySignatures(JetType.TFloat)),
            )
        }

        private fun numericUnarySignatures(returnType: JetType): Array<CallSignature> = arrayOf(
            signature(JetType.TInt, returnType = returnType),
            signature(JetType.TFloat, returnType = returnType),
        )

        private fun numericBinarySignatures(returnType: JetType): Array<CallSignature> = arrayOf(
            signature(JetType.TInt, JetType.TInt, returnType = returnType),
            signature(JetType.TInt, JetType.TFloat, returnType = returnType),
            signature(JetType.TFloat, JetType.TInt, returnType = returnType),
            signature(JetType.TFloat, JetType.TFloat, returnType = returnType),
        )
    }
}
