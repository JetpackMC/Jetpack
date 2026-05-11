package dev.jetpack.engine.resolver

import dev.jetpack.engine.lexer.TokenType
import dev.jetpack.engine.parser.ast.*

data class TypeCheckerError(val message: String, val line: Int)

class TypeChecker(private val typeProvider: BuiltinTypeProvider? = null) {

    private enum class FlowSignal {
        FALLTHROUGH,
        RETURN,
        BREAK,
        CONTINUE,
        NON_TERMINATING,
    }

    private data class NullConditionNarrowing(
        val whenTrue: Map<String, JetType>,
        val whenFalse: Map<String, JetType>,
    )

    private sealed class ConstantValue {
        data class IntValue(val value: Int) : ConstantValue()
    }

    private val errors = mutableListOf<TypeCheckerError>()
    private val typeScopes = ArrayDeque<MutableMap<String, JetType>>()
    private val constScopes = ArrayDeque<MutableSet<String>>()
    private val readOnlyScopes = ArrayDeque<MutableSet<String>>()
    private val constValueScopes = ArrayDeque<MutableMap<String, ConstantValue>>()
    private var currentReturnType: JetType? = null

    private inline fun <T> withThreadBoundary(action: () -> T): T {
        val prevReturnType = currentReturnType
        currentReturnType = null
        return try {
            action()
        } finally {
            currentReturnType = prevReturnType
        }
    }

    fun check(stmts: List<Statement>, predefinedTypes: Map<String, JetType> = emptyMap()): List<TypeCheckerError> {
        errors.clear()
        pushScope()
        for ((name, type) in predefinedTypes) {
            defineType(name, type, 0, isConst = true)
        }
        for (stmt in stmts) {
            if (stmt is Statement.FunctionDecl) hoistFunction(stmt)
            if (stmt is Statement.IntervalDecl) defineType(stmt.name, JetType.TInterval, stmt.line)
            if (stmt is Statement.ListenerDecl) defineType(stmt.name, JetType.TListener, stmt.line)
            if (stmt is Statement.CommandDecl)  defineType(stmt.name, JetType.TCommand, stmt.line)
        }
        for (stmt in stmts) checkStmt(stmt)
        popScope()
        return errors.toList()
    }

    private fun checkStmt(stmt: Statement) {
        when (stmt) {
            is Statement.Metadata, is Statement.Using, is Statement.Manifest -> Unit

            is Statement.VarDecl -> {
                val declaredType = resolveTypeRef(stmt.typeName, stmt.line, "Variable '${stmt.name}'")
                val initType = inferExpr(stmt.initializer)
                val actualType = if (declaredType == JetType.TUnknown) {
                    if (initType == JetType.TNull) JetType.TUnknown.asNullable() else initType
                } else {
                    if (initType == JetType.TNull) {
                        declaredType.asNullable()
                    } else {
                        if (!declaredType.accepts(initType)) {
                            errors.add(TypeCheckerError("Expected type '$declaredType' but got '$initType'", stmt.line))
                        }
                        declaredType
                    }
                }
                defineType(
                    stmt.name,
                    actualType,
                    stmt.line,
                    stmt.isConst,
                    if (stmt.isConst) evaluateConstantValue(stmt.initializer) else null,
                )
            }

            is Statement.ExprStatement -> inferExpr(stmt.expression)

            is Statement.FunctionDecl -> {
                val retType = stmt.returnType?.let { resolveTypeRef(it, stmt.line, "Function '${stmt.name}' return type") }
                    ?: JetType.TUnknown
                val paramTypes = stmt.params.associateWith { param ->
                    param.typeName?.let { resolveTypeRef(it, stmt.line, "Parameter '${param.name}'") }
                        ?: JetType.TUnknown
                }
                val prevReturn = currentReturnType
                currentReturnType = retType
                for (param in stmt.params) {
                    if (param.default != null) {
                        val paramType = paramTypes.getValue(param)
                        val defaultType = inferExpr(param.default)
                        if (paramType != JetType.TUnknown && !paramType.accepts(defaultType)) {
                            errors.add(TypeCheckerError(
                                "Default value for parameter '${param.name}' has type '$defaultType', expected '$paramType'",
                                stmt.line,
                            ))
                        }
                    }
                }
                pushScope()
                for (param in stmt.params) {
                    defineType(param.name, paramTypes.getValue(param), stmt.line)
                }
                for (bodyStmt in stmt.body) checkStmt(bodyStmt)
                if (retType != JetType.TUnknown && !hasRequiredReturns(stmt.body)) {
                    errors.add(TypeCheckerError(
                        "Function '${stmt.name}' with return type '$retType' does not always return a value",
                        stmt.line,
                    ))
                }
                popScope()
                currentReturnType = prevReturn
            }

            is Statement.IntervalDecl -> {
                pushScope()
                for (bodyStmt in stmt.body) checkStmt(bodyStmt)
                popScope()
            }

            is Statement.ListenerDecl -> {
                pushScope()
                if (stmt.senderParam != null) defineType(stmt.senderParam, JetType.TObject, stmt.line)
                for (bodyStmt in stmt.body) checkStmt(bodyStmt)
                popScope()
            }

            is Statement.IfStmt -> {
                checkCondition(stmt.condition)
                val narrowing = extractNullConditionNarrowing(stmt.condition)
                checkBlock(stmt.thenBody, narrowing?.whenTrue.orEmpty())
                for ((condition, body) in stmt.elseIfClauses) {
                    checkCondition(condition)
                    checkBlock(body, extractNullConditionNarrowing(condition)?.whenTrue.orEmpty())
                }
                stmt.elseBody?.let { checkBlock(it, narrowing?.whenFalse.orEmpty()) }

                if (narrowing != null) {
                    val thenFallsThrough = FlowSignal.FALLTHROUGH in analyzeBlockFlow(stmt.thenBody)
                    val elseFallsThrough = stmt.elseBody?.let { FlowSignal.FALLTHROUGH in analyzeBlockFlow(it) }

                    when {
                        !thenFallsThrough -> applyTypeOverrides(narrowing.whenFalse)
                        stmt.elseIfClauses.isEmpty() && elseFallsThrough == false -> applyTypeOverrides(narrowing.whenTrue)
                    }
                }
            }

            is Statement.WhileStmt -> {
                checkCondition(stmt.condition)
                checkBlock(stmt.body, extractNullConditionNarrowing(stmt.condition)?.whenTrue.orEmpty())
            }

            is Statement.ForEachStmt -> {
                val iterableType = inferExpr(stmt.iterable)
                val elementType = when (iterableType) {
                    is JetType.TList -> iterableType.elementType
                    JetType.TString  -> JetType.TString
                    JetType.TObject  -> JetType.TObject
                    JetType.TUnknown -> JetType.TUnknown
                    else -> {
                        errors.add(TypeCheckerError("Cannot iterate over value of type '$iterableType'", stmt.line))
                        JetType.TUnknown
                    }
                }
                val declaredElementType = stmt.itemType?.let {
                    resolveTypeRef(it, stmt.line, "Foreach item '${stmt.itemName}'")
                }
                if (declaredElementType != null &&
                    declaredElementType != JetType.TUnknown &&
                    !declaredElementType.accepts(elementType)
                ) {
                    errors.add(
                        TypeCheckerError(
                            "Foreach item '${stmt.itemName}' has type '$declaredElementType' but iterable yields '$elementType'",
                            stmt.line,
                        )
                    )
                }
                pushScope()
                defineReadOnly(stmt.itemName, declaredElementType ?: elementType, stmt.line)
                for (bodyStmt in stmt.body) checkStmt(bodyStmt)
                popScope()
            }

            is Statement.ReturnStmt -> {
                val expected = currentReturnType
                if (expected != null && expected != JetType.TUnknown) {
                    if (stmt.value == null) {
                        errors.add(TypeCheckerError("Missing return value for function returning '$expected'", stmt.line))
                    } else {
                        val actual = inferExpr(stmt.value)
                        if (!expected.accepts(actual)) {
                            errors.add(TypeCheckerError("Return type does not match function return type", stmt.line))
                        }
                    }
                } else {
                    stmt.value?.let { inferExpr(it) }
                }
            }

            is Statement.BreakStmt, is Statement.ContinueStmt -> Unit

            is Statement.CommandDecl -> checkCommandDecl(stmt, stmt.senderName)
        }
    }

