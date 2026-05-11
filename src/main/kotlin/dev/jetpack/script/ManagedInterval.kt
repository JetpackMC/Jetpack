package dev.jetpack.script

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.runtime.IntervalHandle
import dev.jetpack.engine.runtime.RuntimeError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.bukkit.scheduler.BukkitTask

internal class ManagedInterval(
    private val plugin: JetpackPlugin,
    private val periodTicks: Int,
    private val onRun: suspend () -> Unit,
    private val scope: CoroutineScope,
    private val reportRuntimeError: (RuntimeError) -> Unit,
    private val reportUnknownError: (Exception) -> Unit,
) : IntervalHandle {
    private val lock = Any()
    private var task: BukkitTask? = null
    private var destroyed = false

    init {
        task = createRepeatingTask()
    }

    override fun destroy(): Boolean = synchronized(lock) {
        if (destroyed) return@synchronized false
        task?.cancel()
        task = null
        destroyed = true
        true
    }

    override fun activate(): Boolean = synchronized(lock) {
        if (destroyed || task != null) return@synchronized false
        task = createRepeatingTask()
        true
    }

    override fun deactivate(): Boolean = synchronized(lock) {
        if (destroyed || task == null) return@synchronized false
        task?.cancel()
        task = null
        true
    }

    override fun trigger(): Boolean {
        val isAlive = synchronized(lock) { !destroyed }
        if (!isAlive) return false
        runBlocking { runBody() }
        return true
    }

    override fun isActive(): Boolean = synchronized(lock) {
        !destroyed && task != null
    }

    private fun createRepeatingTask(): BukkitTask =
        plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { scope.launch { runBody() } },
            periodTicks.toLong(),
            periodTicks.toLong(),
        )

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
}
