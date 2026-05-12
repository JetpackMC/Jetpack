package dev.jetpack.engine.resolver

import dev.jetpack.event.JetpackEvent
import dev.jetpack.engine.parser.ast.CatchClause
import dev.jetpack.engine.parser.ast.CommandBodyItem
import dev.jetpack.engine.parser.ast.Expression
import dev.jetpack.engine.parser.ast.Statement

data class ResolverError(
    val message: String,
    val line: Int,
    val prevLine: Int? = null,
)

class NameResolver(private val reservedNames: Set<String> = emptySet()) {

    private val errors = mutableListOf<ResolverError>()
    private val scopes = ArrayDeque<MutableMap<String, Int>>()
    private var manifestLine: Int? = null
    private var insideFunction = false
    private var insideLoop = false
    private var isFileScope = true

    private inline fun withThreadBoundary(block: () -> Unit) {
        val prevFn = insideFunction
        val prevLoop = insideLoop
        val prevFile = isFileScope
        insideFunction = false
        insideLoop = false
        isFileScope = false
        try {
            block()
        } finally {
            insideFunction = prevFn
            insideLoop = prevLoop
            isFileScope = prevFile
        }
    }

    fun resolve(stmts: List<Statement>, predefinedNames: Set<String> = emptySet()): List<ResolverError> {
        errors.clear()
        pushScope()
        for (name in predefinedNames) {
            scopes.last()[name] = 0
        }
        hoistScopeDeclarations(stmts)
        for (stmt in stmts) resolveStmt(stmt)
        popScope()
        return errors.toList()
    }

    private fun resolveStmt(stmt: Statement) {
        when (stmt) {
            is Statement.Metadata, is Statement.Using -> Unit

            is Statement.Manifest -> {
                if (!isFileScope) error("Manifest can only be declared at file scope", stmt.line)
                val prev = manifestLine
                if (prev != null) {
                    errors.add(ResolverError("Manifest is already declared", stmt.line, prev))
                } else {
                    manifestLine = stmt.line
                }
            }

            is Statement.VarDecl -> {
                resolveExpr(stmt.initializer)
                declare(stmt.name, stmt.line)
            }

            is Statement.ExprStatement -> resolveExpr(stmt.expression)

            is Statement.FunctionDecl -> {
                if (!isFileScope) error("Function can only be declared at file scope", stmt.line)
                for (param in stmt.params) {
                    if (param.typeName == null)
                        error("Parameter '${param.name}' must have a type annotation", stmt.line)
                }
                val prevFn = insideFunction
                val prevLoop = insideLoop
                val prevFile = isFileScope
                insideFunction = true
                insideLoop = false
                isFileScope = false
                pushScope()
                for (param in stmt.params) declare(param.name, stmt.line)
                for (bodyStmt in stmt.body) resolveStmt(bodyStmt)
                popScope()
                insideFunction = prevFn
                insideLoop = prevLoop
                isFileScope = prevFile
            }

            is Statement.IntervalDecl -> {
                if (!isFileScope) error("Interval can only be declared at file scope", stmt.line)
                val prevFn = insideFunction
                val prevLoop = insideLoop
                val prevFile = isFileScope
                insideFunction = true
                insideLoop = false
                isFileScope = false
                pushScope()
                for (bodyStmt in stmt.body) resolveStmt(bodyStmt)
                popScope()
                insideFunction = prevFn
                insideLoop = prevLoop
                isFileScope = prevFile
            }

            is Statement.ListenerDecl -> {
                if (!isFileScope) error("Listener can only be declared at file scope", stmt.line)
                if (JetpackEvent.resolve(stmt.eventType) == null)
                    error("Unknown event type '${stmt.eventType}'", stmt.line)
                val prevFn = insideFunction
                val prevLoop = insideLoop
                val prevFile = isFileScope
                insideFunction = true
                insideLoop = false
                isFileScope = false
                pushScope()
                if (stmt.senderParam != null) declare(stmt.senderParam, stmt.line)
                for (bodyStmt in stmt.body) resolveStmt(bodyStmt)
                popScope()
                insideFunction = prevFn
                insideLoop = prevLoop
                isFileScope = prevFile
            }


            is Statement.IfStmt -> {
                resolveExpr(stmt.condition)
                val prevFile = isFileScope
                isFileScope = false
                resolveBlock(stmt.thenBody)
                for ((condition, body) in stmt.elseIfClauses) {
                    resolveExpr(condition)
                    resolveBlock(body)
                }
                stmt.elseBody?.let { resolveBlock(it) }
                isFileScope = prevFile
            }

            is Statement.WhileStmt -> {
                resolveExpr(stmt.condition)
                val prevLoop = insideLoop
                val prevFile = isFileScope
                insideLoop = true
                isFileScope = false
                resolveBlock(stmt.body)
                insideLoop = prevLoop
                isFileScope = prevFile
            }

            is Statement.ForEachStmt -> {
                resolveExpr(stmt.iterable)
                val prevLoop = insideLoop
                val prevFile = isFileScope
                insideLoop = true
                isFileScope = false
                pushScope()
                declare(stmt.itemName, stmt.line)
                for (bodyStmt in stmt.body) resolveStmt(bodyStmt)
                popScope()
                insideLoop = prevLoop
                isFileScope = prevFile
            }

            is Statement.TryStmt -> {
                val prevFile = isFileScope
                isFileScope = false
                resolveBlock(stmt.tryBody)
                for (catchClause in stmt.catches) {
                    resolveCatchClause(catchClause)
                }
                stmt.finallyBody?.let { resolveBlock(it) }
                isFileScope = prevFile
            }

            is Statement.ReturnStmt -> {
                if (!insideFunction) error("Return cannot be used outside of a function", stmt.line)
                stmt.value?.let { resolveExpr(it) }
            }

            is Statement.BreakStmt -> {
                if (!insideLoop) error("Break can only be used inside a loop", stmt.line)
            }

            is Statement.ContinueStmt -> {
                if (!insideLoop) error("Continue can only be used inside a loop", stmt.line)
            }

            is Statement.CommandDecl -> {
                if (!isFileScope) error("Command can only be declared at file scope", stmt.line)
                val prevFn = insideFunction
                val prevLoop = insideLoop
                val prevFile = isFileScope
                insideFunction = true
                insideLoop = false
                isFileScope = false
                resolveCommandDecl(stmt, stmt.senderName)
                insideFunction = prevFn
                insideLoop = prevLoop
                isFileScope = prevFile
            }

            is Statement.ObjectDestructuring -> {
                resolveExpr(stmt.initializer)
                for (binding in stmt.bindings) declare(binding.localName, stmt.line)
            }

            is Statement.ListDestructuring -> {
                resolveExpr(stmt.initializer)
                for (name in stmt.bindings) if (name != null) declare(name, stmt.line)
            }
        }
    }

