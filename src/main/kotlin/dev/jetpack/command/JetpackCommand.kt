package dev.jetpack.command

import dev.jetpack.JetpackPlugin
import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

class JetpackCommand(private val plugin: JetpackPlugin) : CommandExecutor, TabCompleter {

    private enum class SubCommand(val label: String, val permission: String) {
        ENABLE("enable", "jetpack.command.manage"),
        DISABLE("disable", "jetpack.command.manage"),
        LIST("list", "jetpack.command.list"),
        INFO("info", "jetpack.command.info"),
        RELOAD("reload", "jetpack.command.reload"),
    }

    private val l get() = plugin.localeManager
    private val subCommands = SubCommand.entries.associateBy { it.label }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(JetpackMessages.usage(l))
            return true
        }
        val subCommand = subCommands[args[0].lowercase()]
        if (subCommand == null) {
            sender.sendMessage(JetpackMessages.usage(l))
            return true
        }
        if (!canUse(sender, subCommand)) {
            sender.sendMessage(JetpackMessages.noPermission(l))
            return true
        }
        when (subCommand) {
            SubCommand.ENABLE -> handleEnable(sender, args)
            SubCommand.DISABLE -> handleDisable(sender, args)
            SubCommand.LIST -> handleList(sender)
            SubCommand.INFO -> handleInfo(sender, args)
            SubCommand.RELOAD -> handleReload(sender, args)
        }
        return true
    }

    private fun handleEnable(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            val enabled = plugin.scriptRegistry.enableAll()
            sendSummary(sender, JetpackMessages.enabledSummary(l, enabled.size), enabled.map { it.scriptId })
            return
        }
        val path = args.drop(1).joinToString(" ")
        val meta = plugin.scriptRegistry.enable(path)
        if (meta != null) {
            plugin.scriptRegistry.loadAll()
            sender.sendMessage(JetpackMessages.singleEnabled(l, meta.scriptId))
        } else {
            sender.sendMessage(JetpackMessages.invalidScript(l, path))
        }
    }

    private fun handleDisable(sender: CommandSender, args: Array<out String>) {
        if (args.size < 2) {
            val disabled = plugin.scriptRegistry.disableAll()
            sendSummary(sender, JetpackMessages.disabledSummary(l, disabled.size), disabled.map { it.scriptId })
            return
        }
        val path = args.drop(1).joinToString(" ")
        val meta = plugin.scriptRegistry.disable(path)
        if (meta != null) {
            plugin.scriptRegistry.loadAll()
            sender.sendMessage(JetpackMessages.singleDisabled(l, meta.scriptId))
        } else {
            sender.sendMessage(JetpackMessages.invalidScript(l, path))
        }
    }

    private fun handleList(sender: CommandSender) {
        val scripts = plugin.scriptRegistry.getAll()
        sender.sendMessage(JetpackMessages.listHeader(l))
        if (scripts.isEmpty()) {
            sender.sendMessage(JetpackMessages.listEmpty(l))
            return
        }
        scripts.forEach { meta ->
            sender.sendMessage(JetpackMessages.listItem(meta, plugin.scriptRegistry.isEnabled(meta)))
        }
    }

    private fun handleInfo(sender: CommandSender, args: Array<out String>) {
        val path = args.drop(1).joinToString(" ").takeIf { it.isNotBlank() } ?: run {
            sender.sendMessage(JetpackMessages.usage(l))
            return
        }
        val meta = plugin.scriptRegistry.findByPath(path)
        if (meta == null) {
            sender.sendMessage(JetpackMessages.invalidScript(l, path))
            return
        }
        sender.sendMessage(JetpackMessages.infoHeader(meta.scriptId, plugin.scriptRegistry.isEnabled(meta)))
        meta.name?.let { sender.sendMessage(JetpackMessages.infoField(l.get("script.info_title_label"), it)) }
        meta.description?.let { sender.sendMessage(JetpackMessages.infoField(l.get("script.info_description_label"), it)) }
        meta.author?.let { sender.sendMessage(JetpackMessages.infoField(l.get("script.info_author_label"), it)) }
        meta.version?.let { sender.sendMessage(JetpackMessages.infoField(l.get("script.info_version_label"), it)) }
    }

    private fun handleReload(sender: CommandSender, args: Array<out String>) {
        when (args.getOrNull(1)?.lowercase()) {
            "config" -> sendStatusMessage(sender, "plugin.config_reloaded", "plugin.config_reload_failed") {
                plugin.pluginConfig.reload()
                plugin.localeManager.load()
            }
            "scripts" -> sendReloadReport(sender, "plugin.scripts_reloaded", "plugin.scripts_reload_failed") {
                plugin.scriptRegistry.loadAll()
            }
            else -> sendReloadReport(sender, "plugin.reloaded", "plugin.reload_failed") {
                plugin.pluginConfig.reload()
                plugin.localeManager.load()
                plugin.scriptRegistry.loadAll()
            }
        }
    }

    private fun sendSummary(sender: CommandSender, summary: Component, fileNames: List<String>) {
        sender.sendMessage(summary)
        fileNames.forEach { sender.sendMessage(JetpackMessages.fileBullet(it)) }
    }

    private fun sendStatusMessage(
        sender: CommandSender,
        successKey: String,
        failureKey: String,
        action: () -> Unit,
    ) {
        runCatching(action)
            .onSuccess {
                sender.sendMessage(JetpackMessages.status(l.get(successKey), success = true))
            }
            .onFailure {
                sender.sendMessage(JetpackMessages.status(l.get(failureKey), success = false))
            }
    }

    private fun sendReloadReport(
        sender: CommandSender,
        successKey: String,
        failureKey: String,
        action: () -> dev.jetpack.script.ScriptLoadReport,
    ) {
        runCatching(action)
            .onSuccess { report ->
                JetpackMessages.sendLoadReport(
                    receiver = sender::sendMessage,
                    locale = l,
                    report = report,
                    headerKey = successKey,
                )
            }
            .onFailure {
                sender.sendMessage(JetpackMessages.status(l.get(failureKey), success = false))
            }
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>,
    ): List<String> {
        if (args.size == 1) {
            return SubCommand.entries
                .filter { canUse(sender, it) }
                .map { it.label }
                .filter { it.startsWith(args[0], ignoreCase = true) }
        }
        val subCommand = subCommands[args[0].lowercase()] ?: return emptyList()
        if (!canUse(sender, subCommand)) return emptyList()

        if (args.size == 2 && subCommand in setOf(SubCommand.ENABLE, SubCommand.DISABLE, SubCommand.INFO)) {
            return scriptCompletionCandidates(subCommand)
                .filter { it.startsWith(args[1], ignoreCase = true) }
        }
        if (args.size == 2 && subCommand == SubCommand.RELOAD) {
            return listOf("config", "scripts").filter { it.startsWith(args[1], ignoreCase = true) }
        }
        return emptyList()
    }

    private fun scriptCompletionCandidates(subCommand: SubCommand): List<String> =
        plugin.scriptRegistry.getAll()
            .filter { meta ->
                when (subCommand) {
                    SubCommand.ENABLE -> !plugin.scriptRegistry.isEnabled(meta)
                    SubCommand.DISABLE -> plugin.scriptRegistry.isEnabled(meta)
                    SubCommand.INFO -> true
                    else -> false
                }
            }
            .map { it.scriptId }

    private fun canUse(sender: CommandSender, subCommand: SubCommand): Boolean =
        sender.hasPermission("jetpack.command.admin") || sender.hasPermission(subCommand.permission)
}
