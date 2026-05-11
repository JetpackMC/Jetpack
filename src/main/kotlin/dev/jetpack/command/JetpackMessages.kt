package dev.jetpack.command

import dev.jetpack.i18n.LocaleManager
import dev.jetpack.script.ScriptLoadReport
import dev.jetpack.script.ScriptMeta
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration

object JetpackMessages {

    fun usage(locale: LocaleManager): Component =
        Component.text(locale.get("command.usage"), NamedTextColor.RED)

    fun noPermission(locale: LocaleManager): Component =
        Component.text(locale.get("command.no_permission"), NamedTextColor.RED)

    fun loadedSummary(locale: LocaleManager, count: Int): Component =
        Component.text(locale.get("plugin.loaded_summary", "count" to count.toString()), NamedTextColor.GREEN)

    fun failedSummary(locale: LocaleManager, count: Int, scriptIds: List<String> = emptyList()): Component {
        val builder = Component.text()
            .append(Component.text("- ", NamedTextColor.RED))
            .append(Component.text(locale.get("plugin.failed_summary", "count" to count.toString()), NamedTextColor.RED))

        if (scriptIds.isNotEmpty()) {
            builder
                .append(Component.text("  ", NamedTextColor.GRAY))
                .append(
                    Component.text(
                        "(${scriptIds.joinToString(", ")})",
                        NamedTextColor.GRAY,
                        TextDecoration.ITALIC,
                    )
                )
        }

        return builder.build()
    }

    fun enabledSummary(locale: LocaleManager, count: Int): Component =
        Component.text(locale.get("plugin.enabled_summary", "count" to count.toString()), NamedTextColor.GREEN)

    fun disabledSummary(locale: LocaleManager, count: Int): Component =
        Component.text(locale.get("plugin.disabled_summary", "count" to count.toString()), NamedTextColor.RED)

    fun singleEnabled(locale: LocaleManager, name: String): Component =
        Component.text(locale.get("script.enabled_message", "name" to name), NamedTextColor.GREEN)

    fun singleDisabled(locale: LocaleManager, name: String): Component =
        Component.text(locale.get("script.disabled_message", "name" to name), NamedTextColor.RED)

    fun invalidScript(locale: LocaleManager, name: String): Component =
        Component.text(locale.get("script.invalid", "name" to name), NamedTextColor.YELLOW)

    fun listHeader(locale: LocaleManager): Component =
        Component.text(locale.get("script.list_header"), NamedTextColor.WHITE)

    fun listEmpty(locale: LocaleManager): Component =
        Component.text(locale.get("script.list_empty"), NamedTextColor.GRAY, TextDecoration.ITALIC)

    fun fileBullet(scriptId: String): Component =
        Component.text()
            .append(Component.text("- ", NamedTextColor.GRAY))
            .append(Component.text(scriptId, NamedTextColor.WHITE))
            .build()

    fun reloadHeader(locale: LocaleManager, key: String): Component =
        Component.text(locale.get(key), NamedTextColor.WHITE)

    fun loadedBullet(locale: LocaleManager, count: Int): Component =
        bullet(locale.get("plugin.loaded_summary", "count" to count.toString()), NamedTextColor.GREEN)

    fun sendLoadReport(
        receiver: (Component) -> Unit,
        locale: LocaleManager,
        report: ScriptLoadReport,
        headerKey: String? = null,
    ) {
        headerKey?.let { receiver(reloadHeader(locale, it)) }
        receiver(loadedBullet(locale, report.loadedCount))
        if (report.failedCount > 0) {
            receiver(failedSummary(locale, report.failedCount, report.failedScriptIds))
        }
    }

    fun listItem(meta: ScriptMeta, enabled: Boolean): Component {
        val nameColor = if (enabled) NamedTextColor.GREEN else NamedTextColor.RED
        val builder = Component.text()
            .append(Component.text("- ", NamedTextColor.GRAY))
            .append(Component.text(meta.scriptId, nameColor))

        meta.name?.takeIf { it.isNotBlank() }?.let { title ->
            builder
                .append(Component.text(" (", NamedTextColor.GRAY))
                .append(Component.text(title, NamedTextColor.WHITE))
                .append(Component.text(")", NamedTextColor.GRAY))
        }

        meta.description?.takeIf { it.isNotBlank() }?.let { description ->
            builder
                .append(Component.text(" - ", NamedTextColor.GRAY))
                .append(Component.text(description, NamedTextColor.WHITE))
        }

        return builder
            .clickEvent(ClickEvent.runCommand("/jetpack info ${meta.scriptId}"))
            .build()
    }

    fun infoHeader(fileName: String, enabled: Boolean): Component =
        Component.text(
            fileName,
            if (enabled) NamedTextColor.GREEN else NamedTextColor.RED,
        )

    fun infoField(label: String, value: String): Component =
        Component.text()
            .append(Component.text(label, NamedTextColor.WHITE))
            .append(Component.text(":", NamedTextColor.GRAY))
            .append(Component.text(" $value", NamedTextColor.WHITE))
            .build()

    fun status(message: String, success: Boolean): Component =
        Component.text(message, if (success) NamedTextColor.GREEN else NamedTextColor.RED)

    private fun bullet(message: String, color: NamedTextColor): Component =
        Component.text()
            .append(Component.text("- ", color))
            .append(Component.text(message, color))
            .build()
}
