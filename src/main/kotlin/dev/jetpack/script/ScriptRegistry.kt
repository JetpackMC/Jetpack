package dev.jetpack.script

import dev.jetpack.JetpackPlugin
import dev.jetpack.engine.lexer.LexerException
import dev.jetpack.engine.lexer.Lexer
import dev.jetpack.engine.parser.ParseException
import dev.jetpack.engine.parser.Parser
import dev.jetpack.engine.parser.ast.ManifestValue
import dev.jetpack.engine.parser.ast.Statement
import java.io.File
import kotlin.text.Charsets.UTF_8

data class ScriptMeta(
    val file: File,
    val scriptId: String,
    val name: String?,
    val description: String?,
    val version: String?,
    val author: String?,
)

data class ScriptLoadReport(
    val loadedScripts: List<ScriptMeta>,
    val failedScriptIds: List<String>,
) {
    val loadedCount: Int
        get() = loadedScripts.size

    val failedCount: Int
        get() = failedScriptIds.size
}

class ScriptRegistry(private val plugin: JetpackPlugin) {

    private data class ParsedScript(
        val meta: ScriptMeta,
        val module: ScriptModule,
    )

    private data class ParseScanResult(
        val parsedScripts: List<ParsedScript>,
        val failedScriptIds: List<String>,
    )

    private val scriptsDir: File
        get() = File(plugin.dataFolder, "scripts").also { it.mkdirs() }

    private val known = mutableListOf<ScriptMeta>()
    private val loaded = mutableListOf<ScriptMeta>()

    fun loadAll(): ScriptLoadReport {
        plugin.scriptRunner.stopAll()
        known.clear()
        loaded.clear()

        val parsed = scanParsedScripts()
        val sorted = parsed.parsedScripts.sortedBy { it.meta.scriptId }
        known.addAll(sorted.map { it.meta })
        val disabled = plugin.pluginConfig.disabledScripts.toSet()
        val enabled = sorted.filter { it.meta.scriptId !in disabled }
        val loadedPaths = plugin.scriptRunner.runAll(enabled.map { it.module })
        val loadedScripts = enabled.map { it.meta }.filter { it.file.canonicalPath in loadedPaths }
        loaded.addAll(loadedScripts)

        val failedScriptIds = linkedSetOf<String>()
        parsed.failedScriptIds.filterTo(failedScriptIds) { it !in disabled }
        enabled
            .map { it.meta }
            .filter { it.file.canonicalPath !in loadedPaths }
            .mapTo(failedScriptIds) { it.scriptId }

        return ScriptLoadReport(
            loadedScripts = loaded.toList(),
            failedScriptIds = failedScriptIds.toList(),
        )
    }

    fun unloadAll() {
        plugin.scriptRunner.stopAll()
        known.clear()
        loaded.clear()
    }

    fun getAll(): List<ScriptMeta> {
        if (known.isEmpty()) {
            known.addAll(
                scanParsedScripts()
                    .parsedScripts
                    .map { it.meta }
                    .sortedBy { it.scriptId }
            )
        }
        return known.toList()
    }

    fun findByPath(path: String): ScriptMeta? =
        getAll().find { it.scriptId == normalizeScriptId(path) }

    fun enableAll(): List<ScriptMeta> {
        saveDisabledList(emptyList())
        return loadAll().loadedScripts
    }

    fun disableAll(): List<ScriptMeta> {
        val all = getAll()
        saveDisabledList(all.map { it.scriptId })
        loadAll()
        return all
    }

    fun enable(path: String): ScriptMeta? {
        val meta = findByPath(path) ?: return null
        val disabled = plugin.pluginConfig.disabledScripts.toMutableList()
        disabled.remove(meta.scriptId)
        saveDisabledList(disabled)
        return meta
    }

    fun disable(path: String): ScriptMeta? {
        val meta = findByPath(path) ?: return null
        val disabled = plugin.pluginConfig.disabledScripts.toMutableList()
        if (meta.scriptId !in disabled) disabled.add(meta.scriptId)
        saveDisabledList(disabled)
        return meta
    }

    fun isEnabled(meta: ScriptMeta): Boolean {
        val disabled = plugin.pluginConfig.disabledScripts
        return meta.scriptId !in disabled
    }

    private fun saveDisabledList(list: List<String>) {
        plugin.config.set("disabled", list)
        plugin.saveConfig()
    }

    private fun collectScriptFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "jet" }.toList()

    private fun scanParsedScripts(): ParseScanResult {
        val parsedScripts = mutableListOf<ParsedScript>()
        val failedScriptIds = mutableListOf<String>()

        for (file in collectScriptFiles(scriptsDir)) {
            parseScript(file)?.let(parsedScripts::add) ?: failedScriptIds.add(scriptIdFor(file))
        }

        return ParseScanResult(
            parsedScripts = parsedScripts,
            failedScriptIds = failedScriptIds,
        )
    }

    private fun parseScript(file: File): ParsedScript? {
        val source = file.readText(UTF_8)
        val sourceLines = source.lines()
        return try {
            val tokens = Lexer(source).tokenize()
            val stmts = Parser(tokens).parseFile()
            val entries = stmts.filterIsInstance<Statement.Manifest>().firstOrNull()?.entries ?: emptyMap()
            val meta = ScriptMeta(
                file = file,
                scriptId = scriptIdFor(file),
                name = manifestString(entries, "name"),
                description = manifestString(entries, "description"),
                version = manifestString(entries, "version"),
                author = manifestString(entries, "author"),
            )
            ParsedScript(
                meta = meta,
                module = plugin.scriptRunner.createParsedModule(meta, stmts, sourceLines),
            )
        } catch (e: LexerException) {
            plugin.sendScriptError(
                ScriptErrorFormatter.formatIssue(
                    scriptName = scriptIdFor(file),
                    label = plugin.localeManager.get("script.error_label"),
                    message = e.message ?: "Lexer error",
                    sourceLines = sourceLines,
                    line = e.line,
                )
            )
            null
        } catch (e: ParseException) {
            plugin.sendScriptError(
                ScriptErrorFormatter.formatIssue(
                    scriptName = scriptIdFor(file),
                    label = plugin.localeManager.get("script.error_label"),
                    message = e.message ?: "Parse error",
                    sourceLines = sourceLines,
                    line = e.line,
                )
            )
            null
        } catch (e: Exception) {
            plugin.sendScriptError(
                ScriptErrorFormatter.formatIssue(
                    scriptName = scriptIdFor(file),
                    label = plugin.localeManager.get("script.error_label"),
                    message = e.message ?: "Failed to parse script",
                    sourceLines = sourceLines,
                    line = 0,
                )
            )
            null
        }
    }

    private fun manifestString(entries: Map<String, ManifestValue>, key: String): String? =
        when (val value = entries[key]) {
            is ManifestValue.Scalar -> value.value
            is ManifestValue.ListValue -> value.values.joinToString(", ")
            null -> null
        }

    private fun scriptIdFor(file: File): String =
        file.canonicalFile.relativeTo(scriptsDir.canonicalFile).invariantSeparatorsPath

    private fun normalizeScriptId(path: String): String =
        path.trim().replace('\\', '/').removePrefix("./")
}
