package dev.jetpack.config

import dev.jetpack.JetpackPlugin

class PluginConfig(private val plugin: JetpackPlugin) {

    val locale: String
        get() = plugin.config.getString("locale", "")!!

    val disabledScripts: List<String>
        get() = plugin.config.getStringList("disabled")

    val debug: Boolean
        get() = plugin.config.getBoolean("debug", false)

    fun reload() {
        plugin.reloadConfig()
    }
}
