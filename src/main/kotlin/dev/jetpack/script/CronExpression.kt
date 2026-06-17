package dev.jetpack.script

import java.time.ZonedDateTime

internal class CronExpression private constructor(
    private val minutes: CronField,
    private val hours: CronField,
    private val daysOfMonth: CronField,
    private val months: CronField,
    private val daysOfWeek: CronField,
) {
    fun nextAfter(base: ZonedDateTime): ZonedDateTime {
        var candidate = base
            .plusMinutes(1)
            .withSecond(0)
            .withNano(0)
        val limit = candidate.plusYears(8)

        while (!candidate.isAfter(limit)) {
            if (matches(candidate)) return candidate
            candidate = candidate.plusMinutes(1)
        }

        throw IllegalArgumentException("Cron expression has no matching time in the next 8 years")
    }

    private fun matches(time: ZonedDateTime): Boolean {
        if (!months.matches(time.monthValue)) return false
        if (!hours.matches(time.hour)) return false
        if (!minutes.matches(time.minute)) return false

        val dayOfMonthMatches = daysOfMonth.matches(time.dayOfMonth)
        val cronDayOfWeek = time.dayOfWeek.value
        val dayOfWeekMatches = daysOfWeek.matches(cronDayOfWeek)

        return when {
            daysOfMonth.wildcard && daysOfWeek.wildcard -> true
            daysOfMonth.wildcard -> dayOfWeekMatches
            daysOfWeek.wildcard -> dayOfMonthMatches
            else -> dayOfMonthMatches || dayOfWeekMatches
        }
    }

    companion object {
        private val MONTH_NAMES = mapOf(
            "JAN" to 1,
            "FEB" to 2,
            "MAR" to 3,
            "APR" to 4,
            "MAY" to 5,
            "JUN" to 6,
            "JUL" to 7,
            "AUG" to 8,
            "SEP" to 9,
            "OCT" to 10,
            "NOV" to 11,
            "DEC" to 12,
        )
        private val DAY_NAMES = mapOf(
            "SUN" to 7,
            "MON" to 1,
            "TUE" to 2,
            "WED" to 3,
            "THU" to 4,
            "FRI" to 5,
            "SAT" to 6,
        )

        fun parse(raw: String): CronExpression {
            val fields = raw.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            require(fields.size == 5) {
                "Schedule cron must contain 5 fields: minute hour day-of-month month day-of-week"
            }
            return CronExpression(
                minutes = CronField.parse(fields[0], 0, 59),
                hours = CronField.parse(fields[1], 0, 23),
                daysOfMonth = CronField.parse(fields[2], 1, 31),
                months = CronField.parse(fields[3], 1, 12, MONTH_NAMES),
                daysOfWeek = CronField.parse(fields[4], 1, 7, DAY_NAMES, normalizeZeroToMax = true),
            )
        }
    }
}

private data class CronField(
    val values: Set<Int>,
    val wildcard: Boolean,
) {
    fun matches(value: Int): Boolean = value in values

    companion object {
        fun parse(
            raw: String,
            min: Int,
            max: Int,
            names: Map<String, Int> = emptyMap(),
            normalizeZeroToMax: Boolean = false,
        ): CronField {
            require(raw.isNotBlank()) { "Cron field cannot be blank" }
            val values = linkedSetOf<Int>()
            var wildcard = false

            for (part in raw.split(",")) {
                val trimmed = part.trim()
                require(trimmed.isNotEmpty()) { "Cron field contains an empty list item" }
                if (trimmed.startsWith("*")) wildcard = true
                values += parsePart(trimmed, min, max, names, normalizeZeroToMax)
            }

            return CronField(values, wildcard && values.size == (max - min + 1))
        }

        private fun parsePart(
            raw: String,
            min: Int,
            max: Int,
            names: Map<String, Int>,
            normalizeZeroToMax: Boolean,
        ): Set<Int> {
            val split = raw.split("/")
            require(split.size <= 2) { "Cron field '$raw' has too many step separators" }
            val base = split[0]
            val step = split.getOrNull(1)?.toIntOrNull()
                ?: if (split.size == 2) throw IllegalArgumentException("Cron field '$raw' has an invalid step") else 1
            require(step > 0) { "Cron field '$raw' step must be positive" }

            val range = when {
                base == "*" -> min..max
                "-" in base -> {
                    val bounds = base.split("-")
                    require(bounds.size == 2) { "Cron field '$raw' has an invalid range" }
                    val start = parseValue(bounds[0], min, max, names, normalizeZeroToMax)
                    val end = parseValue(bounds[1], min, max, names, normalizeZeroToMax)
                    require(start <= end) { "Cron field '$raw' range start must be <= range end" }
                    start..end
                }
                else -> {
                    val value = parseValue(base, min, max, names, normalizeZeroToMax)
                    value..value
                }
            }

            return range.filterIndexed { index, _ -> index % step == 0 }.toSet()
        }

        private fun parseValue(
            raw: String,
            min: Int,
            max: Int,
            names: Map<String, Int>,
            normalizeZeroToMax: Boolean,
        ): Int {
            val parsed = names[raw.uppercase()] ?: raw.toIntOrNull()
                ?: throw IllegalArgumentException("Cron field value '$raw' is not valid")
            val value = if (normalizeZeroToMax && parsed == 0) max else parsed
            require(value in min..max) {
                "Cron field value '$raw' is outside the allowed range $min..$max"
            }
            return value
        }
    }
}