    private fun checkCommandDecl(stmt: Statement.CommandDecl, inheritedSenderName: String?) {
        val effectiveSenderName = stmt.senderName ?: inheritedSenderName
        pushScope()
        if (effectiveSenderName != null) typeScopes.last()[effectiveSenderName] = JetType.TObject
        val paramTypes = stmt.params.associateWith { param ->
            param.typeName?.let { resolveTypeRef(it, stmt.line, "Command parameter '${param.name}'") }
                ?: JetType.TUnknown
        }
        for (param in stmt.params) {
            if (param.default != null) {
                if (!isConstantExpression(param.default)) {
                    errors.add(TypeCheckerError(
                        "Default value for command parameter '${param.name}' must be a constant expression",
                        stmt.line,
                    ))
                }
                val paramType = paramTypes.getValue(param)
                val defaultType = inferExpr(param.default)
                if (paramType != JetType.TUnknown && !paramType.accepts(defaultType)) {
                    errors.add(TypeCheckerError(
                        "Default value for parameter '${param.name}' has type '$defaultType', expected '$paramType'",
                        stmt.line,
                    ))
                }
            }
            typeScopes.last()[param.name] = paramTypes.getValue(param)
        }
        val prevReturn = currentReturnType
        currentReturnType = null
        for (item in stmt.bodyItems) {
            when (item) {
                is CommandBodyItem.Code -> checkStmt(item.stmt)
                is CommandBodyItem.SubCommand -> checkCommandDecl(item.decl, effectiveSenderName)
                is CommandBodyItem.Default -> item.body.forEach { checkStmt(it) }
            }
        }
        currentReturnType = prevReturn
        popScope()
    }

    private fun checkBlock(stmts: List<Statement>, predefinedTypes: Map<String, JetType> = emptyMap()) {
        pushScope()
        for ((name, type) in predefinedTypes) {
            defineType(name, type, 0)
        }
        for (stmt in stmts) checkStmt(stmt)
        popScope()
    }

    private fun checkCondition(expr: Expression) {
        val type = inferExpr(expr)
        if (type == JetType.TNull) {
            errors.add(TypeCheckerError("Condition cannot be null", expr.line))
        }
    }

    private fun isNullableType(type: JetType): Boolean = type == JetType.TNull || type is JetType.TNullable

