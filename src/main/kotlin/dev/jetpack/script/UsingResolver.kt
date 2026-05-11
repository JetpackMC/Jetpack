package dev.jetpack.script

import dev.jetpack.engine.parser.ast.Statement

class UsingError(message: String, val line: Int) : Exception(message)

object UsingResolver {
    fun resolvePath(stmt: Statement.Using, currentModulePath: List<String>): List<String> {
        val currentDir = currentModulePath.dropLast(1)
        val upCount = stmt.relativeDots - 1
        val base = when {
            stmt.relativeDots == 0 -> emptyList()
            upCount > currentDir.size -> throw UsingError("Using path cannot go above the root directory", stmt.line)
            else -> currentDir.dropLast(upCount)
        }
        return base + stmt.path
    }

    fun displayPath(stmt: Statement.Using): String {
        val prefix = ".".repeat(stmt.relativeDots)
        val suffix = if (stmt.recursive) ".*" else ""
        return prefix + stmt.path.joinToString(".") + suffix
    }
}
