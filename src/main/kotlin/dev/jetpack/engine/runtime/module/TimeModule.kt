package dev.jetpack.engine.runtime.module

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.callable
import dev.jetpack.engine.parser.ast.signature
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JModule
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.JetValue.JString
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlinx.coroutines.delay as coroutinesDelay
import kotlin.time.Duration.Companion.milliseconds

class TimeModule(private val plugin: JetpackPlugin) {

    fun spec(): ModuleSpec = ModuleSpec(
        name = "time",
        value = asValue(),
        fields = mapOf(
            "now" to callable(JetType.TObject, signature()),
            "format" to callable(JetType.TString, signature(JetType.TObject, JetType.TString)),
            "parse" to callable(JetType.TObject, signature(JetType.TString, JetType.TString)),
            "diff" to callable(JetType.TObject, signature(JetType.TObject, JetType.TObject)),
            "delay" to callable(JetType.TNull, signature(JetType.TInt)),
        ),
    )

    fun asValue(): JetValue.JModule = JModule(
        mutableMapOf(
            "now" to builtin(::now),
            "format" to builtin(::format),
            "parse" to builtin(::parse),
            "diff" to builtin(::diff),
            "delay" to JBuiltin(::delay),
        ),
    )

    private fun builtin(handler: (List<JetValue>) -> JetValue): JetValue = JBuiltin { handler(it) }

    private fun now(args: List<JetValue>): JetValue =
        buildTimeObject(LocalDateTime.now(ZoneId.systemDefault()))

    private fun buildTimeObject(dateTime: LocalDateTime): JObject = JObject(
        mutableMapOf(
            "year" to JInt(dateTime.year),
            "month" to JInt(dateTime.monthValue),
            "day" to JInt(dateTime.dayOfMonth),
            "hour" to JInt(dateTime.hour),
            "minute" to JInt(dateTime.minute),
            "second" to JInt(dateTime.second),
            "millisecond" to JInt(dateTime.nano / 1_000_000),
        ),
        isReadOnly = true,
    )

    private fun extractLocalDateTime(timeObj: JObject, fnName: String): LocalDateTime {
        fun intField(name: String): Int =
            (timeObj.getField(name) as? JInt)?.value
                ?: throw RuntimeException("Function '$fnName' expects a time object (missing '$name').")
        val year = intField("year")
        val month = intField("month")
        val day = intField("day")
        val hour = intField("hour")
        val minute = intField("minute")
        val second = intField("second")
        val ms = intField("millisecond")
        return LocalDateTime.of(year, month, day, hour, minute, second, ms * 1_000_000)
    }

    private fun format(args: List<JetValue>): JetValue {
        val timeObj = args[0] as JObject
        val pattern = (args[1] as JString).value
        val dateTime = extractLocalDateTime(timeObj, "time.format")
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern, plugin.localeManager.currentLocale())
        } catch (_: IllegalArgumentException) {
            throw RuntimeException("Function 'time.format' received an invalid pattern '$pattern'.")
        }

        return JString(formatter.format(dateTime.atZone(ZoneId.systemDefault())))
    }

    private fun parse(args: List<JetValue>): JetValue {
        val str = (args[0] as JString).value
        val pattern = (args[1] as JString).value
        val formatter = try {
            DateTimeFormatter.ofPattern(pattern, plugin.localeManager.currentLocale())
        } catch (_: IllegalArgumentException) {
            throw RuntimeException("Function 'time.parse' received an invalid pattern '$pattern'.")
        }
        val dateTime = try {
            LocalDateTime.parse(str, formatter)
        } catch (_: DateTimeParseException) {
            try {
                LocalDate.parse(str, formatter).atStartOfDay()
            } catch (_: DateTimeParseException) {
                throw RuntimeException(
                    "Function 'time.parse' failed to parse '$str' with pattern '$pattern'.",
                )
            }
        }
        return buildTimeObject(dateTime)
    }

    private fun diff(args: List<JetValue>): JetValue {
        val first = args[0] as JObject
        val second = args[1] as JObject
        val xDt = extractLocalDateTime(first, "time.diff")
        val yDt = extractLocalDateTime(second, "time.diff")
        val duration = Duration.between(xDt, yDt).abs()
        return JObject(
            mutableMapOf(
                "days" to JInt(duration.toDays().toInt()),
                "hours" to JInt(duration.toHoursPart()),
                "minutes" to JInt(duration.toMinutesPart()),
                "seconds" to JInt(duration.toSecondsPart()),
                "milliseconds" to JInt(duration.toMillisPart()),
            ),
            isReadOnly = true,
        )
    }

    private suspend fun delay(args: List<JetValue>): JetValue {
        val millis = (args[0] as JInt).value
        if (millis < 0)
            throw RuntimeException("Function 'time.delay' requires a non-negative delay.")

        coroutinesDelay(millis.milliseconds)
        return JNull
    }
}