    private fun resolveCatchClause(catchClause: CatchClause) {
        pushScope()
        catchClause.variableName?.let { declare(it, catchClause.line) }
        for (bodyStmt in catchClause.body) resolveStmt(bodyStmt)
        popScope()
    }

    private fun resolveCommandDecl(stmt: Statement.CommandDecl, inheritedSenderName: String?) {
        for (param in stmt.params) {
            if (param.typeName == null)
                error("Parameter '${param.name}' must have a type annotation", stmt.line)
            else if (param.typeName.name in setOf("list", "object", "var"))
                error("Command parameter '${param.name}' cannot use type '${param.typeName.name}'", stmt.line)
        }
        val effectiveSenderName = stmt.senderName ?: inheritedSenderName
        pushScope()
        if (effectiveSenderName != null) declare(effectiveSenderName, stmt.line)
        for (param in stmt.params) {
            param.default?.let { resolveExpr(it) }
            declare(param.name, stmt.line)
        }
        for (item in stmt.bodyItems) {
            when (item) {
                is CommandBodyItem.Code -> resolveStmt(item.stmt)
                is CommandBodyItem.SubCommand -> resolveCommandDecl(item.decl, effectiveSenderName)
                is CommandBodyItem.Default -> {
                    item.body.forEach(::resolveStmt)
                }
            }
        }
        popScope()
    }

    private fun resolveBlock(stmts: List<Statement>) {
        pushScope()
        for (stmt in stmts) resolveStmt(stmt)
        popScope()
    }

    private fun resolveExpr(expr: Expression) {
        when (expr) {
            is Expression.BinaryOp -> {
                resolveExpr(expr.left)
                resolveExpr(expr.right)
            }
            is Expression.UnaryOp -> resolveExpr(expr.operand)
            is Expression.Ternary -> {
                resolveExpr(expr.condition)
                resolveExpr(expr.thenExpr)
                resolveExpr(expr.elseExpr)
            }
            is Expression.Range -> {
                resolveExpr(expr.start)
                resolveExpr(expr.end)
            }
            is Expression.Call -> {
                resolveExpr(expr.callee)
                expr.arguments.forEach { resolveExpr(it) }
            }
            is Expression.ThreadCall -> {
                resolveExpr(expr.call.callee)
                expr.call.arguments.forEach { resolveExpr(it) }
            }
            is Expression.ThreadBlock -> withThreadBoundary {
                resolveStmt(expr.statement)
            }
            is Expression.MemberAccess -> resolveExpr(expr.target)
            is Expression.IndexAccess -> {
                resolveExpr(expr.target)
                resolveExpr(expr.index)
            }
            is Expression.Assign -> {
                resolveExpr(expr.target)
                resolveExpr(expr.value)
            }
            is Expression.CompoundAssign -> {
                resolveExpr(expr.target)
                resolveExpr(expr.value)
            }
            is Expression.ListLiteral -> expr.elements.forEach { resolveExpr(it) }
            is Expression.ObjectLiteral -> expr.entries.forEach { (_, value) -> resolveExpr(value) }
            is Expression.InterpolatedString -> expr.parts.forEach {
                if (it is Expression.InterpolationPart.Expr) resolveExpr(it.expression)
            }
            is Expression.IntLiteral,
            is Expression.FloatLiteral,
            is Expression.StringLiteral,
            is Expression.BoolLiteral,
            is Expression.NullLiteral -> Unit
            is Expression.Identifier -> {
                val defined = scopes.any { it.containsKey(expr.name) } || expr.name in reservedNames
                if (!defined) error("Undefined identifier '${expr.name}'", expr.line)
            }
        }
    }

    private fun pushScope() = scopes.addLast(mutableMapOf())

    private fun popScope() = scopes.removeLast()

    private fun hoistScopeDeclarations(stmts: List<Statement>) {
        for (stmt in stmts) {
            when (stmt) {
                is Statement.FunctionDecl -> declare(stmt.name, stmt.line)
                is Statement.IntervalDecl -> declare(stmt.name, stmt.line)
                is Statement.ListenerDecl -> declare(stmt.name, stmt.line)
                is Statement.CommandDecl -> declare(stmt.name, stmt.line)
                else -> Unit
            }
        }
    }

    private fun declare(name: String, line: Int) {
        val current = scopes.last()
        val prev = current[name]
        if (prev != null) {
            errors.add(ResolverError("'$name' is already declared", line, prev))
            return
        }
        if (name in reservedNames) {
            errors.add(ResolverError("'$name' is a built-in name and cannot be redeclared", line))
            return
        }
        current[name] = line
    }

    private fun error(message: String, line: Int) {
        errors.add(ResolverError(message, line))
    }
}
