package dev.jetpack

import dev.jetpack.engine.runtime.builtins.RegistrationHandle
import dev.jetpack.command.JetpackCommand
import dev.jetpack.command.JetpackMessages
import dev.jetpack.config.PluginConfig
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.runtime.builtins.Builtin
import dev.jetpack.engine.runtime.module.BukkitModule
import dev.jetpack.engine.runtime.module.ModuleSpec
import dev.jetpack.i18n.LocaleManager
import dev.jetpack.engine.runtime.module.MathModule
import dev.jetpack.engine.runtime.module.JsonModule
import dev.jetpack.engine.runtime.module.RandomModule
import dev.jetpack.engine.runtime.module.RegexModule
import dev.jetpack.engine.runtime.module.StorageModule
import dev.jetpack.engine.runtime.module.StorageService
import dev.jetpack.engine.runtime.module.TimeModule
import dev.jetpack.engine.runtime.module.HttpModule
import dev.jetpack.script.ScriptRegistry
import dev.jetpack.script.ScriptRunner
import net.kyori.adventure.text.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServerLoadEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class JetpackPlugin : JavaPlugin() {

    lateinit var localeManager: LocaleManager
    lateinit var pluginConfig: PluginConfig
    lateinit var scriptRegistry: ScriptRegistry
    lateinit var scriptRunner: ScriptRunner
    private var extensionBatchDepth = 0
    private var extensionReloadPending = false
    private var initialLoadComplete = false

    override fun onEnable() {
        saveDefaultConfig()
        pluginConfig = PluginConfig(this)
        localeManager = LocaleManager(this)
        scriptRunner = ScriptRunner(this)
        scriptRegistry = ScriptRegistry(this)

        val storageService = StorageService(File(dataFolder.parentFile.parentFile, "storage"))
        batchUpdate {
            registerStandardModules(storageService)
        }

        server.pluginManager.registerEvents(object : Listener {
            @EventHandler
            fun onServerLoad(event: ServerLoadEvent) {
                initialLoadComplete = true
                reloadScripts()
            }

            @EventHandler
            fun onPluginDisable(event: PluginDisableEvent) {
                if (event.plugin == this@JetpackPlugin) return
                batchUpdate {
                    if (scriptRunner.unregisterExtensions(event.plugin)) {
                        markExtensionsDirty()
                    }
                }
            }
        }, this)

        val command = getCommand("jetpack") ?: error("jetpack command not registered in plugin.yml")
        val handler = JetpackCommand(this)
        command.setExecutor(handler)
        command.tabCompleter = handler
    }

    override fun onDisable() {
        scriptRegistry.unloadAll()
    }

    fun batchUpdate(block: () -> Unit) {
        require(server.isPrimaryThread) { "Jetpack extension updates must run on the primary server thread" }
        extensionBatchDepth++
        try {
            block()
        } finally {
            extensionBatchDepth--
            if (extensionBatchDepth == 0) {
                flushExtensionReload()
            }
        }
    }

    fun registerModule(owner: Plugin, builtin: Builtin): RegistrationHandle {
        require(server.isPrimaryThread) { "Jetpack extension updates must run on the primary server thread" }
        val handle = scriptRunner.registerModule(owner, builtin)
        markExtensionsDirty()
        return wrapHandle(handle)
    }

    fun registerModule(
        owner: Plugin,
        name: String,
        value: dev.jetpack.engine.runtime.JetValue.JModule,
        fields: Map<String, JetType>,
        dynamic: Boolean = false,
    ): RegistrationHandle {
        require(server.isPrimaryThread) { "Jetpack extension updates must run on the primary server thread" }
        val handle = scriptRunner.registerModule(owner, name, value, fields, dynamic)
        markExtensionsDirty()
        return wrapHandle(handle)
    }

    private fun wrapHandle(handle: RegistrationHandle): RegistrationHandle =
        object : RegistrationHandle {
            override fun unregister(): Boolean {
                require(server.isPrimaryThread) { "Jetpack extension updates must run on the primary server thread" }
                val removed = handle.unregister()
                if (removed) {
                    markExtensionsDirty()
                }
                return removed
            }
        }

    private fun markExtensionsDirty() {
        extensionReloadPending = true
        if (extensionBatchDepth == 0) {
            flushExtensionReload()
        }
    }

    private fun registerStandardModules(storageService: StorageService) {
        val specs = listOf(
            MathModule().spec(),
            JsonModule().spec(),
            RandomModule().spec(),
            StorageModule(storageService).spec(),
            TimeModule(this).spec(),
            RegexModule().spec(),
            BukkitModule().spec(),
            HttpModule().spec(),
        )
        for (spec in specs) {
            registerModule(this, spec)
        }
    }

    private fun registerModule(owner: Plugin, spec: ModuleSpec): RegistrationHandle =
        registerModule(owner, spec.name, spec.value, spec.fields, spec.dynamic)

    private fun flushExtensionReload() {
        if (!extensionReloadPending || !initialLoadComplete || extensionBatchDepth > 0) return
        reloadScripts()
    }

    private fun reloadScripts() {
        extensionReloadPending = false
        val report = scriptRegistry.loadAll()
        JetpackMessages.sendLoadReport(
            receiver = server.consoleSender::sendMessage,
            locale = localeManager,
            report = report,
        )
    }

    fun sendScriptError(component: Component) {
        server.consoleSender.sendMessage(component)
        if (!pluginConfig.debug) return

        for (player in server.onlinePlayers) {
            if (player.isOp) {
                player.sendMessage(component)
            }
        }
    }
}
