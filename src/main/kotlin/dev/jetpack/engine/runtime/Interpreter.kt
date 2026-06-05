package dev.jetpack.engine.runtime

import dev.jetpack.engine.lexer.TokenType
import dev.jetpack.engine.parser.ast.CommandAnnotations
import dev.jetpack.engine.parser.ast.CommandBodyItem as AstCommandBodyItem
import dev.jetpack.engine.parser.ast.Expression
import dev.jetpack.engine.parser.ast.JetType
import dev.jetpack.engine.parser.ast.Statement
import dev.jetpack.engine.parser.ast.toJetType
import dev.jetpack.engine.parser.ast.toJetTypeOrNull
import dev.jetpack.engine.runtime.JetValue.*
import dev.jetpack.engine.runtime.builtins.BuiltinRegistry
import dev.jetpack.engine.runtime.nativeapi.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

private class DetachedListenerHandle(
    private val body: suspend (JetValue) -> Unit,
) : ListenerHandle {
    private var destroyed = false
    private var active = true

    override fun activate(): Boolean {
        if (destroyed || active) return false
        active = true
        return true
    }
    override fun deactivate(): Boolean {
        if (destroyed || !active) return false
        active = false
        return true
    }
    override fun destroy(): Boolean {
        if (destroyed) return false
        destroyed = true
        active = false
        return true
    }
    override fun trigger(sender: JetValue): Boolean {
        if (destroyed || !active) return false
        runBlocking { body(sender) }
        return true
    }
    override fun isActive(): Boolean = !destroyed && active
}

class RuntimeError(
    message: String,
    val line: Int,
    val exceptionType: String = "RuntimeException",
) : Exception(message)

private fun formatArity(requiredCount: Int, totalCount: Int): String =
    if (requiredCount == totalCount) "$totalCount arguments" else "$requiredCount..$totalCount arguments"

private class ReturnSignal(val value: JetValue) : Throwable(null, null, false, false)
private class BreakSignal : Throwable(null, null, false, false)
private class ContinueSignal : Throwable(null, null, false, false)
private class ThreadModeContext(
    val insideThread: Boolean,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ThreadModeContext>
}

private class DetachedIntervalHandle(
    private val body: suspend () -> Unit,
) : IntervalHandle {
    private var destroyed = false
    private var active = true

    override fun destroy(): Boolean {
        if (destroyed) return false
        destroyed = true
        active = false
        return true
    }

    override fun activate(): Boolean {
        if (destroyed || active) return false
        active = true
        return true
    }

    override fun deactivate(): Boolean {
        if (destroyed || !active) return false
        active = false
        return true
    }

    override fun trigger(): Boolean {
        if (destroyed) return false
        runBlocking { body() }
        return true
    }

    override fun isActive(): Boolean = !destroyed && active
}

data class CommandParam(
    val name: String,
    val typeName: String?,
    val declaredType: JetType?,
    val default: (suspend () -> JetValue)?,
    val line: Int,
)

sealed class CommandItem {
    class Code(val execute: suspend (Scope) -> Unit) : CommandItem()
    class Sub(val node: CommandNode) : CommandItem()
    class Default(val execute: suspend (Scope) -> Unit) : CommandItem()
}

class CommandNode(
    val name: String,
    val line: Int,
    val params: List<CommandParam>,
    val senderName: String?,
    val createInvocationScope: () -> Scope,
    val bodyItems: List<CommandItem>,
    val annotations: CommandAnnotations = CommandAnnotations.EMPTY,
) {
    suspend fun newInvocationScope(
        sender: JetValue,
        args: List<JetValue>,
        parentScope: Scope? = null,
    ): Scope {
        val invocationScope = parentScope?.child() ?: createInvocationScope()
        if (senderName != null) invocationScope.define(senderName, sender)
        for ((i, param) in params.withIndex()) {
            val value = args.getOrNull(i)
                ?: param.default?.invoke()
                ?: throw RuntimeError("Missing required parameter '${param.name}'", param.line, "ArgumentException")
            val coercedValue = coerceValueToType(value, param.declaredType)
            invocationScope.defineCoerced(
                param.name,
                coercedValue,
                declaredType = param.declaredType,
            )
        }
        return invocationScope
    }

    suspend fun directTrigger(sender: JetValue, args: List<JetValue>) {
        directTriggerPath(emptyList(), sender, args)
    }

    suspend fun directTriggerPath(
        path: List<String>,
        sender: JetValue,
        args: List<JetValue>,
        parentScope: Scope? = null,
    ) {
        val requiredCount = params.count { it.default == null }
        if (args.size < requiredCount) {
            throw RuntimeError(
                "Command '$name' expects ${formatArity(requiredCount, params.size)} but got ${args.size}",
                line,
                "ArgumentException",
            )
        }
        val invocationArgs = args.take(params.size)
        val invocationScope = newInvocationScope(sender, invocationArgs, parentScope)
        val remainingArgs = args.drop(params.size)
        if (path.isNotEmpty()) {
            val next = path.first()
            for ((i, item) in bodyItems.withIndex()) {
                if (item is CommandItem.Sub && item.node.name.equals(next, ignoreCase = true)) {
                    if (!executePreamble(invocationScope, i)) return
                    item.node.directTriggerPath(path.drop(1), sender, remainingArgs, invocationScope)
                    return
                }
            }
            throw RuntimeError("Command '$name' received unexpected subcommand '$next'", line, "ArgumentException")
        }
        if (remainingArgs.isNotEmpty()) {
            throw RuntimeError("Command '$name' received unexpected arguments", line, "ArgumentException")
        }
        executeDefault(invocationScope)
    }

    internal suspend fun executePreamble(invocationScope: Scope, endExclusive: Int): Boolean {
        for (j in 0 until endExclusive) {
            try {
                (bodyItems[j] as? CommandItem.Code)?.execute(invocationScope)
            } catch (_: ReturnSignal) {
                return false
            }
        }
        return true
    }

    internal suspend fun executeDefault(invocationScope: Scope) {
        for ((i, item) in bodyItems.withIndex()) {
            if (item is CommandItem.Default) {
                if (!executePreamble(invocationScope, i)) return
                item.execute(invocationScope)
                return
            }
        }
    }
}

