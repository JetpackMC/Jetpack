package dev.jetpack.engine.lexer

class LexerException(message: String, val line: Int) : Exception(message)

class Lexer(private val source: String) {

    private var pos = 0
    private var line = 1
    private val tokens = mutableListOf<Token>()

    companion object {
        private val KEYWORDS = mapOf(
            "int" to TokenType.KW_INT,
            "float" to TokenType.KW_FLOAT,
            "string" to TokenType.KW_STRING,
            "bool" to TokenType.KW_BOOL,
            "list" to TokenType.KW_LIST,
            "object" to TokenType.KW_OBJECT,
            "var" to TokenType.KW_VAR,
            "null" to TokenType.KW_NULL,
            "true" to TokenType.BOOL_LITERAL,
            "false" to TokenType.BOOL_LITERAL,
            "function" to TokenType.KW_FUNCTION,
            "return" to TokenType.KW_RETURN,
            "const" to TokenType.KW_CONST,
            "public" to TokenType.KW_PUBLIC,
            "private" to TokenType.KW_PRIVATE,
            "protected" to TokenType.KW_PROTECTED,
            "if" to TokenType.KW_IF,
            "else" to TokenType.KW_ELSE,
            "while" to TokenType.KW_WHILE,
            "foreach" to TokenType.KW_FOREACH,
            "in" to TokenType.KW_IN,
            "break" to TokenType.KW_BREAK,
            "continue" to TokenType.KW_CONTINUE,
            "thread" to TokenType.KW_THREAD,
            "to" to TokenType.KW_TO,
            "until" to TokenType.KW_UNTIL,
            "interval" to TokenType.KW_INTERVAL,
            "listener" to TokenType.KW_LISTENER,
            "command" to TokenType.KW_COMMAND,
            "default" to TokenType.KW_DEFAULT,
            "using" to TokenType.KW_USING,
            "as" to TokenType.KW_AS,
            "manifest" to TokenType.KW_MANIFEST,
        )
    }

    fun tokenize(): List<Token> {
        while (pos < source.length) {
            skipWhitespaceAndComments()
            if (pos >= source.length) break
            val ch = source[pos]
            when {
                ch == '\n' -> {
                    tokens.add(Token(TokenType.NEWLINE, "\\n", line))
                    line++
                    pos++
                }
                ch == '@' -> { tokens.add(Token(TokenType.AT, "@", line)); pos++ }
                ch == ',' -> { tokens.add(Token(TokenType.COMMA, ",", line)); pos++ }
                ch == ';' -> { tokens.add(Token(TokenType.SEMICOLON, ";", line)); pos++ }
                ch == '.' -> { tokens.add(Token(TokenType.DOT, ".", line)); pos++ }
                ch == '(' -> { tokens.add(Token(TokenType.LPAREN, "(", line)); pos++ }
                ch == ')' -> { tokens.add(Token(TokenType.RPAREN, ")", line)); pos++ }
                ch == '{' -> { tokens.add(Token(TokenType.LBRACE, "{", line)); pos++ }
                ch == '}' -> { tokens.add(Token(TokenType.RBRACE, "}", line)); pos++ }
                ch == '[' -> { tokens.add(Token(TokenType.LBRACKET, "[", line)); pos++ }
                ch == ']' -> { tokens.add(Token(TokenType.RBRACKET, "]", line)); pos++ }
                ch == '?' -> { tokens.add(Token(TokenType.QUESTION, "?", line)); pos++ }
                ch == ':' -> { tokens.add(Token(TokenType.COLON, ":", line)); pos++ }
                ch == '+' -> lexPlus()
                ch == '-' -> lexMinus()
                ch == '*' -> lexStar()
                ch == '/' -> lexSlash()
                ch == '%' -> lexPercent()
                ch == '!' -> lexBang()
                ch == '=' -> lexEquals()
                ch == '<' -> lexLt()
                ch == '>' -> lexGt()
                ch == '&' -> lexAnd()
                ch == '|' -> lexOr()
                ch == '"' || ch == '\'' -> lexString(ch)
                ch == '$' && peek(1) == '"' -> lexInterpolatedString()
                ch.isDigit() -> lexNumber()
                ch.isLetter() || ch == '_' -> lexIdentifier()
                else -> throw LexerException("Unexpected character '${ch}'", line)
            }
        }
        tokens.add(Token(TokenType.EOF, "", line))
        return tokens
    }