    private fun commonSupertype(types: List<JetType>): JetType {
        val nonUnknown = types.filter { it != JetType.TUnknown }
        if (nonUnknown.isEmpty()) return JetType.TUnknown

        val containsNull = nonUnknown.any(::isNullableType)
        val baseTypes = nonUnknown.mapNotNull { type ->
            when (type) {
                JetType.TNull -> null
                is JetType.TNullable -> type.innerType.withoutNull()
                else -> type
            }
        }

        val baseType = when {
            baseTypes.isEmpty() -> JetType.TUnknown
            baseTypes.all { it == JetType.TInt } -> JetType.TInt
            baseTypes.all { it.isNumeric() } -> JetType.TFloat
            baseTypes.drop(1).all { it == baseTypes.first() } -> baseTypes.first()
            else -> JetType.TUnknown
        }

        return when {
            containsNull && baseType != JetType.TUnknown -> baseType.asNullable()
            containsNull && baseTypes.isEmpty() -> JetType.TUnknown.asNullable()
            else -> baseType
        }
    }

    private fun extractNullConditionNarrowing(expr: Expression): NullConditionNarrowing? {
        val binary = expr as? Expression.BinaryOp ?: return null
        if (binary.operator.type != TokenType.EQ_EQ && binary.operator.type != TokenType.BANG_EQ) return null

        val identifier = when {
            binary.left is Expression.Identifier && binary.right is Expression.NullLiteral -> binary.left
            binary.right is Expression.Identifier && binary.left is Expression.NullLiteral -> binary.right
            else -> null
        } as? Expression.Identifier ?: return null

        val currentType = lookupType(identifier.name) ?: return null
        if (!isNullableType(currentType) && currentType != JetType.TUnknown) return null

        val nonNullType = when (currentType) {
            JetType.TNull -> JetType.TUnknown
            is JetType.TNullable -> currentType.innerType
            else -> currentType
        }

        return if (binary.operator.type == TokenType.BANG_EQ) {
            NullConditionNarrowing(
                whenTrue = mapOf(identifier.name to nonNullType),
                whenFalse = mapOf(identifier.name to JetType.TNull),
            )
        } else {
            NullConditionNarrowing(
                whenTrue = mapOf(identifier.name to JetType.TNull),
                whenFalse = mapOf(identifier.name to nonNullType),
            )
        }
    }

    private fun refineIdentifierType(name: String, currentType: JetType, valueType: JetType) {
        val refinedType = when {
            currentType == JetType.TUnknown && valueType == JetType.TNull -> JetType.TUnknown.asNullable()
            currentType == JetType.TUnknown -> valueType
            currentType is JetType.TNullable && currentType.innerType == JetType.TUnknown && valueType != JetType.TNull ->
                valueType.asNullable()
            else -> return
        }
        for (i in typeScopes.size - 1 downTo 0) {
            if (typeScopes[i].containsKey(name)) {
                typeScopes[i][name] = refinedType
                return
            }
        }
    }

    private fun applyTypeOverrides(overrides: Map<String, JetType>) {
        for ((name, type) in overrides) {
            for (i in typeScopes.size - 1 downTo 0) {
                if (typeScopes[i].containsKey(name)) {
                    typeScopes[i][name] = type
                    break
                }
            }
        }
    }

    private fun <T> withTemporaryTypes(overrides: Map<String, JetType>, action: () -> T): T {
        pushScope()
        for ((name, type) in overrides) {
            defineType(name, type, 0)
        }
        return try {
            action()
        } finally {
            popScope()
        }
    }

    private fun evaluateConstantValue(expr: Expression): ConstantValue? =
        evaluateConstantInt(expr)?.let(ConstantValue::IntValue)

    private fun evaluateConstantInt(expr: Expression): Int? = when (expr) {
        is Expression.IntLiteral -> expr.value
        is Expression.Identifier -> (lookupConstValue(expr.name) as? ConstantValue.IntValue)?.value
        is Expression.UnaryOp -> when (expr.operator.type) {
            TokenType.MINUS -> evaluateConstantInt(expr.operand)?.let { -it }
            else -> null
        }
        is Expression.BinaryOp -> {
            val left = evaluateConstantInt(expr.left) ?: return null
            val right = evaluateConstantInt(expr.right) ?: return null
            when (expr.operator.type) {
                TokenType.PLUS -> left + right
                TokenType.MINUS -> left - right
                TokenType.STAR -> left * right
                TokenType.SLASH -> if (right == 0) null else left / right
                TokenType.PERCENT -> if (right == 0) null else left % right
                TokenType.STAR_STAR -> if (right < 0) null else (1..right).fold(1) { acc, _ -> acc * left }
                else -> null
            }
        }
        is Expression.Ternary -> {
            val condition = evaluateConstantBoolean(expr.condition) ?: return null
            if (condition) evaluateConstantInt(expr.thenExpr) else evaluateConstantInt(expr.elseExpr)
        }
        else -> null
    }

