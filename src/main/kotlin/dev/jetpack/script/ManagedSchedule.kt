package dev.jetpack.script

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JInt
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.RuntimeError
import dev.jetpack.engine.runtime.ScheduleHandle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.scheduler.BukkitTask
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime

internal class ManagedSchedule(
    private val plugin: JetpackPlugin,
    private val cronText: String,
    private val onRun: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val reportRuntimeError: (RuntimeError) -> Unit,
    private val reportUnknownError: (Exception) -> Unit,
) : ScheduleHandle {
    private val cron = CronExpression.parse(cronText)
    private val zone = ZoneId.systemDefault()
    private val lock = Any()
    private var task: BukkitTask? = null
    private var destroyed = false
    private var active = false
    private var nextRunAt: ZonedDateTime? = null

    init {
        activate()
    }

    override fun destroy(): Boolean = synchronized(lock) {
        if (destroyed) return@synchronized false
        task?.cancel()
        task = null
        nextRunAt = null
        destroyed = true
        active = false
        true
    }

    override fun activate(): Boolean = synchronized(lock) {
        if (destroyed || active) return@synchronized false
        active = true
        scheduleNextLocked()
        true
    }

    override fun deactivate(): Boolean = synchronized(lock) {
        if (destroyed || !active) return@synchronized false
        active = false
        task?.cancel()
        task = null
        nextRunAt = null
        true
    }

    override fun trigger(): Boolean {
        val isAlive = synchronized(lock) { !destroyed }
        if (!isAlive) return false
        runBlocking { runBody() }
        return true
    }

    override fun isActive(): Boolean = synchronized(lock) {
        !destroyed && active
    }

    override fun cron(): String = cronText

    override fun nextRun(): JetValue =
        synchronized(lock) { nextRunAt }?.let(::buildTimeObject) ?: JNull

    private fun runScheduled() {
        synchronized(lock) {
            if (destroyed || !active) return
            task = null
            nextRunAt = null
        }

        scope.launch { runBody() }

        synchronized(lock) {
            if (!destroyed && active) {
                scheduleNextLocked()
            }
        }
    }

    private fun scheduleNextLocked() {
        val next = cron.nextAfter(ZonedDateTime.now(zone))
        nextRunAt = next
        val delayTicks = ticksUntil(next)
        task = plugin.server.scheduler.runTaskLater(
            plugin,
            Runnable { runScheduled() },
            delayTicks,
        )
    }

    private fun ticksUntil(next: ZonedDateTime): Long {
        val millis = Duration.between(ZonedDateTime.now(zone), next).toMillis()
        return ((millis + 49) / 50).coerceAtLeast(1)
    }

    private suspend fun runBody() {
        try {
            onRun()
        } catch (error: CancellationException) {
            throw error
        } catch (error: RuntimeError) {
            reportRuntimeError(error)
        } catch (error: Exception) {
            reportUnknownError(error)
        }
    }

    private fun buildTimeObject(value: ZonedDateTime): JObject = JObject(
        mutableMapOf(
            "year" to JInt(value.year),
            "month" to JInt(value.monthValue),
            "day" to JInt(value.dayOfMonth),
            "hour" to JInt(value.hour),
            "minute" to JInt(value.minute),
            "second" to JInt(value.second),
            "millisecond" to JInt(value.nano / 1_000_000),
        ),
        isReadOnly = true,
    )
}
