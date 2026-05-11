package dev.jetpack.i18n

import dev.jetpack.JetpackPlugin
import org.bukkit.configuration.file.YamlConfiguration
import java.nio.charset.StandardCharsets
import java.util.Locale

class LocaleManager(private val plugin: JetpackPlugin) {

    private var messages: Map<String, String> = emptyMap()
    private var fallbackMessages: Map<String, String> = emptyMap()
    private var locale: Locale = Locale.forLanguageTag("en-US")

    init {
        load()
    }

    fun load() {
        val localeTag = resolveLocaleTag()
        locale = Locale.forLanguageTag(localeTag)
        fallbackMessages = loadMessages("en-US")
        messages = if (localeTag == "en-US") fallbackMessages else loadMessages(localeTag)
    }

    fun get(key: String, vararg args: Pair<String, String>): String {
        var message = messages[key] ?: fallbackMessages[key] ?: key
        for ((placeholder, value) in args) {
            message = message.replace("{$placeholder}", value)
        }
        return message
    }

    fun currentLocale(): Locale = locale

    private fun resolveLocaleTag(): String {
        val configured = plugin.pluginConfig.locale
        if (configured.isNotBlank() && resourceExists(configured)) return configured
        val system = Locale.getDefault().toLanguageTag()
        if (resourceExists(system)) return system
        return "en-US"
    }

    private fun resourceExists(tag: String): Boolean =
        plugin.getResource("lang/$tag.yml") != null

    private fun loadMessages(tag: String): Map<String, String> {
        val stream = plugin.getResource("lang/$tag.yml")
            ?: plugin.getResource("lang/en-US.yml")
            ?: return emptyMap()
        val config = YamlConfiguration.loadConfiguration(stream.reader(StandardCharsets.UTF_8))
        val result = mutableMapOf<String, String>()
        flattenSection(config.getValues(true), "", result)
        return result
    }

    private fun flattenSection(
        values: Map<String, Any>,
        prefix: String,
        result: MutableMap<String, String>
    ) {
        for ((k, v) in values) {
            val key = if (prefix.isEmpty()) k else "$prefix.$k"
            when (v) {
                is String -> result[key] = v
                is Map<*, *> -> @Suppress("UNCHECKED_CAST")
                flattenSection(v as Map<String, Any>, key, result)
            }
        }
    }
}