    private fun evaluateConstantBoolean(expr: Expression): Boolean? {
        return when (expr) {
            is Expression.BoolLiteral -> expr.value
            is Expression.Identifier -> when (lookupConstValue(expr.name)) {
                is ConstantValue.IntValue -> null
                null -> null
            }
            is Expression.BinaryOp -> {
                if (expr.operator.type == TokenType.EQ_EQ || expr.operator.type == TokenType.BANG_EQ) {
                    val left = evaluateConstantInt(expr.left)
                    val right = evaluateConstantInt(expr.right)
                    if (left != null && right != null) {
                        if (expr.operator.type == TokenType.EQ_EQ) left == right else left != right
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun refineModuleCallReturnType(
        moduleName: String,
        member: String,
        args: List<Expression>,
        validatedType: JetType,
    ): JetType {
        if (moduleName == "math" && member == "round" && args.size == 2) {
            val digits = evaluateConstantInt(args[1])
            if (digits == 0) return JetType.TInt
        }
        return validatedType
    }

    private fun inferExpr(expr: Expression): JetType = when (expr) {
        is Expression.IntLiteral         -> JetType.TInt
        is Expression.FloatLiteral       -> JetType.TFloat
        is Expression.StringLiteral      -> JetType.TString
        is Expression.BoolLiteral        -> JetType.TBool
        is Expression.NullLiteral        -> JetType.TNull
        is Expression.InterpolatedString -> JetType.TString
        is Expression.ListLiteral        -> {
            val elementTypeList = expr.elements.map { inferExpr(it) }
            val elementType = when {
                elementTypeList.isEmpty() -> JetType.TUnknown
                else -> {
                    val commonType = commonSupertype(elementTypeList)
                    if (commonType == JetType.TUnknown) {
                        val first = elementTypeList.first()
                        val mismatch = elementTypeList.firstOrNull { !first.accepts(it) && !it.accepts(first) }
                        if (mismatch != null) {
                            errors.add(TypeCheckerError(
                                "List element type mismatch: expected '$first' but got '$mismatch'",
                                expr.line,
                            ))
                        }
                    }
                    commonType
                }
            }
            JetType.TList(elementType)
        }
        is Expression.ObjectLiteral -> JetType.TObject
        is Expression.Identifier    -> {
            val t = lookupType(expr.name)
            if (t == null && typeProvider?.isKnownGlobal(expr.name) != true) {
                errors.add(TypeCheckerError("Undefined identifier '${expr.name}'", expr.line))
            }
            t ?: JetType.TUnknown
        }
        is Expression.Ternary       -> {
            checkCondition(expr.condition)
            val thenType = inferExpr(expr.thenExpr)
            val elseType = inferExpr(expr.elseExpr)
            commonSupertype(listOf(thenType, elseType))
        }
        is Expression.Range         -> {
            val startType = inferExpr(expr.start)
            val endType = inferExpr(expr.end)
            if (isNullableType(startType)) {
                errors.add(TypeCheckerError("Range start cannot be nullable", expr.line))
            } else if (startType != JetType.TUnknown && startType != JetType.TInt) {
                errors.add(TypeCheckerError("Range start must be int, got '$startType'", expr.line))
            }
            if (isNullableType(endType)) {
                errors.add(TypeCheckerError("Range end cannot be nullable", expr.line))
            } else if (endType != JetType.TUnknown && endType != JetType.TInt) {
                errors.add(TypeCheckerError("Range end must be int, got '$endType'", expr.line))
            }
            JetType.TList(JetType.TInt)
        }
        is Expression.BinaryOp      -> inferBinaryOp(expr)
        is Expression.UnaryOp       -> inferUnaryOp(expr)
        is Expression.Call          -> inferCall(expr)
        is Expression.ThreadCall    -> inferCall(expr.call)
        is Expression.ThreadBlock   -> withThreadBoundary {
            checkStmt(expr.statement)
            JetType.TNull
        }
        is Expression.MemberAccess  -> inferMemberAccess(expr)
        is Expression.IndexAccess   -> inferIndexAccess(expr)
        is Expression.Assign        -> {
            val valueType = inferExpr(expr.value)
            val targetType = inferExpr(expr.target)
            if (expr.target is Expression.Identifier) {
                when {
                    isConst(expr.target.name) -> errors.add(TypeCheckerError("Cannot reassign const variable '${expr.target.name}'", expr.line))
                    isReadOnly(expr.target.name) -> errors.add(TypeCheckerError("Cannot reassign foreach item '${expr.target.name}'", expr.line))
                }
            }
            if (!targetType.accepts(valueType) && targetType != JetType.TUnknown) {
                errors.add(TypeCheckerError("Expected type '$targetType' but got '$valueType'", expr.line))
            }
            if (expr.target is Expression.Identifier) {
                refineIdentifierType(expr.target.name, targetType, valueType)
            }
            valueType
        }
        is Expression.CompoundAssign -> {
            val targetType = inferExpr(expr.target)
            val valueType  = inferExpr(expr.value)
            if (expr.target is Expression.Identifier) {
                when {
                    isConst(expr.target.name) -> errors.add(TypeCheckerError("Cannot reassign const variable '${expr.target.name}'", expr.line))
                    isReadOnly(expr.target.name) -> errors.add(TypeCheckerError("Cannot reassign foreach item '${expr.target.name}'", expr.line))
                }
            }
            if (isNullableType(targetType)) {
                errors.add(TypeCheckerError("Compound assignment cannot be applied to nullable type '$targetType'", expr.line))
                JetType.TUnknown
            } else {
                val op = expr.operator.type
                when {
                    op == TokenType.PLUS_ASSIGN && targetType == JetType.TString -> {
                        if (valueType != JetType.TString && valueType != JetType.TUnknown)
                            errors.add(TypeCheckerError("String '+=' requires a string operand, got '$valueType'", expr.line))
                        JetType.TString
                    }
                    op == TokenType.STAR_ASSIGN && targetType == JetType.TString -> {
                        if (valueType != JetType.TInt && valueType != JetType.TUnknown)
                            errors.add(TypeCheckerError("String '*=' requires an int operand, got '$valueType'", expr.line))
                        JetType.TString
                    }
                    op == TokenType.PLUS_ASSIGN && targetType is JetType.TList -> {
                        if (valueType is JetType.TList) {
                            val te = targetType.elementType
                            val ve = valueType.elementType
                            if (te != JetType.TUnknown && ve != JetType.TUnknown && !te.accepts(ve)) {
                                errors.add(TypeCheckerError(
                                    "Cannot concatenate lists with different element types: '$targetType' and '$valueType'",
                                    expr.line,
                                ))
                            }
                        } else if (valueType != JetType.TUnknown) {
                            errors.add(TypeCheckerError("List '+=' requires a list operand, got '$valueType'", expr.line))
                        }
                        targetType
                    }
                    targetType.isNumeric() || targetType == JetType.TUnknown -> {
                        if (valueType != JetType.TUnknown && !valueType.isNumeric())
                            errors.add(TypeCheckerError("Compound assignment requires numeric operands, got '$valueType'", expr.line))
                        targetType
                    }
                    else -> {
                        errors.add(TypeCheckerError(
                            "Operator '${expr.operator.value}' cannot be applied to type '$targetType'",
                            expr.line,
                        ))
                        JetType.TUnknown
                    }
                }
            }
        }
    }

    private fun inferBinaryOp(expr: Expression.BinaryOp): JetType {
        val op = expr.operator.type
        if (op == TokenType.AMP_AMP || op == TokenType.PIPE_PIPE) {
            val leftType = inferExpr(expr.left)
            val narrowing = extractNullConditionNarrowing(expr.left)
            val rightType = when {
                narrowing == null -> inferExpr(expr.right)
                op == TokenType.AMP_AMP -> withTemporaryTypes(narrowing.whenTrue) { inferExpr(expr.right) }
                else -> withTemporaryTypes(narrowing.whenFalse) { inferExpr(expr.right) }
            }
            if (leftType == JetType.TNull) {
                errors.add(TypeCheckerError("Operator '${expr.operator.value}' cannot be applied to null", expr.line))
            }
            if (rightType == JetType.TNull) {
                errors.add(TypeCheckerError("Operator '${expr.operator.value}' cannot be applied to null", expr.line))
            }
            return JetType.TBool
        }

        val left  = inferExpr(expr.left)
        val right = inferExpr(expr.right)
        return when {
            op == TokenType.EQ_EQ || op == TokenType.BANG_EQ -> JetType.TBool
            op in setOf(TokenType.LT, TokenType.LT_EQ, TokenType.GT, TokenType.GT_EQ) -> {
                if (isNullableType(left)) {
                    errors.add(TypeCheckerError("Operator '${expr.operator.value}' cannot be applied to nullable type '$left'", expr.line))
                } else if (left != JetType.TUnknown && !left.isNumeric())
                    errors.add(TypeCheckerError("Operator '${expr.operator.value}' requires numeric operands, got '$left'", expr.line))
                if (isNullableType(right)) {
                    errors.add(TypeCheckerError("Operator '${expr.operator.value}' cannot be applied to nullable type '$right'", expr.line))
                } else if (right != JetType.TUnknown && !right.isNumeric())
                    errors.add(TypeCheckerError("Operator '${expr.operator.value}' requires numeric operands, got '$right'", expr.line))
                JetType.TBool
            }
            op == TokenType.PLUS && left == JetType.TString && right == JetType.TString -> JetType.TString
            op == TokenType.PLUS && left is JetType.TList && right is JetType.TList -> {
                val le = left.elementType
                val re = right.elementType
                if (le != JetType.TUnknown && re != JetType.TUnknown && !le.accepts(re)) {
                    errors.add(TypeCheckerError(
                        "Cannot concatenate lists with different element types: '$left' and '$right'",
                        expr.line,
                    ))
                }
                val elem = commonSupertype(listOf(le, re))
                JetType.TList(elem)
            }
            op == TokenType.STAR && left == JetType.TString && right == JetType.TInt -> JetType.TString
            op == TokenType.STAR && left == JetType.TInt && right == JetType.TString -> JetType.TString
            left.isNumeric() && right.isNumeric() -> when {
                op == TokenType.STAR_STAR -> JetType.TFloat
                left == JetType.TInt && right == JetType.TInt -> JetType.TInt
                else                                           -> JetType.TFloat
            }
            isNullableType(left) || isNullableType(right) -> {
                val nullableType = if (isNullableType(left)) left else right
                errors.add(TypeCheckerError(
                    "Operator '${expr.operator.value}' cannot be applied to nullable type '$nullableType'",
                    expr.line,
                ))
                JetType.TUnknown
            }
            left == JetType.TUnknown || right == JetType.TUnknown -> JetType.TUnknown
            else -> {
                errors.add(TypeCheckerError(
                    "Operator '${expr.operator.value}' cannot be applied to types '$left' and '$right'",
                    expr.line,
                ))
                JetType.TUnknown
            }
        }
    }

    private fun inferUnaryOp(expr: Expression.UnaryOp): JetType {
        val operand = inferExpr(expr.operand)
        return when (expr.operator.type) {
            TokenType.BANG                                -> {
                if (operand == JetType.TNull) {
                    errors.add(TypeCheckerError("Operator '!' cannot be applied to null", expr.line))
                }
                JetType.TBool
            }
            TokenType.MINUS                               -> {
                if (isNullableType(operand)) {
                    errors.add(TypeCheckerError("Operator '-' cannot be applied to nullable type '$operand'", expr.line))
                    JetType.TUnknown
                } else if (operand.isNumeric() || operand == JetType.TUnknown) {
                    operand
                } else {
                    errors.add(TypeCheckerError("Operator '-' requires a numeric operand, got '$operand'", expr.line))
                    JetType.TUnknown
                }
            }
            TokenType.PLUS_PLUS, TokenType.MINUS_MINUS   -> {
                checkMutationTarget(expr.operand, operand, expr.line, expr.operator.value)
                operand
            }
            else                                         -> JetType.TUnknown
        }
    }

    private fun inferCall(expr: Expression.Call): JetType {
        if (expr.callee is Expression.MemberAccess) {
            val target = expr.callee.target
            val member = expr.callee.member
            val argTypes = expr.arguments.map { inferExpr(it) }
            val targetType = inferExpr(target)
            if (isNullableType(targetType)) {
                errors.add(TypeCheckerError("Cannot call method '$member' on nullable type '$targetType'", expr.line))
                return JetType.TUnknown
            }
            if (targetType is JetType.TModule) {
                val fieldType = targetType.fields[member]
                return when {
                    fieldType == null -> {
                        errors.add(TypeCheckerError("Module member '$member' does not exist", expr.line))
                        JetType.TUnknown
                    }
                    else -> {
                        val validated = validateCallableType(fieldType, argTypes, expr.line, "Module member '$member'")
                        refineModuleCallReturnType((target as? Expression.Identifier)?.name ?: "", member, expr.arguments, validated)
                    }
                }
            }
            val builtinMethodType = typeProvider?.methodType(targetType, member)
            if (builtinMethodType != null) {
                if (member in setOf("ascend", "descend") && targetType is JetType.TList) {
                    val elementType = targetType.elementType
                    if (elementType != JetType.TUnknown &&
                        elementType != JetType.TInt &&
                        elementType != JetType.TFloat &&
                        elementType != JetType.TString
                    ) {
                        errors.add(TypeCheckerError(
                            "Method '$member' requires list elements to be int, float, or string, got '$elementType'",
                            expr.line,
                        ))
                    }
                }
                return validateCallableType(builtinMethodType, argTypes, expr.line, "Method '$member'")
            }
            if (targetType != JetType.TUnknown && targetType != JetType.TObject && targetType != JetType.TCommand) {
                errors.add(TypeCheckerError("Type '$targetType' has no method '$member'", expr.line))
            }
            return JetType.TUnknown
        }
        val argTypes = expr.arguments.map { inferExpr(it) }
        if (expr.callee is Expression.Identifier) {
            typeProvider?.globalType(expr.callee.name)?.let { globalType ->
                return validateCallableType(globalType, argTypes, expr.line, "Function '${expr.callee.name}'")
            }
            val functionType = lookupType(expr.callee.name)
            if (functionType is JetType.TCommand) {
                errors.add(TypeCheckerError("'${expr.callee.name}' is a command and cannot be called as a function", expr.line))
                return JetType.TUnknown
            }
            if (functionType is JetType.TCallable) {
                return validateCallableInvocation(functionType, argTypes, expr.line, "Function '${expr.callee.name}'")
            }
            if (functionType == null || functionType == JetType.TUnknown || functionType is JetType.TFunction) {
                return JetType.TUnknown
            }
            errors.add(TypeCheckerError("Identifier '${expr.callee.name}' is not callable", expr.line))
            return JetType.TUnknown
        }
        val calleeType = inferExpr(expr.callee)
        if (calleeType !is JetType.TCallable && calleeType != JetType.TFunction && calleeType != JetType.TUnknown) {
            errors.add(TypeCheckerError("Value of type '$calleeType' is not callable", expr.line))
        }
        return JetType.TUnknown
    }

    private fun inferMemberAccess(expr: Expression.MemberAccess): JetType {
        val targetType = inferExpr(expr.target)
        if (isNullableType(targetType)) {
            errors.add(TypeCheckerError("Cannot access member '${expr.member}' on nullable type '$targetType'", expr.line))
            return JetType.TUnknown
        }
        if (targetType is JetType.TModule) {
            val fieldType = targetType.fields[expr.member]
            if (fieldType == null) {
                errors.add(TypeCheckerError("Module member '${expr.member}' does not exist", expr.line))
                return JetType.TUnknown
            }
            return if (fieldType is JetType.TCallable) JetType.TFunction else fieldType
        }
        val builtinMethodType = typeProvider?.methodType(targetType, expr.member)
        if (builtinMethodType is JetType.TCallable && targetType != JetType.TUnknown && targetType != JetType.TObject) {
            errors.add(
                TypeCheckerError(
                    "Member '${expr.member}' on type '$targetType' is a method and must be called with parentheses",
                    expr.line,
                )
            )
        }
        if (builtinMethodType == null && targetType != JetType.TUnknown && targetType != JetType.TObject && targetType != JetType.TCommand) {
            errors.add(TypeCheckerError("Type '$targetType' has no member '${expr.member}'", expr.line))
        }
        return JetType.TUnknown
    }

    private fun inferIndexAccess(expr: Expression.IndexAccess): JetType {
        val targetType = inferExpr(expr.target)
        val indexType = inferExpr(expr.index)
        if (isNullableType(targetType)) {
            errors.add(TypeCheckerError("Cannot index nullable type '$targetType'", expr.line))
            return JetType.TUnknown
        }
        return when (targetType) {
            is JetType.TList -> {
                if (indexType != JetType.TUnknown && indexType != JetType.TInt) {
                    errors.add(TypeCheckerError("List index must be an int, got '$indexType'", expr.line))
                }
                targetType.elementType
            }
            JetType.TString  -> {
                if (indexType != JetType.TUnknown && indexType != JetType.TInt) {
                    errors.add(TypeCheckerError("String index must be an int, got '$indexType'", expr.line))
                }
                JetType.TString
            }
            JetType.TObject -> {
                if (indexType != JetType.TUnknown && indexType != JetType.TString) {
                    errors.add(TypeCheckerError("Object index must be a string, got '$indexType'", expr.line))
                }
                JetType.TUnknown
            }
            else             -> {
                if (targetType != JetType.TUnknown) {
                    errors.add(TypeCheckerError("Type '$targetType' does not support index access", expr.line))
                }
                JetType.TUnknown
            }
        }
    }

    private fun validateCallableType(
        type: JetType,
        argTypes: List<JetType>,
        line: Int,
        calleeLabel: String,
    ): JetType {
        val callable = type as? JetType.TCallable
        if (callable == null) {
            errors.add(TypeCheckerError("$calleeLabel is not callable", line))
            return JetType.TUnknown
        }
        return validateCallableInvocation(callable, argTypes, line, calleeLabel)
    }

    private fun validateCallableInvocation(
        callable: JetType.TCallable,
        argTypes: List<JetType>,
        line: Int,
        calleeLabel: String,
    ): JetType {
        if (callable.signatures.isEmpty()) return callable.returnType
        val matches = callable.signatures
            .mapNotNull { signature -> signature.matchScore(argTypes)?.let { it to signature } }
        if (matches.isNotEmpty()) {
            val bestScore = matches.minOf { it.first }
            val bestMatches = matches.filter { it.first == bestScore }.map { it.second }
            return commonSupertype(bestMatches.map { it.returnType ?: callable.returnType })
        }
        val expected = callable.signatures.joinToString(" or ") { it.describe() }
        val actual = "(${argTypes.joinToString(", ")})"
        errors.add(TypeCheckerError("$calleeLabel expects $expected but got $actual", line))
        return callable.returnType
    }

    private fun pushScope() {
        typeScopes.addLast(mutableMapOf())
        constScopes.addLast(mutableSetOf())
        readOnlyScopes.addLast(mutableSetOf())
        constValueScopes.addLast(mutableMapOf())
    }

    private fun popScope() {
        typeScopes.removeLast()
        constScopes.removeLast()
        readOnlyScopes.removeLast()
        constValueScopes.removeLast()
    }

    private fun defineType(
        name: String,
        type: JetType,
        line: Int,
        isConst: Boolean = false,
        constValue: ConstantValue? = null,
    ) {
        typeScopes.last()[name] = type
        if (isConst) {
            constScopes.last().add(name)
            if (constValue != null) {
                constValueScopes.last()[name] = constValue
            }
        }
    }

    private fun defineReadOnly(name: String, type: JetType, line: Int) {
        defineType(name, type, line)
        readOnlyScopes.last().add(name)
    }

    private fun isConst(name: String): Boolean {
        for (i in constScopes.size - 1 downTo 0) {
            if (typeScopes[i].containsKey(name)) {
                return name in constScopes[i]
            }
        }
        return false
    }

    private fun isReadOnly(name: String): Boolean {
        for (i in readOnlyScopes.size - 1 downTo 0) {
            if (typeScopes[i].containsKey(name)) {
                return name in readOnlyScopes[i]
            }
        }
        return false
    }

    private fun lookupType(name: String): JetType? {
        for (i in typeScopes.size - 1 downTo 0) {
            typeScopes[i][name]?.let { return it }
        }
        return null
    }

    private fun lookupConstValue(name: String): ConstantValue? {
        for (i in constValueScopes.size - 1 downTo 0) {
            constValueScopes[i][name]?.let { return it }
        }
        return null
    }

    private fun hoistFunction(stmt: Statement.FunctionDecl) {
        val retType = stmt.returnType?.toJetType() ?: JetType.TUnknown
        typeScopes.last()[stmt.name] = JetType.TCallable(retType, listOf(paramsToCallSignature(stmt.params)))
    }

    private fun resolveTypeRef(typeRef: TypeRef, line: Int, context: String): JetType {
        val resolved = typeRef.toJetTypeOrNull()
        if (resolved != null) return resolved
        errors.add(TypeCheckerError("Unsupported type '${formatTypeRef(typeRef)}' in $context", line))
        return JetType.TUnknown
    }

    private fun formatTypeRef(typeRef: TypeRef): String =
        if (typeRef.name == "list") {
            "list<${typeRef.typeArgRef?.let { formatTypeRef(it) } ?: "unknown"}>"
        } else {
            typeRef.name
        }

    private fun isThreadResultTarget(expr: Expression): Boolean = when (expr) {
        is Expression.MemberAccess -> startsWithThreadResult(expr.target)
        is Expression.IndexAccess -> startsWithThreadResult(expr.target)
        else -> false
    }

    private fun startsWithThreadResult(expr: Expression): Boolean = when (expr) {
        is Expression.ThreadCall, is Expression.ThreadBlock -> true
        is Expression.MemberAccess -> startsWithThreadResult(expr.target)
        is Expression.IndexAccess -> startsWithThreadResult(expr.target)
        else -> false
    }

    private fun checkMutationTarget(target: Expression, targetType: JetType, line: Int, operator: String) {
        val isAssignable = target is Expression.Identifier ||
            target is Expression.MemberAccess ||
            target is Expression.IndexAccess
        if (!isAssignable) {
            errors.add(TypeCheckerError("Operator '$operator' requires an assignable target", line))
            return
        }
        if (isThreadResultTarget(target)) {
            errors.add(TypeCheckerError("Cannot mutate a threaded result target", line))
            return
        }
        if (target is Expression.Identifier) {
            when {
                isConst(target.name) -> errors.add(TypeCheckerError("Cannot reassign const variable '${target.name}'", line))
                isReadOnly(target.name) -> errors.add(TypeCheckerError("Cannot reassign foreach item '${target.name}'", line))
            }
        }
        if (isNullableType(targetType)) {
            errors.add(TypeCheckerError("Operator '$operator' cannot be applied to nullable type '$targetType'", line))
        } else if (targetType != JetType.TUnknown && !targetType.isNumeric()) {
            errors.add(TypeCheckerError("Operator '$operator' requires a numeric target, got '$targetType'", line))
        }
    }

    private fun isConstantExpression(expr: Expression): Boolean = when (expr) {
        is Expression.IntLiteral,
        is Expression.FloatLiteral,
        is Expression.StringLiteral,
        is Expression.BoolLiteral,
        is Expression.NullLiteral -> true
        is Expression.InterpolatedString ->
            expr.parts.all { part ->
                part is Expression.InterpolationPart.Literal ||
                    (part is Expression.InterpolationPart.Expr && isConstantExpression(part.expression))
            }
        is Expression.ListLiteral -> expr.elements.all(::isConstantExpression)
        is Expression.ObjectLiteral -> expr.entries.all { (_, value) -> isConstantExpression(value) }
        is Expression.Identifier -> isConst(expr.name)
        is Expression.BinaryOp -> isConstantExpression(expr.left) && isConstantExpression(expr.right)
        is Expression.UnaryOp -> isConstantExpression(expr.operand)
        is Expression.Ternary ->
            isConstantExpression(expr.condition) &&
                isConstantExpression(expr.thenExpr) &&
                isConstantExpression(expr.elseExpr)
        is Expression.Range ->
            isConstantExpression(expr.start) &&
                isConstantExpression(expr.end)
        is Expression.Call,
        is Expression.ThreadCall,
        is Expression.ThreadBlock,
        is Expression.MemberAccess,
        is Expression.IndexAccess,
        is Expression.Assign,
        is Expression.CompoundAssign -> false
    }

    private fun hasRequiredReturns(stmts: List<Statement>): Boolean {
        val flow = analyzeBlockFlow(stmts)
        return FlowSignal.FALLTHROUGH !in flow
    }

    private fun analyzeBlockFlow(stmts: List<Statement>): Set<FlowSignal> {
        val outcomes = linkedSetOf(FlowSignal.FALLTHROUGH)
        for (stmt in stmts) {
            if (FlowSignal.FALLTHROUGH !in outcomes) break
            outcomes.remove(FlowSignal.FALLTHROUGH)
            outcomes.addAll(analyzeStmtFlow(stmt))
        }
        return outcomes
    }

    private fun analyzeStmtFlow(stmt: Statement): Set<FlowSignal> = when (stmt) {
        is Statement.ReturnStmt -> setOf(FlowSignal.RETURN)
        is Statement.BreakStmt -> setOf(FlowSignal.BREAK)
        is Statement.ContinueStmt -> setOf(FlowSignal.CONTINUE)
        is Statement.IfStmt -> {
            val outcomes = linkedSetOf<FlowSignal>()
            outcomes.addAll(analyzeBlockFlow(stmt.thenBody))
            stmt.elseIfClauses.forEach { (_, body) -> outcomes.addAll(analyzeBlockFlow(body)) }
            if (stmt.elseBody != null) {
                outcomes.addAll(analyzeBlockFlow(stmt.elseBody))
            } else {
                outcomes.add(FlowSignal.FALLTHROUGH)
            }
            outcomes
        }
        is Statement.WhileStmt,
        is Statement.ForEachStmt -> setOf(FlowSignal.NON_TERMINATING)
        is Statement.Metadata,
        is Statement.Using,
        is Statement.Manifest,
        is Statement.VarDecl,
        is Statement.ExprStatement,
        is Statement.FunctionDecl,
        is Statement.IntervalDecl,
        is Statement.ListenerDecl,
        is Statement.CommandDecl -> setOf(FlowSignal.FALLTHROUGH)
    }
}
