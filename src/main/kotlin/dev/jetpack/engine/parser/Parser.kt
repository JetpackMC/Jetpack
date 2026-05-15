package dev.jetpack.engine.parser

import dev.jetpack.engine.lexer.Token
import dev.jetpack.engine.lexer.TokenType
import dev.jetpack.engine.parser.ast.*

class ParseException(message: String, val line: Int) : Exception(message)

class Parser(private val tokens: List<Token>) {

    companion object {
        private val TYPE_KEYWORDS = setOf(
            TokenType.KW_INT, TokenType.KW_FLOAT, TokenType.KW_STRING, TokenType.KW_BOOL,
            TokenType.KW_LIST, TokenType.KW_OBJECT, TokenType.KW_VAR,
        )
        private val ASSIGNMENT_OPERATORS = setOf(
            TokenType.EQ,
            TokenType.PLUS_ASSIGN,
            TokenType.MINUS_ASSIGN,
            TokenType.STAR_ASSIGN,
            TokenType.SLASH_ASSIGN,
            TokenType.PERCENT_ASSIGN,
            TokenType.STAR_STAR_ASSIGN,
        )
        private val ACCESS_MODIFIERS = setOf(
            TokenType.KW_PUBLIC, TokenType.KW_PRIVATE, TokenType.KW_PROTECTED,
        )
        private val EQUALITY_OPS = setOf(TokenType.EQ_EQ, TokenType.BANG_EQ)
        private val COMPARISON_OPS = setOf(TokenType.LT, TokenType.LT_EQ, TokenType.GT, TokenType.GT_EQ)
        private val ADD_SUB_OPS = setOf(TokenType.PLUS, TokenType.MINUS)
        private val MUL_DIV_OPS = setOf(TokenType.STAR, TokenType.SLASH, TokenType.PERCENT)
        private val UNARY_OPS = setOf(TokenType.BANG, TokenType.MINUS)
        private val PREFIX_INC_DEC_OPS = setOf(TokenType.PLUS_PLUS, TokenType.MINUS_MINUS)
    }

    private var pos = 0
    private var statementDepth = 0
    private var pendingCommandAnnotations: CommandAnnotations = CommandAnnotations.EMPTY
    private var pendingListenerAnnotations: ListenerAnnotations = ListenerAnnotations.EMPTY

    private fun peek(): Token = tokens[pos]
    private fun peek(offset: Int): Token =
        tokens.getOrElse(pos + offset) { tokens.last() }
    private fun peekType(): TokenType = tokens[pos].type
    private fun peekType(offset: Int): TokenType = peek(offset).type
    private fun advance(): Token = tokens[pos++]
    private fun isAtEnd(): Boolean = peekType() == TokenType.EOF

    private fun check(type: TokenType): Boolean = peekType() == type
    private fun match(vararg types: TokenType): Boolean {
        if (peekType() in types) { advance(); return true }
        return false
    }
    private fun expect(type: TokenType, msg: String): Token {
        if (!check(type)) throw ParseException(msg, peek().line)
        return advance()
    }
    private fun skipNewlines() { while (check(TokenType.NEWLINE) || check(TokenType.SEMICOLON)) advance() }
    private inline fun <T> withNestedStatements(block: () -> T): T {
        statementDepth++
        return try {
            block()
        } finally {
            statementDepth--
        }
    }

    fun parseFile(): List<Statement> {
        val stmts = mutableListOf<Statement>()
        skipNewlines()
        while (!isAtEnd()) {
            val pendingMeta = mutableListOf<Statement.Metadata>()
            while (check(TokenType.AT)) {
                pendingMeta.add(parseMetadata())
                skipNewlines()
            }
            if (isAtEnd()) { stmts.addAll(pendingMeta); break }
            if (pendingMeta.isNotEmpty() && isCommandDeclarationAhead()) {
                pendingCommandAnnotations = buildCommandAnnotations(pendingMeta)
            } else if (pendingMeta.isNotEmpty() && isListenerDeclarationAhead()) {
                pendingListenerAnnotations = buildListenerAnnotations(pendingMeta)
            } else {
                stmts.addAll(pendingMeta)
            }
            stmts.add(parseTopLevelStatement())
            skipNewlines()
        }
        return stmts
    }

    private fun isCommandDeclarationAhead(): Boolean {
        var i = pos
        while (i < tokens.size && tokens[i].type in ACCESS_MODIFIERS) i++
        return i < tokens.size && tokens[i].type == TokenType.KW_COMMAND
    }

    private fun isListenerDeclarationAhead(): Boolean {
        var i = pos
        while (i < tokens.size && tokens[i].type in ACCESS_MODIFIERS) i++
        return i < tokens.size && tokens[i].type == TokenType.KW_LISTENER
    }

    private fun buildCommandAnnotations(meta: List<Statement.Metadata>): CommandAnnotations {
        var description: String? = null
        var permission: String? = null
        var permissionMessage: String? = null
        var usage: String? = null
        var aliases = emptyList<String>()
        for (m in meta) {
            when (m.key) {
                "description" -> description = m.value
                "permission" -> permission = m.value
                "permission_message" -> permissionMessage = m.value
                "usage" -> usage = m.value
                "aliases" -> aliases = parseAliasList(m.value)
            }
        }
        return CommandAnnotations(description, permission, permissionMessage, usage, aliases)
    }

    private fun buildListenerAnnotations(meta: List<Statement.Metadata>): ListenerAnnotations {
        var priority: String? = null
        var ignoreCancelled = false
        for (m in meta) {
            when (m.key) {
                "priority" -> priority = m.value
                "ignoreCancelled" -> ignoreCancelled = m.value.trim().lowercase() == "true"
            }
        }
        return ListenerAnnotations(priority, ignoreCancelled)
    }

