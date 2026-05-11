package dev.jetpack.script

import dev.jetpack.engine.parser.ast.AccessModifier
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.Statement
import dev.jetpack.engine.runtime.Scope

enum class ModuleLoadState {
    NOT_LOADED,
    LOADING,
    LOADED,
    FAILED,
}

data class ModuleExportDefinition(
    val name: String,
    val access: AccessModifier,
    val type: JetType,
    val isReadOnly: Boolean,
    val availableAfterDeclaration: Boolean,
) {
    val visibleInModule: Boolean
        get() = access != AccessModifier.PRIVATE
}

data class ResolvedImport(
    val using: Statement.Using,
    val targetModules: List<ScriptModule>,
)

data class ScriptModule(
    val meta: ScriptMeta,
    val stmts: List<Statement>,
    val sourceLines: List<String>,
    val pathSegments: List<String>,
    val exportDefinitions: Map<String, ModuleExportDefinition>,
    var resolvedImports: List<ResolvedImport> = emptyList(),
    var validationState: Boolean? = null,
    var state: ModuleLoadState = ModuleLoadState.NOT_LOADED,
    var scope: Scope? = null,
    val initializedExports: MutableSet<String> = linkedSetOf(),
) {
    val displayPath: String
        get() = pathSegments.joinToString(".")
}

class ModuleTreeNode(
    val children: LinkedHashMap<String, ModuleTreeNode> = linkedMapOf(),
    var module: ScriptModule? = null,
)