interface ScriptEnvironment {
    fun registerInterval(name: String, ms: Int, body: suspend () -> Unit): IntervalHandle
    fun registerListener(
        eventType: String,
        line: Int,
        priority: String?,
        ignoreCancelled: Boolean,
        body: suspend (JetValue) -> Unit,
    ): ListenerHandle
    fun registerCommand(node: CommandNode): CommandHandle
    suspend fun <T> runThread(body: suspend () -> T): T
}

private class DetachedCommandHandle(private val node: CommandNode) : CommandHandle {
    private var destroyed = false
    private var active = true

    override val hasSender = node.senderName != null

    override fun activate(): Boolean {
        if (destroyed || active) return false
        active = true
        return true
    }

    override fun deactivate(): Boolean {
        if (destroyed || !active) return false
        active = false
        return true
    }

    override fun destroy(): Boolean {
        if (destroyed) return false
        destroyed = true
        active = false
        return true
    }

    override fun trigger(sender: JetValue, args: List<JetValue>): Boolean {
        if (destroyed || !active) return false
        runBlocking { node.directTrigger(sender, args) }
        return true
    }

    override fun triggerPath(path: List<String>, sender: JetValue, args: List<JetValue>): Boolean {
        if (destroyed || !active) return false
        runBlocking { node.directTriggerPath(path, sender, args) }
        return true
    }

    override fun isActive(): Boolean = !destroyed && active
}