    private fun parseAliasList(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()
        val inner = if (trimmed.startsWith("[") && trimmed.endsWith("]"))
            trimmed.substring(1, trimmed.length - 1) else trimmed
        return inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun parseTopLevelStatement(): Statement {
        return when (peekType()) {
            TokenType.AT -> parseMetadata()
            TokenType.KW_USING -> parseUsing()
            else -> parseDeclarationOrStatement()
        }
    }

    private fun parseMetadata(): Statement.Metadata {
        val line = peek().line
        expect(TokenType.AT, "Expected '@'")
        val key = expect(TokenType.IDENTIFIER, "Expected metadata key after '@'").value
        val value = expect(
            TokenType.STRING_LITERAL,
            "Metadata '@$key' expects a string literal value",
        ).value
        if (!isAtEnd() && !check(TokenType.NEWLINE) && !check(TokenType.SEMICOLON)) {
            throw ParseException("Metadata '@$key' must contain exactly one string literal value", peek().line)
        }
        return Statement.Metadata(key, value, line)
    }

    private fun parseUsing(): Statement.Using {
        val line = peek().line
        expect(TokenType.KW_USING, "Expected 'using'")
        var relativeDots = 0
        while (check(TokenType.DOT)) {
            advance()
            relativeDots++
        }
        val path = mutableListOf<String>()
        path.add(expect(TokenType.IDENTIFIER, "Expected identifier in using path").value)
        var recursive = false
        while (check(TokenType.DOT)) {
            advance()
            if (check(TokenType.STAR)) {
                advance()
                recursive = true
                break
            }
            path.add(expect(TokenType.IDENTIFIER, "Expected identifier in using path").value)
        }
        val alias = if (check(TokenType.KW_AS)) {
            if (recursive) throw ParseException("Recursive using import does not support an alias", peek().line)
            advance()
            expect(TokenType.IDENTIFIER, "Expected alias name after 'as'").value
        } else null
        return Statement.Using(relativeDots, path, recursive, alias, line)
    }

    private fun parseManifest(line: Int): Statement.Manifest {
        expect(TokenType.KW_MANIFEST, "Expected 'manifest'")
        expect(TokenType.LBRACE, "Expected '{' after 'manifest'")
        skipNewlines()
        val entries = linkedMapOf<String, ManifestValue>()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            val key = expect(TokenType.IDENTIFIER, "Expected key in manifest").value
            expect(TokenType.EQ, "Expected '=' after key in manifest")
            entries[key] = parseManifestValue()
            skipNewlines()
            if (check(TokenType.COMMA)) { advance(); skipNewlines() }
        }
        expect(TokenType.RBRACE, "Expected '}' to close manifest")
        return Statement.Manifest(entries, line)
    }

    private fun parseManifestValue(): ManifestValue =
        when {
            check(TokenType.STRING_LITERAL) || check(TokenType.INT_LITERAL) || check(TokenType.FLOAT_LITERAL) ->
                ManifestValue.Scalar(advance().value)
            check(TokenType.LBRACKET) -> parseManifestArrayValue()
            else -> throw ParseException("Expected string, number, or array value in manifest", peek().line)
        }

    private fun parseManifestArrayValue(): ManifestValue.ListValue {
        expect(TokenType.LBRACKET, "Expected '[' to start manifest array")
        skipNewlines()
        val values = mutableListOf<String>()
        while (!check(TokenType.RBRACKET) && !isAtEnd()) {
            values += when {
                check(TokenType.STRING_LITERAL) || check(TokenType.INT_LITERAL) || check(TokenType.FLOAT_LITERAL) ->
                    advance().value
                else -> throw ParseException("Expected string or number value in manifest array", peek().line)
            }
            skipNewlines()
            if (check(TokenType.COMMA)) {
                advance()
                skipNewlines()
            } else {
                break
            }
        }
        expect(TokenType.RBRACKET, "Expected ']' to close manifest array")
        return ManifestValue.ListValue(values)
    }

