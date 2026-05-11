package dev.jetpack.event

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.runtime.JetValue
import dev.jetpack.engine.runtime.JetValue.JBuiltin
import dev.jetpack.engine.runtime.JetValue.JNull
import dev.jetpack.engine.runtime.JetValue.JObject
import dev.jetpack.engine.runtime.ListenerHandle
import dev.jetpack.engine.runtime.nativeapi.NativeBridge
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object EventBridge {

    private class ListenerEntry(
        val scriptFile: String,
        val callback: (JetValue) -> Unit,
        @Volatile var active: Boolean = true,
        @Volatile var destroyed: Boolean = false,
    )

    private class EventBinding(
        val eventClassName: String,
        val eventClass: Class<out Event>,
        val listener: Listener = object : Listener {},
        @Volatile var registered: Boolean = false,
    )

    private val handlers = ConcurrentHashMap<String, CopyOnWriteArrayList<ListenerEntry>>()
    private val bindings = ConcurrentHashMap<String, EventBinding>()

    fun register(
        plugin: JetpackPlugin,
        eventClassName: String,
        scriptFile: String,
        callback: (JetValue) -> Unit,
    ): ListenerHandle {
        val eventClass = resolveEventClass(eventClassName)
        requireNotNull(eventClass) { "Unknown event type '$eventClassName'" }
        val binding = bindings.computeIfAbsent(eventClassName) { EventBinding(eventClassName, eventClass) }
        val entry = ListenerEntry(scriptFile, callback)
        handlers.getOrPut(eventClassName) { CopyOnWriteArrayList() }.add(entry)
        reconcileBinding(plugin, binding)

        return object : ListenerHandle {
            override fun activate(): Boolean {
                if (entry.destroyed || entry.active) return false
                entry.active = true
                reconcileBinding(plugin, binding)
                return true
            }

            override fun deactivate(): Boolean {
                if (entry.destroyed || !entry.active) return false
                entry.active = false
                reconcileBinding(plugin, binding)
                return true
            }

            override fun destroy(): Boolean {
                if (entry.destroyed) return false
                entry.destroyed = true
                entry.active = false
                handlers[eventClassName]?.remove(entry)
                cleanupEmptyBinding(binding)
                return true
            }

            override fun trigger(sender: JetValue): Boolean {
                if (entry.destroyed || !entry.active) return false
                entry.callback(sender)
                return true
            }

            override fun isActive(): Boolean = !entry.destroyed && entry.active
        }
    }

    fun unregisterAll(scriptFile: String) {
        for ((eventClassName, list) in handlers) {
            val removed = list.removeAll { entry ->
                if (entry.scriptFile != scriptFile) return@removeAll false
                entry.destroyed = true
                entry.active = false
                true
            }
            if (removed) {
                bindings[eventClassName]?.let(::cleanupEmptyBinding)
            }
        }
    }

    fun unregisterAllEvents() {
        handlers.values.forEach { list ->
            list.forEach { entry ->
                entry.destroyed = true
                entry.active = false
            }
        }
        handlers.clear()
        bindings.values.forEach { binding ->
            HandlerList.unregisterAll(binding.listener)
            binding.registered = false
        }
        bindings.clear()
    }

    internal fun reflectToJetValue(obj: Any): JObject =
        NativeBridge.wrap(obj, enumAsString = true) as JObject

    internal fun overlayObject(base: JObject, extraFields: Map<String, JetValue>): JObject =
        NativeBridge.overlayObject(base, extraFields)

    private fun resolveEventClass(name: String): Class<out Event>? =
        JetpackEvent.resolve(name)?.eventClass

    private fun reconcileBinding(plugin: JetpackPlugin, binding: EventBinding) {
        synchronized(binding) {
            val activeEntries = handlers[binding.eventClassName].orEmpty().filter { it.active && !it.destroyed }
            when {
                activeEntries.isEmpty() && binding.registered -> {
                    HandlerList.unregisterAll(binding.listener)
                    binding.registered = false
                }
                activeEntries.isNotEmpty() && !binding.registered -> {
                    plugin.server.pluginManager.registerEvent(
                        binding.eventClass,
                        binding.listener,
                        EventPriority.NORMAL,
                        eventCallback@{ _, event ->
                            if (!binding.eventClass.isInstance(event)) return@eventCallback
                            val callbacks = handlers[binding.eventClassName].orEmpty()
                                .filter { it.active && !it.destroyed }
                            if (callbacks.isEmpty()) return@eventCallback
                            val reflected = reflectToJetValue(event)
                            val jetValue = if (event is Cancellable) {
                                overlayObject(reflected, mapOf("cancel" to JBuiltin { _ ->
                                    event.isCancelled = true
                                    JNull
                                }))
                            } else {
                                reflected
                            }
                            callbacks.forEach { it.callback(jetValue) }
                        },
                        plugin,
                    )
                    binding.registered = true
                }
            }
        }
    }

    private fun cleanupEmptyBinding(binding: EventBinding) {
        synchronized(binding) {
            val list = handlers[binding.eventClassName]
            if (list != null && list.isNotEmpty()) {
                if (list.none { it.active && !it.destroyed } && binding.registered) {
                    HandlerList.unregisterAll(binding.listener)
                    binding.registered = false
                }
                return
            }
            handlers.remove(binding.eventClassName)
            if (binding.registered) {
                HandlerList.unregisterAll(binding.listener)
                binding.registered = false
            }
            bindings.remove(binding.eventClassName, binding)
        }
    }
}