class Interpreter(
    private val builtins: BuiltinRegistry = BuiltinRegistry.createDefault(),
    private val env: ScriptEnvironment? = null,
) {
    fun declareTopLevelDeclarations(stmts: List<Statement>, scope: Scope) {
        for (stmt in stmts) {
            when (stmt) {
                is Statement.FunctionDecl -> declareFunction(stmt, scope)
                is Statement.IntervalDecl -> declareInterval(stmt, scope)
                is Statement.ListenerDecl -> declareListener(stmt, scope)
                is Statement.CommandDecl  -> declareCommand(stmt, scope)
                else -> Unit
            }
        }
    }

    suspend fun executeStmt(stmt: Statement, scope: Scope) {
        when (stmt) {
            is Statement.VarDecl -> {
                val declaredType = stmt.typeName.toJetTypeOrNull()
                val value = coerceValueToType(evalExpr(stmt.initializer, scope), declaredType)
                withScopeRuntimeError(stmt.line) {
                    scope.defineCoerced(stmt.name, value, stmt.isConst, declaredType)
                }
            }
            is Statement.ExprStatement -> evalExpr(stmt.expression, scope)
            is Statement.FunctionDecl -> declareFunction(stmt, scope)
            is Statement.IntervalDecl -> declareInterval(stmt, scope)
            is Statement.ListenerDecl -> declareListener(stmt, scope)
            is Statement.IfStmt -> executeIf(stmt, scope)
            is Statement.WhileStmt -> executeWhile(stmt, scope)
            is Statement.ForEachStmt -> executeForEach(stmt, scope)
            is Statement.TryStmt -> executeTry(stmt, scope)
            is Statement.ReturnStmt -> {
                val value = stmt.value?.let { evalExpr(it, scope) } ?: JNull
                throw ReturnSignal(value)
            }
            is Statement.BreakStmt -> throw BreakSignal()
            is Statement.ContinueStmt -> throw ContinueSignal()
            is Statement.Deconstruction -> {
                val list = evalExpr(stmt.initializer, scope)
                if (list !is JList) throw RuntimeError(
                    "Deconstruction requires a list, got '${list.typeName()}'",
                    stmt.line, "TypeException",
                )
                for ((index, binding) in stmt.bindings.withIndex()) {
                    val name = binding.name ?: continue
                    val value = list.elements.getOrNull(index)
                        ?: throw RuntimeError(
                            "Deconstruction index $index is out of range (list has ${list.elements.size} elements)",
                            stmt.line, "IndexException",
                        )
                    val declaredType = binding.typeName?.toJetTypeOrNull()
                    val coerced = coerceValueToType(value, declaredType)
                    withScopeRuntimeError(stmt.line) {
                        if (stmt.isDeclaration) {
                            scope.defineCoerced(name, coerced, stmt.isConst, declaredType)
                        } else {
                            scope.set(name, coerced)
                        }
                    }
                }
            }
            is Statement.Metadata, is Statement.Using, is Statement.Manifest, is Statement.CommandDecl -> Unit
        }
    }

    private suspend fun executeTry(stmt: Statement.TryStmt, scope: Scope) {
        var pending: Throwable? = null

        try {
            executeBlock(stmt.tryBody, scope.child())
        } catch (failure: Throwable) {
            pending = handleTryFailure(failure, stmt, scope)
        }

        val finallyBody = stmt.finallyBody
        if (finallyBody != null) {
            try {
                executeBlock(finallyBody, scope.child())
            } catch (failure: Throwable) {
                pending = failure
            }
        }

        pending?.let { throw it }
    }

    private suspend fun handleTryFailure(
        failure: Throwable,
        stmt: Statement.TryStmt,
        scope: Scope,
    ): Throwable? {
        if (failure !is RuntimeError) return failure

        val catchClause = stmt.catches.firstOrNull { matchesException(failure, it.exceptionType) }
            ?: return failure

        return try {
            val catchScope = scope.child()
            catchClause.variableName?.let { name ->
                catchScope.defineReadOnly(name, buildExceptionObject(failure))
            }
            executeBlock(catchClause.body, catchScope)
            null
        } catch (catchFailure: Throwable) {
            catchFailure
        }
    }

    private fun matchesException(error: RuntimeError, expectedType: String?): Boolean {
        if (expectedType == null) return true
        var current: String? = error.exceptionType
        while (current != null) {
            if (current == expectedType) return true
            current = exceptionSupertype(current)
        }
        return false
    }

    private fun exceptionSupertype(type: String): String? = when (type) {
        "TypeException",
        "NameException",
        "IndexException",
        "KeyException",
        "ArgumentException",
        "ArithmeticException",
        "StateException",
        "PermissionException",
        "NativeException",
        "ModuleException" -> "RuntimeException"
        "RuntimeException" -> "Exception"
        "Exception" -> null
        else -> "RuntimeException"
    }

    private fun buildExceptionObject(error: RuntimeError): JObject =
        JObject(
            fields = linkedMapOf(
                "type" to JString(error.exceptionType),
                "message" to JString(error.message ?: ""),
                "line" to JInt(error.line),
            ),
            isReadOnly = true,
        )

    private fun declareFunction(stmt: Statement.FunctionDecl, scope: Scope) {
        val fn = JFunction(stmt.params, stmt.body, scope, stmt.returnType?.toJetType())
        withScopeRuntimeError(stmt.line) {
            scope.define(stmt.name, fn)
        }
    }

    private fun declareCommand(stmt: Statement.CommandDecl, scope: Scope) {
        val node = buildCommandNode(stmt, scope, stmt.senderName)
        val handle = env?.registerCommand(node) ?: DetachedCommandHandle(node)
        withScopeRuntimeError(stmt.line) {
            scope.define(stmt.name, buildCommandValue(handle, node))
        }
    }

    private fun buildCommandValue(
        handle: CommandHandle,
        node: CommandNode,
        path: List<String> = emptyList(),
    ): JetValue.JCommand {
        val subcommands = linkedMapOf<String, JetValue.JCommand>()
        for (item in node.bodyItems) {
            if (item is CommandItem.Sub) {
                subcommands[item.node.name] = buildCommandValue(handle, item.node, path + item.node.name)
            }
        }
        return JetValue.JCommand(handle = handle, path = path, subcommands = subcommands)
    }

    private fun buildCommandNode(
        stmt: Statement.CommandDecl,
        scope: Scope,
        rootSenderName: String?,
    ): CommandNode {
        val effectiveSenderName = stmt.senderName ?: rootSenderName
        val commandParams = stmt.params.map { param ->
            CommandParam(
                name = param.name,
                typeName = param.typeName?.name,
                declaredType = param.typeName?.toJetTypeOrNull(),
                default = param.default?.let { expr -> { evalExpr(expr, scope) } },
                line = stmt.line,
            )
        }
        val items: List<CommandItem> = stmt.bodyItems.map { item ->
            when (item) {
                is AstCommandBodyItem.Code -> CommandItem.Code { preambleScope ->
                    executeStmt(item.stmt, preambleScope)
                }
                is AstCommandBodyItem.SubCommand ->
                    CommandItem.Sub(buildCommandNode(item.decl, scope, effectiveSenderName))
                is AstCommandBodyItem.Default -> CommandItem.Default { preambleScope ->
                    try { executeBlock(item.body, preambleScope) } catch (_: ReturnSignal) {}
                }
            }
        }
        return CommandNode(
            name = stmt.name,
            line = stmt.line,
            params = commandParams,
            senderName = effectiveSenderName,
            createInvocationScope = { scope.child() },
            bodyItems = items,
            annotations = stmt.annotations,
        )
    }

    private fun declareInterval(stmt: Statement.IntervalDecl, scope: Scope) {
        val handle = env?.registerInterval(stmt.name, stmt.intervalMs) {
            val child = scope.child()
            try { executeBlock(stmt.body, child) } catch (_: ReturnSignal) {}
        } ?: DetachedIntervalHandle {
            val child = scope.child()
            try { executeBlock(stmt.body, child) } catch (_: ReturnSignal) {}
        }
        withScopeRuntimeError(stmt.line) {
            scope.define(stmt.name, JInterval(handle))
        }
    }

    private fun declareListener(stmt: Statement.ListenerDecl, scope: Scope) {
        val body: suspend (JetValue) -> Unit = { senderValue ->
            val child = scope.child()
            if (stmt.senderParam != null) child.define(stmt.senderParam, senderValue)
            try { executeBlock(stmt.body, child) } catch (_: ReturnSignal) {}
        }
        val handle = env?.registerListener(
            stmt.eventType, stmt.line,
            stmt.annotations.priority,
            stmt.annotations.ignoreCancelled,
            body,
        ) ?: DetachedListenerHandle(body)
        withScopeRuntimeError(stmt.line) {
            scope.define(stmt.name, JListener(handle))
        }
    }

    private suspend fun executeIf(stmt: Statement.IfStmt, scope: Scope) {
        if (evalCondition(stmt.condition, scope).isTruthy()) {
            executeBlock(stmt.thenBody, scope.child())
            return
        }
        for ((cond, body) in stmt.elseIfClauses) {
            if (evalCondition(cond, scope).isTruthy()) {
                executeBlock(body, scope.child())
                return
            }
        }
        stmt.elseBody?.let { executeBlock(it, scope.child()) }
    }

    private suspend fun executeWhile(stmt: Statement.WhileStmt, scope: Scope) {
        while (evalCondition(stmt.condition, scope).isTruthy()) {
            try {
                executeBlock(stmt.body, scope.child())
            } catch (_: BreakSignal) { break }
              catch (_: ContinueSignal) { continue }
        }
    }

    private suspend fun executeForEach(stmt: Statement.ForEachStmt, scope: Scope) {
        val iterable = evalExpr(stmt.iterable, scope)
        when (iterable) {
            is JList -> {
                for (element in iterable.elements) {
                    val child = scope.child()
                    child.defineReadOnly(stmt.itemName, element, declaredType = stmt.itemType?.toJetTypeOrNull())
                    try {
                        executeBlock(stmt.body, child)
                    } catch (_: BreakSignal) { break }
                      catch (_: ContinueSignal) { continue }
                }
            }
            is JString -> {
                val value = iterable.value
                for (i in value.indices) {
                    val child = scope.child()
                    child.defineReadOnly(
                        stmt.itemName,
                        JString(value[i].toString()),
                        declaredType = stmt.itemType?.toJetTypeOrNull(),
                    )
                    try {
                        executeBlock(stmt.body, child)
                    } catch (_: BreakSignal) { break }
                      catch (_: ContinueSignal) { continue }
                }
            }
            is JObject -> {
                val keys = iterable.fieldNames().toList()
                for (key in keys) {
                    val value = iterable.getField(key) ?: continue
                    val child = scope.child()
                    child.defineReadOnly(
                        stmt.itemName,
                        JObject(
                            fields = linkedMapOf(
                                "key" to JString(key),
                                "value" to value,
                            ),
                            isReadOnly = true,
                        ),
                        declaredType = stmt.itemType?.toJetTypeOrNull(),
                    )
                    try {
                        executeBlock(stmt.body, child)
                    } catch (_: BreakSignal) { break }
                      catch (_: ContinueSignal) { continue }
                }
            }
            else -> throw RuntimeError(
                "Cannot iterate over value of type '${iterable.typeName()}'",
                stmt.line,
                "TypeException",
            )
        }
    }

    suspend fun executeBlock(stmts: List<Statement>, scope: Scope) {
        for (stmt in stmts) executeStmt(stmt, scope)
    }

    suspend fun evalExpr(expr: Expression, scope: Scope): JetValue = when (expr) {
        is Expression.IntLiteral -> JInt(expr.value)
        is Expression.FloatLiteral -> JFloat(expr.value)
        is Expression.StringLiteral -> JString(expr.value)
        is Expression.BoolLiteral -> JBool(expr.value)
        is Expression.NullLiteral -> JNull
        is Expression.InterpolatedString -> evalInterpolated(expr, scope)
        is Expression.ListLiteral -> {
            val elements = mutableListOf<JetValue>()
            for (e in expr.elements) elements.add(evalExpr(e, scope))
            JList(elements)
        }
        is Expression.ObjectLiteral -> {
            val map = linkedMapOf<String, JetValue>()
            for ((k, v) in expr.entries) map[k] = evalExpr(v, scope)
            JObject(map)
        }
        is Expression.Identifier -> resolveIdentifier(expr, scope)
        is Expression.BinaryOp -> evalBinaryOp(expr, scope)
        is Expression.UnaryOp -> evalUnaryOp(expr, scope)
        is Expression.Ternary -> if (evalCondition(expr.condition, scope).isTruthy())
            evalExpr(expr.thenExpr, scope) else evalExpr(expr.elseExpr, scope)
        is Expression.Range -> evalRange(expr, scope)
        is Expression.Call -> evalCall(expr, scope)
        is Expression.ThreadCall -> evalThreadCall(expr, scope)
        is Expression.ThreadBlock -> evalThreadBlock(expr, scope)
        is Expression.MemberAccess -> evalMemberAccess(expr, scope)
        is Expression.IndexAccess -> evalIndexAccess(expr, scope)
        is Expression.Assign -> evalAssign(expr, scope)
        is Expression.CompoundAssign -> evalCompoundAssign(expr, scope)
    }

    private suspend fun evalThreadCall(expr: Expression.ThreadCall, scope: Scope): JetValue {
        ensureThreadAllowed(expr.line)
        val args = expr.call.arguments.map { argument -> evalExpr(argument, scope) }
        return runThread {
            val callee = expr.call.callee
            if (callee is Expression.MemberAccess) {
                val target = evalExpr(callee.target, scope)
                return@runThread callMemberFunction(target, callee.member, args, expr.line)
            }
            val resolvedCallee = evalExpr(callee, scope)
            val calleeLabel = (callee as? Expression.Identifier)?.let { "Identifier '${it.name}'" }
            callFunction(resolvedCallee, args, expr.line, calleeLabel)
        }
    }

    private suspend fun evalThreadBlock(expr: Expression.ThreadBlock, scope: Scope): JetValue {
        ensureThreadAllowed(expr.line)
        runThread {
            executeThreadOwnedStatement(expr.statement, scope)
            JNull
        }
        return JNull
    }

    private suspend fun callMemberFunction(
        target: JetValue,
        method: String,
        args: List<JetValue>,
        line: Int,
    ): JetValue {
        if (target is JModule) {
            val fieldVal = getModuleField(target, method, line)
            return callFunction(fieldVal, args, line, "Module member '$method'")
        }
        try {
            NativeBridge.callMember(target, method, args)?.let { return it }
        } catch (e: RuntimeException) {
            throw RuntimeError(e.message ?: "Native method call failed", line, "NativeException")
        }
        builtins.resolveMethod(target, method)?.let { fn ->
            try {
                return fn(args)
            } catch (e: RuntimeException) {
                throw RuntimeError(e.message ?: "Builtin method call failed", line, "RuntimeException")
            }
        }
        if (target is JObject) {
            val fieldVal = getObjectField(target, method, line, "member")
            return callFunction(fieldVal, args, line, "Object member '$method'")
        }
        throw RuntimeError(
            "Runtime value of type '${target.typeName()}' does not support method '$method'",
            line,
            "TypeException",
        )
    }

    private suspend fun executeThreadOwnedStatement(stmt: Statement, scope: Scope) {
        try {
            when (stmt) {
                is Statement.IfStmt -> executeIf(stmt, scope)
                is Statement.WhileStmt -> executeWhile(stmt, scope)
                is Statement.ForEachStmt -> executeForEach(stmt, scope)
                else -> throw RuntimeError(
                    "'thread' only supports block statements and function/method calls",
                    stmt.line,
                    "TypeException",
                )
            }
        } catch (_: ReturnSignal) {
            throw RuntimeError("Return cannot escape a thread block", stmt.line, "StateException")
        } catch (_: BreakSignal) {
            throw RuntimeError("Break cannot escape a thread block", stmt.line, "StateException")
        } catch (_: ContinueSignal) {
            throw RuntimeError("Continue cannot escape a thread block", stmt.line, "StateException")
        }
    }

    private suspend fun ensureThreadAllowed(line: Int) {
        if (coroutineContext[ThreadModeContext]?.insideThread == true) {
            throw RuntimeError("Nested 'thread' execution is not allowed", line, "StateException")
        }
    }

    private suspend fun <T> runThread(action: suspend () -> T): T =
        env?.runThread {
            withContext(ThreadModeContext(true)) { action() }
        } ?: withContext(Dispatchers.Default + ThreadModeContext(true)) {
            action()
        }

    private suspend fun evalInterpolated(expr: Expression.InterpolatedString, scope: Scope): JetValue {
        val sb = StringBuilder()
        for (part in expr.parts) {
            sb.append(when (part) {
                is Expression.InterpolationPart.Literal -> part.text
                is Expression.InterpolationPart.Expr -> evalExpr(part.expression, scope).toString()
            })
        }
        return JString(sb.toString())
    }

    private suspend fun evalRange(expr: Expression.Range, scope: Scope): JetValue {
        val start = evalExpr(expr.start, scope) as? JInt
            ?: throw RuntimeError("Range start must evaluate to int", expr.line, "TypeException")
        val end = evalExpr(expr.end, scope) as? JInt
            ?: throw RuntimeError("Range end must evaluate to int", expr.line, "TypeException")
        return buildRangeList(start.value, end.value, expr.inclusive, expr.line)
    }

    private fun buildRangeList(start: Int, end: Int, inclusive: Boolean, line: Int): JList {
        val rangeSize = try {
            if (start <= end) {
                val span = Math.subtractExact(end, start)
                if (inclusive) Math.addExact(span, 1) else span
            } else {
                val span = Math.subtractExact(start, end)
                if (inclusive) Math.addExact(span, 1) else span
            }
        } catch (_: ArithmeticException) {
            throw RuntimeError("Range size exceeds the maximum limit of ${Int.MAX_VALUE}", line, "ArithmeticException")
        }

        val elements = ArrayList<JetValue>(rangeSize)
        if (start <= end) {
            val endVal = if (inclusive) end else end - 1
            for (i in start..endVal) elements.add(JInt(i))
        } else {
            val endVal = if (inclusive) end else end + 1
            for (i in start downTo endVal) elements.add(JInt(i))
        }
        return JList(elements, declaredElementType = JetType.TInt)
    }

    private fun resolveIdentifier(expr: Expression.Identifier, scope: Scope): JetValue {
        builtins.resolveGlobal(expr.name)?.let { return JBuiltin(it) }
        return withScopeRuntimeError(expr.line) {
            scope.get(expr.name)
        }
    }

    private suspend fun evalBinaryOp(expr: Expression.BinaryOp, scope: Scope): JetValue {
        val op = expr.operator.type

        if (op == TokenType.AMP_AMP) {
            val left = evalCondition(expr.left, scope, "Operator '&&' cannot be applied to null")
            if (!left.isTruthy()) return JBool(false)
            return JBool(evalCondition(expr.right, scope, "Operator '&&' cannot be applied to null").isTruthy())
        }
        if (op == TokenType.PIPE_PIPE) {
            val left = evalCondition(expr.left, scope, "Operator '||' cannot be applied to null")
            if (left.isTruthy()) return JBool(true)
            return JBool(evalCondition(expr.right, scope, "Operator '||' cannot be applied to null").isTruthy())
        }

        val left = evalExpr(expr.left, scope)
        val right = evalExpr(expr.right, scope)
        return when {
            op == TokenType.EQ_EQ -> JBool(jetEquals(left, right))
            op == TokenType.BANG_EQ -> JBool(!jetEquals(left, right))
            op == TokenType.PLUS && left is JString && right is JString ->
                JString(left.value + right.value)
            op == TokenType.PLUS && left is JList && right is JList -> {
                val anchor = firstNonNullListElement(left)
                val expectedElement = describeListElementExpectation(left, anchor) ?: "unknown"
                for (elem in right.elements) {
                    if (!listAcceptsValue(left, elem, anchor)) {
                        throw RuntimeError(
                            "Cannot concatenate lists with incompatible element types: '$expectedElement' and '${elem.typeName()}'",
                            expr.line,
                            "TypeException",
                        )
                    }
                }
                val elements = ArrayList<JetValue>(left.elements.size + right.elements.size)
                elements.addAll(left.elements)
                for (elem in right.elements) {
                    elements.add(coerceValueForList(left, elem, anchor))
                }
                JList(
                    elements = elements,
                    declaredElementType = left.declaredElementType ?: right.declaredElementType,
                )
            }
            op == TokenType.STAR && left is JString && right is JInt -> {
                val count = right.value
                if (count < 0) throw RuntimeError("String repetition count cannot be negative", expr.line, "ArgumentException")
                JString(left.value.repeat(count))
            }
            op == TokenType.STAR && left is JInt && right is JString -> {
                val count = left.value
                if (count < 0) throw RuntimeError("String repetition count cannot be negative", expr.line, "ArgumentException")
                JString(right.value.repeat(count))
            }
            left.isNumeric() && right.isNumeric() -> evalNumericOp(left, right, op, expr.line)
            op == TokenType.KW_IN -> when (right) {
                is JList -> JBool(right.elements.any { jetEquals(left, it) })
                is JObject -> {
                    val key = (left as? JString)
                        ?: throw RuntimeError(
                            "'in' on object requires a string key, got '${left.typeName()}'",
                            expr.line,
                            "TypeException",
                        )
                    JBool(right.hasField(key.value))
                }
                is JString -> {
                    val sub = (left as? JString)
                        ?: throw RuntimeError(
                            "'in' on string requires a string value, got '${left.typeName()}'",
                            expr.line,
                            "TypeException",
                        )
                    JBool(right.value.contains(sub.value))
                }
                else -> throw RuntimeError(
                    "Operator 'in' cannot be applied to type '${right.typeName()}'",
                    expr.line,
                    "TypeException",
                )
            }
            else -> throw RuntimeError(
                "Operator '${expr.operator.value}' cannot be applied to types '${left.typeName()}' and '${right.typeName()}'",
                expr.line,
                "TypeException",
            )
        }
    }

    private fun evalNumericOp(left: JetValue, right: JetValue, op: TokenType, line: Int): JetValue {
        val leftInt = left as? JInt
        val rightInt = right as? JInt
        val isInt = leftInt != null && rightInt != null
        if (isInt) {
            val li = leftInt.value
            val ri = rightInt.value
            return when (op) {
                TokenType.PLUS -> JInt(li + ri)
                TokenType.MINUS -> JInt(li - ri)
                TokenType.STAR -> JInt(li * ri)
                TokenType.SLASH -> {
                    if (ri == 0) throw RuntimeError("Division by zero", line, "ArithmeticException")
                    JInt(li / ri)
                }
                TokenType.PERCENT -> {
                    if (ri == 0) throw RuntimeError("Division by zero", line, "ArithmeticException")
                    JInt(li % ri)
                }
                TokenType.STAR_STAR -> {
                    if (ri >= 0) JInt(intPow(li, ri, line))
                    else JFloat(Math.pow(li.toDouble(), ri.toDouble()))
                }
                TokenType.LT -> JBool(li < ri)
                TokenType.LT_EQ -> JBool(li <= ri)
                TokenType.GT -> JBool(li > ri)
                TokenType.GT_EQ -> JBool(li >= ri)
                else -> throw RuntimeError("Unknown numeric operator '${op}'", line, "TypeException")
            }
        }
        val l = left.toNumericDouble()
        val r = right.toNumericDouble()
        return when (op) {
            TokenType.PLUS -> JFloat(l + r)
            TokenType.MINUS -> JFloat(l - r)
            TokenType.STAR -> JFloat(l * r)
            TokenType.SLASH -> {
                if (r == 0.0) throw RuntimeError("Division by zero", line, "ArithmeticException")
                JFloat(l / r)
            }
            TokenType.PERCENT -> {
                if (r == 0.0) throw RuntimeError("Division by zero", line, "ArithmeticException")
                JFloat(l % r)
            }
            TokenType.STAR_STAR -> JFloat(Math.pow(l, r))
            TokenType.LT -> JBool(l < r)
            TokenType.LT_EQ -> JBool(l <= r)
            TokenType.GT -> JBool(l > r)
            TokenType.GT_EQ -> JBool(l >= r)
            else -> throw RuntimeError("Unknown numeric operator '${op}'", line, "TypeException")
        }
    }

    private suspend fun evalUnaryOp(expr: Expression.UnaryOp, scope: Scope): JetValue {
        val op = expr.operator.type
        if (op == TokenType.PLUS_PLUS || op == TokenType.MINUS_MINUS) {
            if (expr.operand is Expression.Identifier) {
                val current = withScopeRuntimeError(expr.operand.line) {
                    scope.get(expr.operand.name)
                }
                val newVal = when (current) {
                    is JInt -> if (op == TokenType.PLUS_PLUS) JInt(current.value + 1) else JInt(current.value - 1)
                    is JFloat -> if (op == TokenType.PLUS_PLUS) JFloat(current.value + 1) else JFloat(current.value - 1)
                    else -> throw RuntimeError("Operator '${expr.operator.value}' requires a numeric value", expr.line, "TypeException")
                }
                val returnVal = if (expr.prefix) newVal else current
                withScopeRuntimeError(expr.operand.line) {
                    scope.set(expr.operand.name, newVal)
                }
                return returnVal
            }
            val current = evalExpr(expr.operand, scope)
            val newVal = when {
                current is JInt -> if (op == TokenType.PLUS_PLUS) JInt(current.value + 1) else JInt(current.value - 1)
                current is JFloat -> if (op == TokenType.PLUS_PLUS) JFloat(current.value + 1) else JFloat(current.value - 1)
                else -> throw RuntimeError("Operator '${expr.operator.value}' requires a numeric value", expr.line, "TypeException")
            }
            val returnVal = if (expr.prefix) newVal else current
            assignTo(expr.operand, newVal, scope)
            return returnVal
        }
        val operand = evalExpr(expr.operand, scope)
        return when (op) {
            TokenType.MINUS -> when (operand) {
                is JInt -> JInt(-operand.value)
                is JFloat -> JFloat(-operand.value)
                else -> throw RuntimeError("Operator '-' requires a numeric value", expr.line, "TypeException")
            }
            TokenType.BANG -> {
                if (operand is JNull) throw RuntimeError("Operator '!' cannot be applied to null", expr.line, "TypeException")
                JBool(!operand.isTruthy())
            }
            else -> throw RuntimeError("Unknown unary operator '${expr.operator.value}'", expr.line, "TypeException")
        }
    }

    private suspend fun evalCall(expr: Expression.Call, scope: Scope): JetValue {
        if (expr.callee is Expression.MemberAccess) {
            val target = evalExpr(expr.callee.target, scope)
            val args = mutableListOf<JetValue>()
            for (arg in expr.arguments) args.add(evalExpr(arg, scope))
            return callMemberFunction(target, expr.callee.member, args, expr.line)
        }
        val callee = evalExpr(expr.callee, scope)
        val args = mutableListOf<JetValue>()
        for (arg in expr.arguments) args.add(evalExpr(arg, scope))
        val calleeLabel = (expr.callee as? Expression.Identifier)?.let { "Identifier '${it.name}'" }
        return callFunction(callee, args, expr.line, calleeLabel)
    }

    private suspend fun callFunction(
        callee: JetValue,
        args: List<JetValue>,
        line: Int,
        calleeLabel: String? = null,
    ): JetValue {
        return when (callee) {
            is JObject -> {
                val result = try {
                    NativeBridge.call(callee, args)
                } catch (e: RuntimeException) {
                    throw RuntimeError(e.message ?: "Native call failed", line, "NativeException")
                }
                result ?: throw RuntimeError(
                        calleeLabel?.let { "$it is not callable" }
                            ?: "Value of type '${callee.typeName()}' is not callable",
                        line,
                        "TypeException",
                    )
            }
            is JBuiltin -> {
                try {
                    callee.fn(args)
                } catch (e: RuntimeException) {
                    throw RuntimeError(e.message ?: "Builtin call failed", line, "RuntimeException")
                }
            }
            is JFunction -> {
                val requiredCount = callee.requiredCount
                if (args.size < requiredCount || args.size > callee.params.size) {
                    throw RuntimeError(
                        "Function expects ${formatArity(requiredCount, callee.params.size)} but got ${args.size}",
                        line,
                        "ArgumentException",
                    )
                }
                val fnScope = callee.closure.child()
                for ((i, param) in callee.params.withIndex()) {
                    val argVal = args.getOrNull(i)
                        ?: param.default?.let { evalExpr(it, callee.closure) }
                        ?: throw RuntimeError("Missing required parameter '${param.name}'", line, "ArgumentException")
                    val expectedType = callee.resolvedParamTypes[i]
                    val coercedArg = coerceValueToType(argVal, expectedType)
                    val actualType = runtimeTypeOf(coercedArg)
                    if (expectedType != JetType.TUnknown && !expectedType.accepts(actualType)) {
                        throw RuntimeError(
                            "Parameter '${param.name}' expected '$expectedType' but got '$actualType'",
                            line,
                            "ArgumentException",
                        )
                    }
                    fnScope.defineCoerced(param.name, coercedArg, declaredType = expectedType)
                }
                val result = try {
                    executeBlock(callee.body, fnScope)
                    JNull
                } catch (ret: ReturnSignal) {
                    ret.value
                }
                coerceReturn(result, callee.returnType)
            }
            else -> throw RuntimeError(
                calleeLabel?.let { "$it is not callable" } ?: "Value of type '${callee.typeName()}' is not callable",
                line,
                "TypeException",
            )
        }
    }

    private suspend fun evalMemberAccess(expr: Expression.MemberAccess, scope: Scope): JetValue {
        val target = evalExpr(expr.target, scope)
        return when (target) {
            is JModule -> getModuleField(target, expr.member, expr.line)
            is JObject -> getObjectField(target, expr.member, expr.line, "member")
            is JCommand -> target.subcommands[expr.member]
                ?: throw RuntimeError("Command subcommand '${expr.member}' does not exist", expr.line, "KeyException")
            else -> throw RuntimeError(
                "Runtime value of type '${target.typeName()}' does not support member access '${expr.member}'",
                expr.line,
                "TypeException",
            )
        }
    }

    private suspend fun evalIndexAccess(expr: Expression.IndexAccess, scope: Scope): JetValue {
        val target = evalExpr(expr.target, scope)
        val index = evalExpr(expr.index, scope)
        return when (target) {
            is JList -> {
                if (index !is JInt) throw RuntimeError("List index must be an integer", expr.line, "TypeException")
                val i = index.value.toInt()
                if (i < 0 || i >= target.elements.size) throw RuntimeError("List index is out of range", expr.line, "IndexException")
                target.elements[i]
            }
            is JString -> {
                if (index !is JInt) throw RuntimeError("String index must be an integer", expr.line, "TypeException")
                val i = index.value.toInt()
                if (i < 0 || i >= target.value.length) throw RuntimeError("String index is out of range", expr.line, "IndexException")
                JString(target.value[i].toString())
            }
            is JObject -> {
                val key = when (index) {
                    is JString -> index.value
                    else -> throw RuntimeError("Object key must be a string", expr.line, "TypeException")
                }
                getObjectField(target, key, expr.line, "key")
            }
            else -> throw RuntimeError(
                "Runtime value of type '${target.typeName()}' does not support index access",
                expr.line,
                "TypeException",
            )
        }
    }

    private suspend fun evalAssign(expr: Expression.Assign, scope: Scope): JetValue {
        val value = evalExpr(expr.value, scope)
        return if (expr.target is Expression.Identifier) {
            withScopeRuntimeError(expr.target.line) {
                scope.set(expr.target.name, value)
            }
        } else {
            assignTo(expr.target, value, scope)
            value
        }
    }

    private suspend fun evalCompoundAssign(expr: Expression.CompoundAssign, scope: Scope): JetValue {
        val current = evalExpr(expr.target, scope)
        val right = evalExpr(expr.value, scope)
        val op = expr.operator.type
        val combined: JetValue = when {
            op == TokenType.PLUS_ASSIGN && current is JString && right is JString ->
                JString(current.value + right.value)
            op == TokenType.STAR_ASSIGN && current is JString && right is JInt -> {
                val count = right.value
                if (count < 0) throw RuntimeError("String repetition count cannot be negative", expr.line, "ArgumentException")
                JString(current.value.repeat(count))
            }
            op == TokenType.PLUS_ASSIGN && current is JList && right is JList -> {
                val anchor = firstNonNullListElement(current)
                val expectedElement = describeListElementExpectation(current, anchor) ?: "unknown"
                for (elem in right.elements) {
                    if (!listAcceptsValue(current, elem, anchor)) {
                        throw RuntimeError(
                            "Cannot concatenate lists with incompatible element types: '$expectedElement' and '${elem.typeName()}'",
                            expr.line,
                            "TypeException",
                        )
                    }
                }
                val elements = ArrayList<JetValue>(current.elements.size + right.elements.size)
                elements.addAll(current.elements)
                for (elem in right.elements) {
                    elements.add(coerceValueForList(current, elem, anchor))
                }
                JList(
                    elements = elements,
                    declaredElementType = current.declaredElementType ?: right.declaredElementType,
                )
            }
            else -> {
                val binaryOp = when (op) {
                    TokenType.PLUS_ASSIGN     -> TokenType.PLUS
                    TokenType.MINUS_ASSIGN    -> TokenType.MINUS
                    TokenType.STAR_ASSIGN     -> TokenType.STAR
                    TokenType.SLASH_ASSIGN    -> TokenType.SLASH
                    TokenType.PERCENT_ASSIGN  -> TokenType.PERCENT
                    TokenType.STAR_STAR_ASSIGN -> TokenType.STAR_STAR
                    else -> throw RuntimeError("Unknown compound assignment operator", expr.line, "TypeException")
                }
                evalNumericOp(current, right, binaryOp, expr.line)
            }
        }
        assignTo(expr.target, combined, scope)
        return combined
    }

    private suspend fun assignTo(target: Expression, value: JetValue, scope: Scope) {
        when (target) {
            is Expression.Identifier -> withScopeRuntimeError(target.line) {
                scope.set(target.name, value)
            }
            is Expression.MemberAccess -> {
                val receiver = evalExpr(target.target, scope)
                try {
                    when (receiver) {
                        is JObject -> receiver.setField(target.member, value)
                        is JModule -> receiver.setField(target.member, value)
                        else -> throw RuntimeError("Cannot assign member on non-object", target.line, "TypeException")
                    }
                } catch (e: RuntimeError) {
                    throw e
                } catch (e: RuntimeException) {
                    throw RuntimeError(e.message ?: "Cannot assign member", target.line, "KeyException")
                }
            }
            is Expression.IndexAccess -> {
                val container = evalExpr(target.target, scope)
                val index = evalExpr(target.index, scope)
                when (container) {
                    is JList -> {
                        if (container.isReadOnly) {
                            throw RuntimeError("Cannot modify a read-only list", target.line, "PermissionException")
                        }
                        if (index !is JInt) throw RuntimeError("List index must be an integer", target.line, "TypeException")
                        val i = index.value.toInt()
                        if (i < 0 || i >= container.elements.size)
                            throw RuntimeError("List index is out of range", target.line, "IndexException")
                        val anchor = firstNonNullListElement(container)
                        val expectedElement = describeListElementExpectation(container, anchor) ?: "unknown"
                        if (!listAcceptsValue(container, value, anchor)) {
                            throw RuntimeError(
                                "Cannot assign '${value.typeName()}' to list of '$expectedElement'",
                                target.line,
                                "TypeException",
                            )
                        }
                        container.elements[i] = coerceValueForList(container, value, anchor)
                        return
                    }
                    is JObject -> {
                        val key = when (index) {
                            is JString -> index.value
                            else -> throw RuntimeError("Object key must be a string", target.line, "TypeException")
                        }
                        try {
                            container.setField(key, value)
                        } catch (e: RuntimeError) {
                            throw e
                        } catch (e: RuntimeException) {
                            throw RuntimeError(e.message ?: "Cannot assign object member", target.line, "KeyException")
                        }
                    }
                    else -> throw RuntimeError("Cannot index-assign on type '${container.typeName()}'", target.line, "TypeException")
                }
            }
            else -> throw RuntimeError("Invalid assignment target", target.line, "TypeException")
        }
    }

    private inline fun <T> withScopeRuntimeError(line: Int, action: () -> T): T {
        try {
            return action()
        } catch (e: ScopeException) {
            throw RuntimeError(e.message ?: "Scope error", line, scopeExceptionType(e))
        }
    }

    private fun scopeExceptionType(error: ScopeException): String {
        val message = error.message.orEmpty()
        return when {
            message.startsWith("Undefined identifier") -> "NameException"
            message.contains("cannot be modified") || message.contains("read-only") -> "PermissionException"
            else -> "RuntimeException"
        }
    }

    private suspend fun evalCondition(
        expr: Expression,
        scope: Scope,
        nullMessage: String = "Condition cannot be null",
    ): JetValue {
        val value = evalExpr(expr, scope)
        if (value is JNull) throw RuntimeError(nullMessage, expr.line, "TypeException")
        return value
    }

    private fun getObjectField(
        target: JObject,
        member: String,
        line: Int,
        referenceKind: String,
    ): JetValue {
        try {
            return target.getField(member) ?: throw RuntimeError("Object $referenceKind '$member' does not exist", line, "KeyException")
        } catch (e: RuntimeError) {
            throw e
        } catch (e: RuntimeException) {
            throw RuntimeError(e.message ?: "Object access failed", line, "KeyException")
        }
    }

    private fun getModuleField(target: JModule, member: String, line: Int): JetValue {
        try {
            return target.getField(member) ?: throw RuntimeError("Module member '$member' does not exist", line, "ModuleException")
        } catch (e: RuntimeError) {
            throw e
        } catch (e: RuntimeException) {
            throw RuntimeError(e.message ?: "Module access failed", line, "ModuleException")
        }
    }

    private fun coerceReturn(value: JetValue, type: JetType?): JetValue =
        coerceValueToType(value, type)

    private fun intPow(base: Int, exp: Int, line: Int): Int {
        var result = 1
        var factor = base
        var exponent = exp
        while (exponent > 0) {
            if ((exponent and 1) != 0) {
                try {
                    result = Math.multiplyExact(result, factor)
                } catch (_: ArithmeticException) {
                    throw RuntimeError("Integer overflow in '**' operation", line, "ArithmeticException")
                }
            }
            exponent = exponent ushr 1
            if (exponent > 0) {
                try {
                    factor = Math.multiplyExact(factor, factor)
                } catch (_: ArithmeticException) {
                    throw RuntimeError("Integer overflow in '**' operation", line, "ArithmeticException")
                }
            }
        }
        return result
    }

}