    private fun parseDeclarationOrStatement(): Statement {
        val line = peek().line
        var access: AccessModifier? = null
        if (peekType() in ACCESS_MODIFIERS) {
            if (statementDepth > 0) {
                throw ParseException("Access modifier can only be used on top-level declarations", line)
            }
            access = parseAccessModifier()
        }

        return when (peekType()) {
            TokenType.KW_MANIFEST -> {
                if (statementDepth > 0) {
                    throw ParseException("Manifest can only be declared at file scope", line)
                }
                if (access != null) throw ParseException("Access modifier is not allowed on manifest declarations", line)
                parseManifest(line)
            }
            TokenType.KW_FUNCTION -> parseFunctionDecl(access ?: AccessModifier.PRIVATE, line)
            TokenType.KW_INTERVAL -> parseIntervalDecl(access ?: AccessModifier.PRIVATE, line)
            TokenType.KW_LISTENER -> parseListenerDecl(access ?: AccessModifier.PRIVATE, line)
            TokenType.KW_COMMAND -> parseCommandDecl(access ?: AccessModifier.PRIVATE, line, isRoot = true)
            TokenType.KW_CONST -> {
                advance()
                if (peekType() == TokenType.LPAREN) {
                    parseDeconstruction(
                        access = access ?: AccessModifier.PRIVATE,
                        isConst = true,
                        isDeclaration = true,
                        allowTypes = true,
                        requireTypes = false,
                        line = line,
                    )
                } else if (!isTypeKeyword(peekType())) {
                    throw ParseException("Const can only be used with variable declarations", line)
                } else {
                    parseVarDecl(access ?: AccessModifier.PRIVATE, isConst = true, line)
                }
            }
            else -> {
                if (peekType() == TokenType.KW_VAR && peekType(1) == TokenType.LPAREN) {
                    advance()
                    parseDeconstruction(
                        access = access ?: AccessModifier.PRIVATE,
                        isConst = false,
                        isDeclaration = true,
                        allowTypes = false,
                        requireTypes = false,
                        line = line,
                    )
                } else if (peekType() == TokenType.LPAREN && isDeconstructionAhead()) {
                    val isDeclaration = isTypedDeconstructionPattern()
                    if (access != null && !isDeclaration) {
                        throw ParseException("Access modifier can only be used with declarations", line)
                    }
                    parseDeconstruction(
                        access = access ?: AccessModifier.PRIVATE,
                        isConst = false,
                        isDeclaration = isDeclaration,
                        allowTypes = isDeclaration,
                        requireTypes = isDeclaration,
                        line = line,
                    )
                } else if (access != null && !isTypeKeyword(peekType())) {
                    throw ParseException("Access modifier can only be used with top-level declarations", line)
                } else if (isTypeKeyword(peekType())) {
                    parseVarDecl(access ?: AccessModifier.PRIVATE, isConst = false, line)
                } else {
                    if (access != null) {
                        throw ParseException("Access modifier can only be used with top-level declarations", line)
                    }
                    when (peekType()) {
                        TokenType.KW_IF -> parseIfStmt(line)
                        TokenType.KW_WHILE -> parseWhileStmt(line)
                        TokenType.KW_FOREACH -> parseForEachStmt(line)
                        TokenType.KW_TRY -> parseTryStmt(line)
                        TokenType.KW_RETURN -> parseReturnStmt(line)
                        TokenType.KW_BREAK -> { advance(); Statement.BreakStmt(line) }
                        TokenType.KW_CONTINUE -> { advance(); Statement.ContinueStmt(line) }
                        else -> Statement.ExprStatement(parseStatementExpression(), line)
                    }
                }
            }
        }
    }

    private fun parseAccessModifier(): AccessModifier {
        val token = advance()
        if (peekType() in ACCESS_MODIFIERS) {
            throw ParseException("Only one access modifier is allowed", peek().line)
        }
        return when (token.type) {
            TokenType.KW_PUBLIC -> AccessModifier.PUBLIC
            TokenType.KW_PRIVATE -> AccessModifier.PRIVATE
            TokenType.KW_PROTECTED -> AccessModifier.PROTECTED
            else -> throw ParseException("Expected an access modifier", token.line)
        }
    }

    private fun isTypeKeyword(type: TokenType): Boolean = type in TYPE_KEYWORDS

    private fun parseTypeRef(): TypeRef {
        val name = when (peekType()) {
            TokenType.KW_INT -> { advance(); "int" }
            TokenType.KW_FLOAT -> { advance(); "float" }
            TokenType.KW_STRING -> { advance(); "string" }
            TokenType.KW_BOOL -> { advance(); "bool" }
            TokenType.KW_OBJECT -> { advance(); "object" }
            TokenType.KW_VAR -> { advance(); "var" }
            TokenType.KW_LIST -> {
                advance()
                expect(TokenType.LT, "Expected '<' after 'list'")
                val argRef = parseTypeRef()
                expect(TokenType.GT, "Expected '>' after list type argument")
                return TypeRef("list", argRef)
            }
            TokenType.IDENTIFIER -> advance().value
            else -> throw ParseException("Expected type", peek().line)
        }
        return TypeRef(name)
    }

    private fun parseVarDecl(access: AccessModifier, isConst: Boolean, line: Int): Statement.VarDecl {
        val typeRef = parseTypeRef()
        val name = expect(TokenType.IDENTIFIER, "Expected variable name").value
        expect(TokenType.EQ, "Expected '=' after variable name")
        val init = parseExpression()
        return Statement.VarDecl(access, isConst, typeRef, name, init, line)
    }

    private fun parseFunctionDecl(access: AccessModifier, line: Int): Statement.FunctionDecl {
        expect(TokenType.KW_FUNCTION, "Expected 'function'")
        val name = expect(TokenType.IDENTIFIER, "Expected function name").value
        expect(TokenType.LPAREN, "Expected '(' after function name")
        skipNewlines()
        val params = parseParams()
        skipNewlines()
        expect(TokenType.RPAREN, "Expected ')' after parameters")
        val returnType = if (check(TokenType.COLON)) {
            advance()
            parseTypeRef()
        } else null
        val body = parseBlock()
        return Statement.FunctionDecl(access, name, params, returnType, body, line)
    }

    private fun parseParams(): List<Param> {
        val params = mutableListOf<Param>()
        skipNewlines()
        if (check(TokenType.RPAREN)) return params
        params.add(parseParam())
        skipNewlines()
        while (check(TokenType.COMMA)) {
            advance()
            skipNewlines()
            params.add(parseParam())
            skipNewlines()
        }
        return params
    }

    private fun parseParam(): Param {
        return if (isTypeKeyword(peekType())) {
            val typeRef = parseTypeRef()
            val name = expect(TokenType.IDENTIFIER, "Expected parameter name").value
            val default = if (check(TokenType.EQ)) { advance(); parseExpression() } else null
            Param(typeRef, name, default)
        } else {
            val name = expect(TokenType.IDENTIFIER, "Expected parameter name").value
            val default = if (check(TokenType.EQ)) { advance(); parseExpression() } else null
            Param(null, name, default)
        }
    }

