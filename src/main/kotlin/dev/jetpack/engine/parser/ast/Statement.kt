package dev.jetpack.engine.parser.ast

sealed class Statement {
    abstract val line: Int

    data class Metadata(val key: String, val value: String, override val line: Int) : Statement()
    data class Using(
        val relativeDots: Int,
        val path: List<String>,
        val recursive: Boolean,
        val alias: String?,
        override val line: Int,
    ) : Statement()
    data class Manifest(val entries: Map<String, ManifestValue>, override val line: Int) : Statement()
    data class VarDecl(
        val access: AccessModifier,
        val isConst: Boolean,
        val typeName: TypeRef,
        val name: String,
        val initializer: Expression,
        override val line: Int,
    ) : Statement()
    data class ExprStatement(val expression: Expression, override val line: Int) : Statement()
    data class FunctionDecl(
        val access: AccessModifier,
        val name: String,
        val params: List<Param>,
        val returnType: TypeRef?,
        val body: List<Statement>,
        override val line: Int,
    ) : Statement()
    data class IntervalDecl(
        val access: AccessModifier,
        val name: String,
        val intervalMs: Int,
        val body: List<Statement>,
        override val line: Int,
    ) : Statement()
    data class ListenerDecl(
        val access: AccessModifier,
        val eventType: String,
        val name: String,
        val senderParam: String?,
        val body: List<Statement>,
        override val line: Int,
    ) : Statement()
    data class IfStmt(
        val condition: Expression,
        val thenBody: List<Statement>,
        val elseIfClauses: List<Pair<Expression, List<Statement>>>,
        val elseBody: List<Statement>?,
        override val line: Int,
    ) : Statement()
    data class WhileStmt(
        val condition: Expression,
        val body: List<Statement>,
        override val line: Int,
    ) : Statement()
    data class ForEachStmt(
        val itemType: TypeRef?,
        val itemName: String,
        val iterable: Expression,
        val body: List<Statement>,
        override val line: Int,
    ) : Statement()

    data class ReturnStmt(val value: Expression?, override val line: Int) : Statement()
    data class BreakStmt(override val line: Int) : Statement()
    data class ContinueStmt(override val line: Int) : Statement()

    data class CommandDecl(
        val access: AccessModifier,
        val name: String,
        val senderName: String?,
        val params: List<Param>,
        val bodyItems: List<CommandBodyItem>,
        val annotations: CommandAnnotations,
        override val line: Int,
    ) : Statement()
}

data class CommandAnnotations(
    val description: String?,
    val permission: String?,
    val permissionMessage: String?,
    val usage: String?,
    val aliases: List<String>,
) {
    companion object {
        val EMPTY = CommandAnnotations(null, null, null, null, emptyList())
    }
}

sealed class ManifestValue {
    data class Scalar(val value: String) : ManifestValue()
    data class ListValue(val values: List<String>) : ManifestValue()
}

sealed class CommandBodyItem {
    data class Code(val stmt: Statement) : CommandBodyItem()
    data class SubCommand(val decl: Statement.CommandDecl) : CommandBodyItem()
    data class Default(val body: List<Statement>) : CommandBodyItem()
}

data class Param(val typeName: TypeRef?, val name: String, val default: Expression? = null)

data class TypeRef(
    val name: String,
    val typeArgRef: TypeRef? = null,
)

enum class AccessModifier { PUBLIC, PRIVATE, PROTECTED }
