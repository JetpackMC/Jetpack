package dev.jetpack.event

import org.bukkit.event.Event
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves catalog entries to the event classes present on the running server.
 *
 * [JetpackEvent] is generated from the newest supported Paper API, so a server on an older supported
 * version is expected to be missing some entries. Those resolve to null and are reported as
 * unsupported rather than treated as a script authoring mistake.
 */
object EventCatalog {

    private val resolved = ConcurrentHashMap<JetpackEvent, Optional<Class<out Event>>>()

    fun eventClass(event: JetpackEvent): Class<out Event>? =
        resolved.computeIfAbsent(event) { Optional.ofNullable(loadEventClass(it.className)) }.orElse(null)

    fun eventClass(name: String): Class<out Event>? =
        JetpackEvent.resolve(name)?.let(::eventClass)

    fun isKnown(name: String): Boolean = JetpackEvent.resolve(name) != null

    fun isSupported(name: String): Boolean = eventClass(name) != null

    private fun loadEventClass(className: String): Class<out Event>? =
        try {
            Class.forName(className, false, EventCatalog::class.java.classLoader)
                .asSubclass(Event::class.java)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        }
}