    private fun parseCommandDecl(access: AccessModifier, line: Int, isRoot: Boolean): Statement.CommandDecl {
        val annotations = if (isRoot) pendingCommandAnnotations.also { pendingCommandAnnotations = CommandAnnotations.EMPTY }
                          else CommandAnnotations.EMPTY
        expect(TokenType.KW_COMMAND, "Expected 'command'")
        val name = expect(TokenType.IDENTIFIER, "Expected command name").value
        expect(TokenType.LPAREN, "Expected '(' after command name")
        skipNewlines()

        var senderName: String? = null
        val params = mutableListOf<Param>()

        if (!check(TokenType.RPAREN)) {
            if (isRoot && check(TokenType.KW_OBJECT)) {
                parseTypeRef()
                senderName = expect(TokenType.IDENTIFIER, "Expected sender parameter name").value
                skipNewlines()
                if (check(TokenType.COMMA)) {
                    advance()
                    skipNewlines()
                }
            }
            while (!check(TokenType.RPAREN) && !isAtEnd()) {
                params.add(parseParam())
                skipNewlines()
                if (!check(TokenType.RPAREN)) {
                    expect(TokenType.COMMA, "Expected ',' or ')' after parameter")
                    skipNewlines()
                }
            }
        }
        expect(TokenType.RPAREN, "Expected ')' after parameters")
        skipNewlines()
        expect(TokenType.LBRACE, "Expected '{' to open command body")

        val bodyItems = withNestedStatements {
            val items = mutableListOf<CommandBodyItem>()
            var defaultLine: Int? = null
            val subCommandLines = linkedMapOf<String, Int>()

            skipNewlines()
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                when {
                    check(TokenType.KW_COMMAND) -> {
                        val sub = parseCommandDecl(AccessModifier.PRIVATE, peek().line, isRoot = false)
                        val prev = subCommandLines.putIfAbsent(sub.name, sub.line)
                        if (prev != null) {
                            throw ParseException("Sub command '${sub.name}' is already declared in this command body", sub.line)
                        }
                        items.add(CommandBodyItem.SubCommand(sub))
                    }
                    check(TokenType.KW_DEFAULT) -> {
                        val currentLine = peek().line
                        advance()
                        val prev = defaultLine
                        if (prev != null) {
                            throw ParseException("Default block is already declared in this command body", currentLine)
                        }
                        defaultLine = currentLine
                        items.add(CommandBodyItem.Default(parseBlock()))
                    }
                    else -> items.add(CommandBodyItem.Code(parseDeclarationOrStatement()))
                }
                skipNewlines()
            }
            items
        }
        expect(TokenType.RBRACE, "Expected '}' to close command body")

        val hasSubOrDefault = bodyItems.any { it is CommandBodyItem.SubCommand || it is CommandBodyItem.Default }
        val finalItems: List<CommandBodyItem> = if (!hasSubOrDefault && bodyItems.isNotEmpty()) {
            listOf(CommandBodyItem.Default(bodyItems.map { (it as CommandBodyItem.Code).stmt }))
        } else {
            bodyItems
        }

