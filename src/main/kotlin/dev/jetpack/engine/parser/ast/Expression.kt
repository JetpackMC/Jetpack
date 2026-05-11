package dev.jetpack.engine.parser.ast

import dev.jetpack.engine.lexer.Token

sealed class Expression {
    abstract val line: Int

    data class IntLiteral(val value: Int, override val line: Int) : Expression()
    data class FloatLiteral(val value: Double, override val line: Int) : Expression()
    data class StringLiteral(val value: String, override val line: Int) : Expression()
    data class BoolLiteral(val value: Boolean, override val line: Int) : Expression()
    data class NullLiteral(override val line: Int) : Expression()

    data class InterpolatedString(val parts: List<InterpolationPart>, override val line: Int) : Expression()
    sealed class InterpolationPart {
        data class Literal(val text: String) : InterpolationPart()
        data class Expr(val expression: Expression) : InterpolationPart()
    }

    data class ListLiteral(val elements: List<Expression>, override val line: Int) : Expression()
    data class ObjectLiteral(val entries: List<Pair<String, Expression>>, override val line: Int) : Expression()

    data class Identifier(val name: String, override val line: Int) : Expression()

    data class BinaryOp(
        val left: Expression,
        val operator: Token,
        val right: Expression,
        override val line: Int,
    ) : Expression()

    data class UnaryOp(
        val operator: Token,
        val operand: Expression,
        val prefix: Boolean,
        override val line: Int,
    ) : Expression()

    data class Ternary(
        val condition: Expression,
        val thenExpr: Expression,
        val elseExpr: Expression,
        override val line: Int,
    ) : Expression()

    data class Range(
        val start: Expression,
        val end: Expression,
        val inclusive: Boolean,
        override val line: Int,
    ) : Expression()

    data class Call(
        val callee: Expression,
        val arguments: List<Expression>,
        override val line: Int,
    ) : Expression()

    data class ThreadCall(
        val call: Call,
        override val line: Int,
    ) : Expression()

    data class ThreadBlock(
        val statement: Statement,
        override val line: Int,
    ) : Expression()

    data class MemberAccess(
        val target: Expression,
        val member: String,
        override val line: Int,
    ) : Expression()

    data class IndexAccess(
        val target: Expression,
        val index: Expression,
        override val line: Int,
    ) : Expression()

    data class Assign(
        val target: Expression,
        val value: Expression,
        override val line: Int,
    ) : Expression()

    data class CompoundAssign(
        val target: Expression,
        val operator: Token,
        val value: Expression,
        override val line: Int,
    ) : Expression()
}
