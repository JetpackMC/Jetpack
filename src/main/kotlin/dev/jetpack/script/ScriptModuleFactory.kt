package dev.jetpack.script

import dev.jetpack.engine.parser.ast.AccessModifier
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.Statement
import dev.jetpack.engine.parser.ast.paramsToCallSignature
import dev.jetpack.engine.parser.ast.toJetType
import java.io.File

internal class ScriptModuleFactory(private val scriptsRoot: File) {

    fun createParsedModule(
        meta: ScriptMeta,
        stmts: List<Statement>,
        sourceLines: List<String>,
    ): ScriptModule = ScriptModule(
        meta = meta,
        stmts = stmts,
        sourceLines = sourceLines,
        pathSegments = modulePathSegments(meta.file),
        exportDefinitions = collectExportDefinitions(stmts),
    )

    private fun modulePathSegments(file: File): List<String> {
        val relative = file.canonicalFile.relativeTo(scriptsRoot.canonicalFile).invariantSeparatorsPath
        return relative.removeSuffix(".jet").split('/').filter { it.isNotBlank() }
    }

    private fun collectExportDefinitions(stmts: List<Statement>): Map<String, ModuleExportDefinition> {
        val exports = linkedMapOf<String, ModuleExportDefinition>()
        for (stmt in stmts) {
            when (stmt) {
                is Statement.VarDecl -> {
                    val declaredType = stmt.typeName.toJetType()
                    exports[stmt.name] = ModuleExportDefinition(
                        name = stmt.name,
                        access = stmt.access,
                        type = declaredType,
                        isReadOnly = stmt.isConst || stmt.access == AccessModifier.PROTECTED,
                        availableAfterDeclaration = false,
                    )
                }
                is Statement.FunctionDecl -> {
                    exports[stmt.name] = ModuleExportDefinition(
                        name = stmt.name,
                        access = stmt.access,
                        type = JetType.TCallable(
                            stmt.returnType?.toJetType() ?: JetType.TUnknown,
                            listOf(paramsToCallSignature(stmt.params)),
                        ),
                        isReadOnly = true,
                        availableAfterDeclaration = true,
                    )
                }
                is Statement.IntervalDecl -> {
                    exports[stmt.name] = ModuleExportDefinition(
                        name = stmt.name,
                        access = stmt.access,
                        type = JetType.TInterval,
                        isReadOnly = true,
                        availableAfterDeclaration = true,
                    )
                }
                is Statement.ListenerDecl -> {
                    exports[stmt.name] = ModuleExportDefinition(
                        name = stmt.name,
                        access = stmt.access,
                        type = JetType.TListener,
                        isReadOnly = true,
                        availableAfterDeclaration = true,
                    )
                }
                is Statement.CommandDecl -> {
                    exports[stmt.name] = ModuleExportDefinition(
                        name = stmt.name,
                        access = stmt.access,
                        type = JetType.TCommand,
                        isReadOnly = true,
                        availableAfterDeclaration = true,
                    )
                }
                is Statement.ObjectDestructuring -> {
                    for (binding in stmt.bindings) {
                        exports[binding.localName] = ModuleExportDefinition(
                            name = binding.localName,
                            access = stmt.access,
                            type = JetType.TUnknown,
                            isReadOnly = stmt.isConst || stmt.access == AccessModifier.PROTECTED,
                            availableAfterDeclaration = false,
                        )
                    }
                }
                is Statement.ListDestructuring -> {
                    for (name in stmt.bindings) {
                        if (name != null) {
                            exports[name] = ModuleExportDefinition(
                                name = name,
                                access = stmt.access,
                                type = JetType.TUnknown,
                                isReadOnly = stmt.isConst || stmt.access == AccessModifier.PROTECTED,
                                availableAfterDeclaration = false,
                            )
                        }
                    }
                }
                else -> Unit
            }
        }
        return exports
    }
}