        return Statement.CommandDecl(access, name, senderName, params, finalItems, annotations, line)
    }

    private fun parseIntervalDecl(access: AccessModifier, line: Int): Statement.IntervalDecl {
        expect(TokenType.KW_INTERVAL, "Expected 'interval'")
        val name = expect(TokenType.IDENTIFIER, "Expected interval name").value
        expect(TokenType.LPAREN, "Expected '(' after interval name")
        skipNewlines()
        val msToken = expect(TokenType.INT_LITERAL, "Interval period must be a positive integer literal")
        val ms = msToken.value.toIntOrNull()
            ?: throw ParseException("Interval period must be a positive integer literal", msToken.line)
        if (ms <= 0) throw ParseException("Interval period must be a positive integer literal", msToken.line)
        skipNewlines()
        expect(TokenType.RPAREN, "Expected ')' after interval ms")
        val body = parseBlock()
        return Statement.IntervalDecl(access, name, ms, body, line)
    }

    private fun parseListenerDecl(access: AccessModifier, line: Int): Statement.ListenerDecl {
        val annotations = pendingListenerAnnotations.also { pendingListenerAnnotations = ListenerAnnotations.EMPTY }
        expect(TokenType.KW_LISTENER, "Expected 'listener'")
        val eventType = expect(TokenType.IDENTIFIER, "Expected event type").value
        val name = expect(TokenType.IDENTIFIER, "Expected listener name").value
        expect(TokenType.LPAREN, "Expected '(' after listener name")
        skipNewlines()
        val sender = if (!check(TokenType.RPAREN)) {
            expect(TokenType.KW_OBJECT, "Expected 'object' before sender parameter name")
            expect(TokenType.IDENTIFIER, "Expected sender parameter name").value
        } else null
        skipNewlines()
        expect(TokenType.RPAREN, "Expected ')' after sender parameter")
        val body = parseBlock()
        return Statement.ListenerDecl(access, eventType, name, sender, body, annotations, line)
    }

    private fun parseIfStmt(line: Int): Statement.IfStmt {
        expect(TokenType.KW_IF, "Expected 'if'")
        expect(TokenType.LPAREN, "Expected '(' after 'if'")
        skipNewlines()
        val condition = parseExpression()
        skipNewlines()
        expect(TokenType.RPAREN, "Expected ')' after condition")
        val thenBody = parseBlock()
        val elseIfs = mutableListOf<Pair<Expression, List<Statement>>>()
        var elseBody: List<Statement>? = null
        skipNewlines()
        while (check(TokenType.KW_ELSE)) {
            advance()
            skipNewlines()
            if (check(TokenType.KW_IF)) {
                advance()
                expect(TokenType.LPAREN, "Expected '(' after 'else if'")
                skipNewlines()
                val cond = parseExpression()
                skipNewlines()
                expect(TokenType.RPAREN, "Expected ')' after else-if condition")
                elseIfs.add(cond to parseBlock())
                skipNewlines()
            } else {
                elseBody = parseBlock()
                break
            }
        }
        return Statement.IfStmt(condition, thenBody, elseIfs, elseBody, line)
    }

    private fun parseWhileStmt(line: Int): Statement.WhileStmt {
        expect(TokenType.KW_WHILE, "Expected 'while'")
        expect(TokenType.LPAREN, "Expected '(' after 'while'")
        skipNewlines()
        val cond = parseExpression()
        skipNewlines()
        expect(TokenType.RPAREN, "Expected ')' after condition")
        val body = parseBlock()
        return Statement.WhileStmt(cond, body, line)
    }

    private fun parseForEachStmt(line: Int): Statement.ForEachStmt {
        expect(TokenType.KW_FOREACH, "Expected 'foreach'")
        expect(TokenType.LPAREN, "Expected '(' after 'foreach'")
        skipNewlines()
        val itemType = parseOptionalForEachType()
        val item = expect(TokenType.IDENTIFIER, "Expected item variable name").value
        expect(TokenType.KW_IN, "Expected 'in' after item name")
        val iterable = parseExpression()
        skipNewlines()
        expect(TokenType.RPAREN, "Expected ')' after iterable")
        val body = parseBlock()
        return Statement.ForEachStmt(itemType, item, iterable, body, line)
    }

    private fun parseOptionalForEachType(): TypeRef? {
        if (isTypeKeyword(peekType())) {
            return parseTypeRef()
        }
        if (peekType() == TokenType.IDENTIFIER && peekType(1) == TokenType.IDENTIFIER) {
            return parseTypeRef()
        }
        return null
    }

    private fun parseReturnStmt(line: Int): Statement.ReturnStmt {
        expect(TokenType.KW_RETURN, "Expected 'return'")
        val value = if (!check(TokenType.NEWLINE) && !check(TokenType.SEMICOLON) && !check(TokenType.RBRACE) && !isAtEnd()) {
            parseExpression()
        } else null
        return Statement.ReturnStmt(value, line)
    }

    private fun parseTryStmt(line: Int): Statement.TryStmt {
        expect(TokenType.KW_TRY, "Expected 'try'")
        val tryBody = parseBlock()
        val catches = mutableListOf<CatchClause>()
        var finallyBody: List<Statement>? = null
        var fallbackCatchLine: Int? = null

        skipNewlines()
        while (check(TokenType.KW_CATCH)) {
            if (fallbackCatchLine != null) {
                throw ParseException("Catch after fallback catch is not allowed", peek().line)
            }
            val catchClause = parseCatchClause()
            if (catchClause.exceptionType == null) {
                fallbackCatchLine = catchClause.line
            }
            catches.add(catchClause)
            skipNewlines()
        }

        if (check(TokenType.KW_FINALLY)) {
            advance()
            finallyBody = parseBlock()
            skipNewlines()
        }

        if (catches.isEmpty() && finallyBody == null) {
            throw ParseException("Try statement requires at least one catch or finally block", line)
        }
        return Statement.TryStmt(tryBody, catches, finallyBody, line)
    }

    private fun parseCatchClause(): CatchClause {
        val line = peek().line
        expect(TokenType.KW_CATCH, "Expected 'catch'")
        var exceptionType: String? = null
        var variableName: String? = null

        if (check(TokenType.LPAREN)) {
            advance()
            exceptionType = expect(TokenType.IDENTIFIER, "Expected exception type in catch clause").value
            if (check(TokenType.IDENTIFIER)) {
                variableName = advance().value
            }
            expect(TokenType.RPAREN, "Expected ')' after catch clause")
        }

        val body = parseBlock()
        return CatchClause(exceptionType, variableName, body, line)
    }

    private fun parseBlock(): List<Statement> {
        skipNewlines()
        return withNestedStatements {
            if (!check(TokenType.LBRACE)) {
                return@withNestedStatements listOf(parseDeclarationOrStatement())
            }
            advance()
            val stmts = mutableListOf<Statement>()
            skipNewlines()
            while (!check(TokenType.RBRACE) && !isAtEnd()) {
                stmts.add(parseDeclarationOrStatement())
                skipNewlines()
            }
            expect(TokenType.RBRACE, "Expected '}' to close the block")
            stmts
        }
    }

    private fun parseExpression(): Expression = parseTernary()

    private fun parseStatementExpression(): Expression {
        val left = parseExpression()
        if (peekType() !in assignmentOperators()) return left
        ensureAssignmentTarget(left)
        val line = peek().line
        return when (peekType()) {
            TokenType.EQ -> {
                advance()
                Expression.Assign(left, parseExpression(), line)
            }
            TokenType.PLUS_ASSIGN, TokenType.MINUS_ASSIGN, TokenType.STAR_ASSIGN,
            TokenType.SLASH_ASSIGN, TokenType.PERCENT_ASSIGN, TokenType.STAR_STAR_ASSIGN -> {
                val op = advance()
                Expression.CompoundAssign(left, op, parseExpression(), line)
            }
            else -> left
        }
    }

    private fun ensureAssignmentTarget(expr: Expression) {
        if (expr !is Expression.Identifier &&
            expr !is Expression.MemberAccess &&
            expr !is Expression.IndexAccess
        ) {
            throw ParseException("Invalid assignment target", expr.line)
        }
        if (isThreadResultTarget(expr)) {
            throw ParseException("Cannot assign to a threaded result target", expr.line)
        }
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

    private fun assignmentOperators(): Set<TokenType> = ASSIGNMENT_OPERATORS

    private fun parseTernary(): Expression {
        val left = parseOr()
        if (check(TokenType.QUESTION)) {
            val line = peek().line
            advance()
            val thenExpr = parseExpression()
            expect(TokenType.COLON, "Expected ':' in ternary expression")
            val elseExpr = parseExpression()
            return Expression.Ternary(left, thenExpr, elseExpr, line)
        }
        return left
    }

    private fun parseOr(): Expression {
        var left = parseAnd()
        while (check(TokenType.PIPE_PIPE)) {
            val op = advance()
            left = Expression.BinaryOp(left, op, parseAnd(), op.line)
        }
        return left
    }

    private fun parseAnd(): Expression {
        var left = parseEquality()
        while (check(TokenType.AMP_AMP)) {
            val op = advance()
            left = Expression.BinaryOp(left, op, parseEquality(), op.line)
        }
        return left
    }

    private fun parseEquality(): Expression {
        var left = parseComparison()
        while (peekType() in EQUALITY_OPS) {
            val op = advance()
            left = Expression.BinaryOp(left, op, parseComparison(), op.line)
        }
        return left
    }

    private fun parseComparison(): Expression {
        var left = parseRange()
        while (peekType() in COMPARISON_OPS || peekType() == TokenType.KW_IN) {
            val op = advance()
            left = Expression.BinaryOp(left, op, parseRange(), op.line)
        }
        return left
    }

    private fun parseRange(): Expression {
        val left = parseAddSub()
        if (!isRangeOperator()) return left
        val operator = advance()
        val right = parseAddSub()
        if (isRangeOperator()) {
            throw ParseException("Range expressions cannot be chained", peek().line)
        }
        return Expression.Range(
            start = left,
            end = right,
            inclusive = operator.type == TokenType.KW_TO,
            line = operator.line,
        )
    }

    private fun isRangeOperator(): Boolean =
        peekType() == TokenType.KW_TO || peekType() == TokenType.KW_UNTIL

    private fun parseAddSub(): Expression {
        var left = parseMulDiv()
        while (peekType() in ADD_SUB_OPS) {
            val op = advance()
            left = Expression.BinaryOp(left, op, parseMulDiv(), op.line)
        }
        return left
    }

    private fun parseMulDiv(): Expression {
        var left = parsePower()
        while (peekType() in MUL_DIV_OPS) {
            val op = advance()
            left = Expression.BinaryOp(left, op, parsePower(), op.line)
        }
        return left
    }

    private fun parsePower(): Expression {
        val left = parseUnary()
        if (check(TokenType.STAR_STAR)) {
            val op = advance()
            return Expression.BinaryOp(left, op, parsePower(), op.line)
        }
        return left
    }

    private fun parseUnary(): Expression {
        if (peekType() in UNARY_OPS) {
            val op = advance()
            return Expression.UnaryOp(op, parseUnary(), prefix = true, op.line)
        }
        if (peekType() in PREFIX_INC_DEC_OPS) {
            val op = advance()
            val operand = parsePostfix()
            if (isThreadResultTarget(operand)) {
                throw ParseException("Cannot mutate a threaded result target", op.line)
            }
            return Expression.UnaryOp(op, operand, prefix = true, op.line)
        }
        return parsePostfix()
    }

    private fun parsePostfix(): Expression {
        var expr = parsePrimary()
        while (true) {
            expr = when {
                check(TokenType.DOT) -> {
                    val line = peek().line
                    advance()
                    val member = expect(TokenType.IDENTIFIER, "Expected member name after '.'").value
                    if (check(TokenType.LPAREN)) {
                        advance()
                        val args = parseArgList()
                        expect(TokenType.RPAREN, "Expected ')' after arguments")
                        Expression.Call(Expression.MemberAccess(expr, member, line), args, line)
                    } else {
                        Expression.MemberAccess(expr, member, line)
                    }
                }
                check(TokenType.LBRACKET) -> {
                    val line = peek().line
                    advance()
                    val index = parseExpression()
                    expect(TokenType.RBRACKET, "Expected ']' after index")
                    Expression.IndexAccess(expr, index, line)
                }
                check(TokenType.LPAREN) && canStartCall(expr) -> {
                    val line = peek().line
                    advance()
                    skipNewlines()
                    val args = parseArgList()
                    skipNewlines()
                    expect(TokenType.RPAREN, "Expected ')' after arguments")
                    Expression.Call(expr, args, line)
                }
                check(TokenType.PLUS_PLUS) || check(TokenType.MINUS_MINUS) -> {
                    val op = advance()
                    if (isThreadResultTarget(expr)) {
                        throw ParseException("Cannot mutate a threaded result target", op.line)
                    }
                    Expression.UnaryOp(op, expr, prefix = false, op.line)
                }
                else -> break
            }
        }
        return expr
    }

    private fun canStartCall(expr: Expression): Boolean =
        expr is Expression.Identifier ||
            expr is Expression.Call ||
            expr is Expression.MemberAccess ||
            expr is Expression.IndexAccess ||
            expr is Expression.ThreadCall

    private fun parseArgList(): List<Expression> {
        val args = mutableListOf<Expression>()
        skipNewlines()
        if (check(TokenType.RPAREN)) return args
        args.add(parseExpression())
        skipNewlines()
        while (check(TokenType.COMMA)) {
            advance()
            skipNewlines()
            args.add(parseExpression())
            skipNewlines()
        }
        return args
    }

    private fun parsePrimary(): Expression {
        val token = peek()
        return when (token.type) {
            TokenType.KW_THREAD -> parseThreadExpression()
            else -> parseBasePrimary()
        }
    }

    private fun parseBasePrimary(): Expression {
        val token = peek()
        return when (token.type) {
            TokenType.INT_LITERAL -> {
                advance()
                val intValue = token.value.toIntOrNull()
                    ?: throw ParseException("Integer literal '${token.value}' is out of range (${Int.MIN_VALUE}..${Int.MAX_VALUE})", token.line)
                Expression.IntLiteral(intValue, token.line)
            }
            TokenType.FLOAT_LITERAL -> { advance(); Expression.FloatLiteral(token.value.toDouble(), token.line) }
            TokenType.STRING_LITERAL -> { advance(); Expression.StringLiteral(token.value, token.line) }
            TokenType.INTERP_STRING -> { advance(); parseInterpolatedString(token) }
            TokenType.BOOL_LITERAL -> { advance(); Expression.BoolLiteral(token.value == "true", token.line) }
            TokenType.KW_NULL -> { advance(); Expression.NullLiteral(token.line) }
            TokenType.IDENTIFIER -> { advance(); Expression.Identifier(token.value, token.line) }
            TokenType.LPAREN -> {
                advance()
                skipNewlines()
                val expr = parseExpression()
                skipNewlines()
                expect(TokenType.RPAREN, "Expected ')'")
                expr
            }
            TokenType.LBRACKET -> parseListLiteral(token.line)
            TokenType.LBRACE -> parseObjectLiteral(token.line)
            else -> throw ParseException("Unexpected token '${token.value}'", token.line)
        }
    }

    private fun parseThreadExpression(): Expression {
        val threadToken = expect(TokenType.KW_THREAD, "Expected 'thread'")
        skipNewlines()
        if (check(TokenType.KW_THREAD)) {
            throw ParseException("Directly nested 'thread' is not allowed", peek().line)
        }
        return when (peekType()) {
            TokenType.KW_IF -> Expression.ThreadBlock(parseIfStmt(peek().line), threadToken.line)
            TokenType.KW_WHILE -> Expression.ThreadBlock(parseWhileStmt(peek().line), threadToken.line)
            TokenType.KW_FOREACH -> Expression.ThreadBlock(parseForEachStmt(peek().line), threadToken.line)
            else -> Expression.ThreadCall(parseThreadCallTarget(threadToken.line), threadToken.line)
        }
    }

    private fun parseThreadCallTarget(threadLine: Int): Expression.Call {
        var expr = parseBasePrimary()
        while (true) {
            expr = when {
                check(TokenType.DOT) -> {
                    val line = peek().line
                    advance()
                    val member = expect(TokenType.IDENTIFIER, "Expected member name after '.'").value
                    if (check(TokenType.LPAREN)) {
                        advance()
                        val args = parseArgList()
                        expect(TokenType.RPAREN, "Expected ')' after arguments")
                        return Expression.Call(Expression.MemberAccess(expr, member, line), args, line)
                    }
                    Expression.MemberAccess(expr, member, line)
                }
                check(TokenType.LBRACKET) -> {
                    val line = peek().line
                    advance()
                    val index = parseExpression()
                    expect(TokenType.RBRACKET, "Expected ']' after index")
                    Expression.IndexAccess(expr, index, line)
                }
                check(TokenType.LPAREN) && canStartCall(expr) -> {
                    val line = peek().line
                    advance()
                    skipNewlines()
                    val args = parseArgList()
                    skipNewlines()
                    expect(TokenType.RPAREN, "Expected ')' after arguments")
                    return Expression.Call(expr, args, line)
                }
                else -> throw ParseException("'thread' must target a block or a function/method call", threadLine)
            }
        }
    }

    private fun parseInterpolatedString(token: Token): Expression {
        val raw = token.value
        val parts = mutableListOf<Expression.InterpolationPart>()
        var i = 0
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                parts.add(Expression.InterpolationPart.Literal(literal.toString()))
                literal.clear()
            }
        }

        while (i < raw.length) {
            when {
                raw[i] == '{' && i + 1 < raw.length && raw[i + 1] == '{' -> {
                    literal.append('{')
                    i += 2
                }
                raw[i] == '}' && i + 1 < raw.length && raw[i + 1] == '}' -> {
                    literal.append('}')
                    i += 2
                }
                raw[i] == '{' -> {
                    flushLiteral()
                    i++
                    val exprSrc = StringBuilder()
                    var braceDepth = 0
                    var parenDepth = 0
                    var bracketDepth = 0
                    var stringQuote: Char? = null
                    var escaped = false

                    while (i < raw.length) {
                        val ch = raw[i]
                        when {
                            stringQuote != null -> {
                                exprSrc.append(ch)
                                when {
                                    escaped -> escaped = false
                                    ch == '\\' -> escaped = true
                                    ch == stringQuote -> stringQuote = null
                                }
                            }
                            ch == '"' || ch == '\'' -> {
                                stringQuote = ch
                                exprSrc.append(ch)
                            }
                            ch == '(' -> {
                                parenDepth++
                                exprSrc.append(ch)
                            }
                            ch == ')' -> {
                                if (parenDepth > 0) parenDepth--
                                exprSrc.append(ch)
                            }
                            ch == '[' -> {
                                bracketDepth++
                                exprSrc.append(ch)
                            }
                            ch == ']' -> {
                                if (bracketDepth > 0) bracketDepth--
                                exprSrc.append(ch)
                            }
                            ch == '{' -> {
                                braceDepth++
                                exprSrc.append(ch)
                            }
                            ch == '}' -> {
                                if (braceDepth == 0 && parenDepth == 0 && bracketDepth == 0) {
                                    break
                                }
                                if (braceDepth > 0) braceDepth--
                                exprSrc.append(ch)
                            }
                            else -> exprSrc.append(ch)
                        }
                        i++
                    }

                    if (i >= raw.length || raw[i] != '}') {
                        throw ParseException("Interpolation expression is not closed", token.line)
                    }

                    val innerTokens = dev.jetpack.engine.lexer.Lexer(exprSrc.toString()).tokenize()
                    val innerParser = Parser(innerTokens)
                    val innerExpr = innerParser.parseExpression()
                    innerParser.skipNewlines()
                    if (!innerParser.isAtEnd()) {
                        throw ParseException("Unexpected token in interpolation expression", token.line)
                    }
                    parts.add(Expression.InterpolationPart.Expr(innerExpr))
                    i++
                }
                raw[i] == '}' -> {
                    throw ParseException("Single '}' is not allowed in interpolated strings; use '}}' for a literal brace", token.line)
                }
                else -> {
                    literal.append(raw[i])
                    i++
                }
            }
        }
        flushLiteral()
        return Expression.InterpolatedString(parts, token.line)
    }

    private fun parseListLiteral(line: Int): Expression.ListLiteral {
        expect(TokenType.LBRACKET, "Expected '['")
        val elements = mutableListOf<Expression>()
        skipNewlines()
        while (!check(TokenType.RBRACKET) && !isAtEnd()) {
            elements.add(parseExpression())
            skipNewlines()
            if (!check(TokenType.RBRACKET)) {
                expect(TokenType.COMMA, "Expected ',' or ']' in list literal")
                skipNewlines()
            }
        }
        expect(TokenType.RBRACKET, "Expected ']' to close list literal")
        return Expression.ListLiteral(elements, line)
    }

    private fun parseObjectLiteral(line: Int): Expression.ObjectLiteral {
        expect(TokenType.LBRACE, "Expected '{'")
        val entries = mutableListOf<Pair<String, Expression>>()
        skipNewlines()
        while (!check(TokenType.RBRACE) && !isAtEnd()) {
            val key = expect(TokenType.STRING_LITERAL, "Expected string key in object literal").value
            expect(TokenType.COLON, "Expected ':' after object key")
            val value = parseExpression()
            entries.add(key to value)
            skipNewlines()
            if (!check(TokenType.RBRACE)) {
                expect(TokenType.COMMA, "Expected ',' or '}' in object literal")
                skipNewlines()
            }
        }
        expect(TokenType.RBRACE, "Expected '}' to close object literal")
        return Expression.ObjectLiteral(entries, line)
    }

    private fun isDeconstructionAhead(): Boolean {
        if (peekType() != TokenType.LPAREN) return false
        var i = pos + 1
        var depth = 0
        while (i < tokens.size) {
            when (tokens[i].type) {
                TokenType.LPAREN -> depth++
                TokenType.RPAREN -> {
                    if (depth == 0) {
                        i++
                        while (i < tokens.size && (tokens[i].type == TokenType.NEWLINE || tokens[i].type == TokenType.SEMICOLON)) i++
                        return i < tokens.size && tokens[i].type == TokenType.EQ
                    }
                    depth--
                }
                TokenType.EOF -> return false
                else -> {}
            }
            i++
        }
        return false
    }

    private fun isTypedDeconstructionPattern(): Boolean {
        if (peekType() != TokenType.LPAREN) return false
        var i = pos + 1
        while (i < tokens.size && tokens[i].type in setOf(TokenType.NEWLINE, TokenType.SEMICOLON)) i++
        return i < tokens.size && isTypeKeyword(tokens[i].type)
    }

    private fun parseDeconstruction(
        access: AccessModifier,
        isConst: Boolean,
        isDeclaration: Boolean,
        allowTypes: Boolean,
        requireTypes: Boolean,
        line: Int,
    ): Statement.Deconstruction {
        expect(TokenType.LPAREN, "Expected '('")
        skipNewlines()
        val bindings = mutableListOf<DeconstructionBinding>()
        while (!check(TokenType.RPAREN) && !isAtEnd()) {
            bindings += parseDeconstructionBinding(isDeclaration, allowTypes, requireTypes)
            skipNewlines()
            if (check(TokenType.COMMA)) {
                advance()
                skipNewlines()
            } else {
                break
            }
        }
        expect(TokenType.RPAREN, "Expected ')' to close deconstruction pattern")
        expect(TokenType.EQ, "Expected '=' after deconstruction pattern")
        val initializer = parseExpression()
        return Statement.Deconstruction(access, isConst, isDeclaration, bindings, initializer, line)
    }

    private fun parseDeconstructionBinding(
        isDeclaration: Boolean,
        allowTypes: Boolean,
        requireTypes: Boolean,
    ): DeconstructionBinding {
        if (check(TokenType.IDENTIFIER) && peek().value == "_") {
            advance()
            return DeconstructionBinding(null, null)
        }
        if (!allowTypes && isTypeKeyword(peekType())) {
            throw ParseException("Deconstruction declared with 'var' cannot specify item types", peek().line)
        }
        if (requireTypes && !isTypeKeyword(peekType())) {
            throw ParseException("Expected typed deconstruction item", peek().line)
        }
        val typeRef = if (isDeclaration && allowTypes && isTypeKeyword(peekType())) {
            parseTypeRef()
        } else null
        val name = expect(TokenType.IDENTIFIER, "Expected variable name or '_' in deconstruction pattern").value
        return DeconstructionBinding(name, typeRef)
    }
}
