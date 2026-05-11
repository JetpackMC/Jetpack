package dev.jetpack.script

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

data class ScriptErrorExcerpt(
    val lineNumber: Int?,
    val source: String?,
)

object ScriptErrorFormatter {

    fun buildExcerpt(sourceLines: List<String>, line: Int, prevLine: Int? = null): List<ScriptErrorExcerpt> {
        val currentLine = line.takeIf { it in 1..sourceLines.size }
        val previousLine = prevLine?.takeIf { it in 1..sourceLines.size }

        if (currentLine == null && previousLine == null) {
            return emptyList()
        }

        if (previousLine == null || previousLine == currentLine) {
            return listOfNotNull(currentLine?.let { ScriptErrorExcerpt(it, sourceLines[it - 1]) })
        }

        val start = minOf(previousLine, currentLine ?: previousLine)
        val end = maxOf(previousLine, currentLine ?: previousLine)
        if (end - start <= 1) {
            return (start..end).map { ScriptErrorExcerpt(it, sourceLines[it - 1]) }
        }

        return buildList {
            add(ScriptErrorExcerpt(previousLine, sourceLines[previousLine - 1]))
            add(ScriptErrorExcerpt(null, null))
            currentLine?.let { add(ScriptErrorExcerpt(it, sourceLines[it - 1])) }
        }
    }

    fun formatIssue(
        scriptName: String,
        label: String,
        message: String,
        sourceLines: List<String>,
        line: Int,
        prevLine: Int? = null,
    ): Component {
        val builder = Component.text()
            .append(Component.text("[$scriptName]", NamedTextColor.WHITE))
            .append(Component.newline())
            .append(Component.text(label, NamedTextColor.RED))
            .append(Component.text(":", NamedTextColor.GRAY))
            .append(Component.text(" $message", NamedTextColor.WHITE))

        buildExcerpt(sourceLines, line, prevLine).forEach { excerpt ->
            builder.append(Component.newline())
            if (excerpt.lineNumber == null) {
                builder.append(Component.text("...", NamedTextColor.GRAY))
            } else {
                builder
                    .append(Component.text(" ${excerpt.lineNumber} |", NamedTextColor.AQUA))
                    .append(Component.text(" ${excerpt.source?.trimStart().orEmpty()}", NamedTextColor.WHITE))
            }
        }

        return builder.build()
    }
}