    private fun peek(offset: Int = 1): Char =
        if (pos + offset < source.length) source[pos + offset] else '\u0000'

    private fun skipWhitespaceAndComments() {
        while (pos < source.length) {
            val ch = source[pos]
            when {
                ch == ' ' || ch == '\t' || ch == '\r' -> pos++
                ch == '/' && peek() == '/' -> {
                    while (pos < source.length && source[pos] != '\n') pos++
                }
                ch == '/' && peek() == '*' -> {
                    val startLine = line
                    pos += 2
                    var closed = false
                    while (pos < source.length) {
                        if (source[pos] == '\n') line++
                        if (source[pos] == '*' && peek() == '/') { pos += 2; closed = true; break }
                        pos++
                    }
                    if (!closed) throw LexerException("Unclosed block comment", startLine)
                }
                else -> return
            }
        }
    }

    private fun lexPlus() {
        when {
            peek() == '+' -> { tokens.add(Token(TokenType.PLUS_PLUS, "++", line)); pos += 2 }
            peek() == '=' -> { tokens.add(Token(TokenType.PLUS_ASSIGN, "+=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.PLUS, "+", line)); pos++ }
        }
    }

    private fun lexMinus() {
        when {
            peek() == '-' -> { tokens.add(Token(TokenType.MINUS_MINUS, "--", line)); pos += 2 }
            peek() == '=' -> { tokens.add(Token(TokenType.MINUS_ASSIGN, "-=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.MINUS, "-", line)); pos++ }
        }
    }

    private fun lexStar() {
        when {
            peek() == '*' && peek(2) == '=' -> { tokens.add(Token(TokenType.STAR_STAR_ASSIGN, "**=", line)); pos += 3 }
            peek() == '*' -> { tokens.add(Token(TokenType.STAR_STAR, "**", line)); pos += 2 }
            peek() == '=' -> { tokens.add(Token(TokenType.STAR_ASSIGN, "*=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.STAR, "*", line)); pos++ }
        }
    }

    private fun lexSlash() {
        when {
            peek() == '=' -> { tokens.add(Token(TokenType.SLASH_ASSIGN, "/=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.SLASH, "/", line)); pos++ }
        }
    }

    private fun lexPercent() {
        when {
            peek() == '=' -> { tokens.add(Token(TokenType.PERCENT_ASSIGN, "%=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.PERCENT, "%", line)); pos++ }
        }
    }

    private fun lexBang() {
        when {
            peek() == '=' -> { tokens.add(Token(TokenType.BANG_EQ, "!=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.BANG, "!", line)); pos++ }
        }
    }

    private fun lexEquals() {
        when {
            peek() == '=' -> { tokens.add(Token(TokenType.EQ_EQ, "==", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.EQ, "=", line)); pos++ }
        }
    }

    private fun lexLt() {
        when {
            peek() == '=' -> { tokens.add(Token(TokenType.LT_EQ, "<=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.LT, "<", line)); pos++ }
        }
    }

    private fun lexGt() {
        when {
            peek() == '=' -> { tokens.add(Token(TokenType.GT_EQ, ">=", line)); pos += 2 }
            else -> { tokens.add(Token(TokenType.GT, ">", line)); pos++ }
        }
    }

    private fun lexAnd() {
        if (peek() == '&') { tokens.add(Token(TokenType.AMP_AMP, "&&", line)); pos += 2 }
        else throw LexerException("Expected '&&'", line)
    }

    private fun lexOr() {
        if (peek() == '|') { tokens.add(Token(TokenType.PIPE_PIPE, "||", line)); pos += 2 }
        else throw LexerException("Expected '||'", line)
    }

    private fun lexString(quote: Char) {
        pos++
        val sb = StringBuilder()
        while (pos < source.length && source[pos] != quote) {
            if (source[pos] == '\n') throw LexerException("String is not closed", line)
            if (source[pos] == '\\') {
                pos++
                sb.append(when (source.getOrNull(pos)) {
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '\\' -> '\\'
                    '\'' -> '\''
                    '"' -> '"'
                    else -> throw LexerException("Invalid escape sequence", line)
                })
            } else {
                sb.append(source[pos])
            }
            pos++
        }
        if (pos >= source.length) throw LexerException("String is not closed", line)
        pos++
        tokens.add(Token(TokenType.STRING_LITERAL, sb.toString(), line))
    }

    private fun lexInterpolatedString() {
        pos += 2
        val sb = StringBuilder()
        var braceDepth = 0
        var stringQuote: Char? = null
        var escapedInExprString = false
        while (pos < source.length && !(source[pos] == '"' && braceDepth == 0 && stringQuote == null)) {
            if (source[pos] == '\n') {
                if (braceDepth > 0 && stringQuote == null) { line++; sb.append('\n'); pos++; continue }
                throw LexerException("String is not closed", line)
            }
            when {
                stringQuote != null -> {
                    sb.append(source[pos])
                    when {
                        escapedInExprString -> escapedInExprString = false
                        source[pos] == '\\' -> escapedInExprString = true
                        source[pos] == stringQuote -> stringQuote = null
                    }
                    pos++
                }
                braceDepth > 0 && (source[pos] == '"' || source[pos] == '\'') -> {
                    stringQuote = source[pos]
                    sb.append(source[pos])
                    pos++
                }
                braceDepth == 0 && source[pos] == '{' && peek() == '{' -> {
                    sb.append("{{")
                    pos += 2
                }
                braceDepth == 0 && source[pos] == '}' && peek() == '}' -> {
                    sb.append("}}")
                    pos += 2
                }
                source[pos] == '{' -> { braceDepth++; sb.append('{'); pos++ }
                source[pos] == '}' && braceDepth > 0 -> { braceDepth--; sb.append('}'); pos++ }
                source[pos] == '\\' && braceDepth == 0 -> {
                    pos++
                    sb.append(when (source.getOrNull(pos)) {
                        'n'  -> '\n'
                        't'  -> '\t'
                        'r'  -> '\r'
                        '\\' -> '\\'
                        '"'  -> '"'
                        else -> throw LexerException("Invalid escape sequence", line)
                    })
                    pos++
                }
                else -> { sb.append(source[pos]); pos++ }
            }
        }
        if (pos >= source.length) throw LexerException("String is not closed", line)
        pos++
        tokens.add(Token(TokenType.INTERP_STRING, sb.toString(), line))
    }

    private fun lexNumber() {
        val start = pos
        while (pos < source.length && source[pos].isDigit()) pos++
        if (pos < source.length && source[pos] == '.' && peek().isDigit()) {
            pos++
            while (pos < source.length && source[pos].isDigit()) pos++
            tokens.add(Token(TokenType.FLOAT_LITERAL, source.substring(start, pos), line))
        } else {
            tokens.add(Token(TokenType.INT_LITERAL, source.substring(start, pos), line))
        }
    }

    private fun lexIdentifier() {
        val start = pos
        while (pos < source.length && (source[pos].isLetterOrDigit() || source[pos] == '_')) pos++
        val word = source.substring(start, pos)
        val type = KEYWORDS[word] ?: TokenType.IDENTIFIER
        tokens.add(Token(type, word, line))
    }
}
