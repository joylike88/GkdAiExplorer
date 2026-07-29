/**
 * 选择器解析器 — 递归下降解析器
 * 语法参考 GKD SelectorParser.kt
 */
package com.toolbox.smartcleaner.engine.selector

/**
 * 解析错误
 */
class SelectorParseException(message: String, val position: Int) : Exception(message)

/**
 * 选择器解析器
 * 支持语法：
 * - @text='xxx' / @id='xxx' / @desc='xxx' / @class='xxx' / @name='xxx'
 * - [attr=val] [attr!=val] [attr>val] [attr>=val] [attr<val] [attr<=val]
 * - [attr^=val] [attr$=val] [attr*=val] [attr~=val] [attr!~=val]
 * - 连接符: > (child), < (parent), << (descendant), + (prev sibling), - (next sibling), -> (prev match)
 * - 逻辑: && (and), || (or), ! (not), () 分组
 * - 索引: (1), (1,3), (2n+1)
 */
class SelectorParser(private val source: CharSequence) {

    private var pos = 0

    private val len: Int get() = source.length

    private fun char(): Char? = if (pos < len) source[pos] else null

    private fun peek(): Char? = if (pos + 1 < len) source[pos + 1] else null

    private fun consume(c: Char): Boolean {
        if (char() == c) { pos++; return true }
        return false
    }

    private fun expect(c: Char) {
        if (!consume(c)) throw error("Expected '$c'")
    }

    private fun skipWs() {
        while (pos < len && source[pos].isWhitespace()) pos++
    }

    private fun error(msg: String): SelectorParseException {
        return SelectorParseException("$msg at pos $pos: '${source.subSequence(max(0, pos-10), pos+10)}'", pos)
    }

    // =========== 入口 ===========

    fun parse(): Selector {
        skipWs()
        val expr = parseSelectorExpression()
        skipWs()
        if (pos != len) throw error("Unexpected trailing chars")
        return Selector(expr)
    }

    // =========== 选择器表达式（逻辑组合） ===========

    /**
     * selectorExpr = unitSelectorExpr { ('&&' | '||') unitSelectorExpr }
     */
    private fun parseSelectorExpression(): SelectorExpression {
        var left = parseUnitSelectorExpression()
        skipWs()

        while (true) {
            if (consume('&')) {
                expect('&')
                skipWs()
                val right = parseUnitSelectorExpression()
                left = LogicalSelectorExpression(left, LogicalOperator.And, right)
                skipWs()
            } else if (consume('|')) {
                expect('|')
                skipWs()
                val right = parseUnitSelectorExpression()
                left = LogicalSelectorExpression(left, LogicalOperator.Or, right)
                skipWs()
            } else {
                break
            }
        }
        return left
    }

    /**
     * unitSelectorExpr = ['!'] propertyChain
     * propertyChain = propertySegment { connectSegment propertySegment }
     * propertySegment = ['@'] [name] '[' unit ']'
     */
    private fun parseUnitSelectorExpression(): SelectorExpression {
        val negated = consume('!')

        // parse first property segment
        val firstSeg = parsePropertySegment()
        val segments = mutableListOf<Pair<ConnectSegment?, PropertySegment>>()
        segments.add(null to firstSeg)

        skipWs()

        // parse remaining: connectSegment propertySegment
        while (pos < len) {
            val conn = parseConnectSegmentOrNull() ?: break
            skipWs()
            val propSeg = parsePropertySegment()
            segments.add(conn to propSeg)
            skipWs()
        }

        var expr: SelectorExpression = UnitSelectorExpression(segments)

        if (negated) {
            expr = NotSelectorExpression(expr)
        }

        return expr
    }

    // =========== 属性段 ===========

    /**
     * propertySegment = ['@'] [name] { '[' expression ']' }
     */
    private fun parsePropertySegment(): PropertySegment {
        val at = consume('@')
        var name = ""

        if (char() != '[') {
            name = parsePropertyName()
        }

        val units = mutableListOf<PropertyUnit>()
        while (consume('[')) {
            units.add(parsePropertyUnit())
        }

        if (units.isEmpty()) throw error("Property segment must have at least one unit [...]")

        return PropertySegment(at, name, units)
    }

    private fun parsePropertyName(): String {
        val start = pos
        // allow * as wildcard
        if (char() == '*') {
            pos++
            return "*"
        }
        if (!isIdentifierStart(char())) throw error("Invalid property name start")
        pos++
        while (pos < len && isIdentifierContinue(char())) pos++
        return source.subSequence(start, pos).toString()
    }

    private fun isIdentifierStart(c: Char?): Boolean = c?.let { it.isLetterOrDigit() || it == '_' || it == '.' } ?: false
    private fun isIdentifierContinue(c: Char?): Boolean = c?.let { it.isLetterOrDigit() || it == '_' || it == '.' } ?: false

    // =========== 属性单元（方括号内的表达式） ===========

    /**
     * propertyUnit = expression
     * expression = logicalOr
     * logicalOr = logicalAnd { '||' logicalAnd }
     * logicalAnd = primary { '&&' primary }
     * primary = '(' expression ')' | '!' primary | binaryExpr
     * binaryExpr = valueExpr compareOp valueExpr
     */
    private fun parsePropertyUnit(): PropertyUnit {
        skipWs()
        val expr = parseExpression()
        skipWs()
        expect(']')
        return PropertyUnit(expr)
    }

    private fun parseExpression(): Expression = parseLogicalOr()

    private fun parseLogicalOr(): Expression {
        var left = parseLogicalAnd()
        skipWs()
        while (consume('|')) {
            expect('|')
            skipWs()
            val right = parseLogicalAnd()
            left = LogicalExpression(left, LogicalOperator.Or, right)
            skipWs()
        }
        return left
    }

    private fun parseLogicalAnd(): Expression {
        var left = parsePrimary()
        skipWs()
        while (consume('&')) {
            expect('&')
            skipWs()
            val right = parsePrimary()
            left = LogicalExpression(left, LogicalOperator.And, right)
            skipWs()
        }
        return left
    }

    private fun parsePrimary(): Expression {
        skipWs()
        val c = char()
        return when {
            c == '(' -> {
                pos++
                val expr = parseExpression()
                skipWs()
                expect(')')
                expr
            }
            c == '!' -> {
                pos++
                NotExpression(parsePrimary())
            }
            else -> parseBinaryExpression()
        }
    }

    private fun parseBinaryExpression(): Expression {
        val left = parseValueExpression()
        skipWs()
        val op = parseCompareOperator()
        skipWs()
        val right = parseValueExpression()
        return BinaryExpression(left, op, right)
    }

    // =========== 值表达式 ===========

    /**
     * valueExpr = string | number | 'null' | 'true' | 'false' | variable
     * variable = identifier { '.' identifier } { '(' [valueExpr { ',' valueExpr }] ')' }
     */
    private fun parseValueExpression(): ValueExpression {
        skipWs()
        val c = char()
        return when {
            c == '"' || c == '\'' -> ValueExpression.StringLiteral(parseString())
            c?.isDigit() == true -> ValueExpression.IntLiteral(parseNumber())
            consume("null") -> ValueExpression.NullLiteral
            consume("true") -> ValueExpression.BooleanLiteral(true)
            consume("false") -> ValueExpression.BooleanLiteral(false)
            isIdentifierStart(c) -> parseVariable()
            else -> throw error("Expected value expression")
        }
    }

    private fun parseString(): String {
        val quote = char()!!
        pos++
        val sb = StringBuilder()
        while (pos < len) {
            val ch = source[pos]
            if (ch == quote) {
                pos++
                break
            }
            if (ch == '\\') {
                pos++
                if (pos < len) {
                    sb.append(source[pos])
                    pos++
                }
            } else {
                sb.append(ch)
                pos++
            }
        }
        return sb.toString()
    }

    private fun parseNumber(): Int {
        val start = pos
        while (pos < len && source[pos].isDigit()) pos++
        return source.subSequence(start, pos).toString().toInt()
    }

    private fun parseVariable(): ValueExpression {
        var base: ValueExpression.Variable = ValueExpression.Identifier(parsePropertyName())
        skipWs()
        while (consume('.')) {
            skipWs()
            val prop = parsePropertyName()
            base = ValueExpression.MemberExpression(base, prop)
            skipWs()
        }
        while (consume('(')) {
            skipWs()
            val args = mutableListOf<ValueExpression>()
            if (char() != ')') {
                while (true) {
                    args.add(parseValueExpression())
                    skipWs()
                    if (!consume(',')) break
                    skipWs()
                }
            }
            skipWs()
            expect(')')
            base = ValueExpression.CallExpression(base, args)
            skipWs()
        }
        return base
    }

    private fun consume(keyword: String): Boolean {
        if (source.subSequence(pos, min(pos + keyword.length, len)) == keyword) {
            pos += keyword.length
            return true
        }
        return false
    }

    // =========== 比较操作符 ===========

    private fun parseCompareOperator(): CompareOperator {
        skipWs()
        val start = pos
        // 双字符操作符优先
        val twoChar = source.subSequence(pos, min(pos + 2, len)).toString()
        return when (twoChar) {
            ">=", "<=", "!=", "^=", "$=", "*=", "~=", "!~" -> {
                pos += 2
                CompareOperator.fromSymbol(twoChar)
            }
            else -> {
                val oneChar = char()?.toString() ?: throw error("Expected operator")
                pos++
                CompareOperator.fromSymbol(oneChar)
            }
        }
    }

    // =========== 连接段 ===========

    /**
     * connectSegment = '>' | '<' | '<<' | '+' | '-' | '->' [indexExpr]
     */
    private fun parseConnectSegmentOrNull(): ConnectSegment? {
        skipWs()
        val c = char()
        return when {
            c == '>' -> {
                pos++
                if (consume('>')) ConnectSegment(ConnectOperator.Descendant, parseIndexExpr())
                else ConnectSegment(ConnectOperator.Child, parseIndexExpr())
            }
            c == '<' -> {
                pos++
                ConnectSegment(ConnectOperator.Parent, parseIndexExpr())
            }
            c == '+' -> {
                pos++
                ConnectSegment(ConnectOperator.PrevSibling, parseIndexExpr())
            }
            c == '-' -> {
                pos++
                ConnectSegment(ConnectOperator.NextSibling, parseIndexExpr())
            }
            c == '-' && peek() == '>' -> {
                pos += 2
                ConnectSegment(ConnectOperator.PrevMatch, parseIndexExpr())
            }
            else -> null
        }
    }

    /**
     * indexExpr = '(' [indices] ')'
     * indices = number | 'n' | number 'n' | number 'n' + number | ...
     * 简化：支持 (1), (1,3), (2n+1) 形式
     */
    private fun parseIndexExpr(): IndexExpression? {
        skipWs()
        if (!consume('(')) return null
        skipWs()
        val raw = StringBuilder()
        while (char() != ')') {
            if (pos >= len) throw error("Unclosed index expression")
            raw.append(char())
            pos++
        }
        pos++
        return parseIndexExpression(raw.toString())
    }

    private fun parseIndexExpression(raw: String): IndexExpression {
        // 简化实现：支持单个数字、逗号分隔、2n+1 形式
        val tokens = raw.trim().split(",").map { it.trim() }
        if (tokens.size == 1) {
            val t = tokens[0]
            if (t.contains('n')) {
                // 2n+1 形式
                val parts = t.split("n")
                val a = if (parts[0].isEmpty()) 1 else if (parts[0] == "+") 1 else if (parts[0] == "-") -1 else parts[0].toInt()
                val b = if (parts.size > 1 && parts[1].isNotEmpty()) parts[1].toInt() else 0
                return IndexExpression.Polynomial(a, b)
            }
            return IndexExpression.Single(tokens[0].toInt())
        }
        return IndexExpression.List(tokens.map { it.toInt() })
    }

    // =========== AST 节点定义 ===========

    // --- SelectorExpression ---
    sealed interface SelectorExpression {
        fun <T> match(node: T, transform: Transform<T>, option: MatchOption): T?
        fun <T> matchAll(node: T, transform: Transform<T>, option: MatchOption): Sequence<T>
        val fastQueryList: List<FastQuery>
        val isMatchRoot: Boolean
        fun isSlow(option: MatchOption): Boolean
    }

    data class UnitSelectorExpression(
        val segments: List<Pair<ConnectSegment?, PropertySegment>>
    ) : SelectorExpression {
        override fun <T> match(node: T, transform: Transform<T>, option: MatchOption): T? {
            // 从后往前匹配：最后一个 segment 是目标节点
            val last = segments.last()
            val lastProp = last.second
            val targetNode = lastProp.matchContext(node, transform, option)
            if (targetNode == null) return null

            // 验证前续 connect segments
            var current: T? = targetNode
            for ((conn, prop) in segments.reversed().drop(1)) {
                conn?.let { connect ->
                    current = connect.traverse(current!!, transform).firstOrNull { n ->
                        prop.matchContext(n, transform, option) != null
                    }
                }
                if (current == null) return null
            }
            return targetNode
        }

        override val fastQueryList: List<FastQuery> get() = segments.flatMap { it.second.fastQueryList }
        override val isMatchRoot: Boolean get() = segments.any { it.second.isMatchRoot }
        override fun isSlow(option: MatchOption): Boolean = segments.any { (conn, _) ->
            conn?.operator == ConnectOperator.Descendant && !(conn.canFastQuery && option.fastQuery)
        }
    }

    data class LogicalSelectorExpression(
        val left: SelectorExpression,
        val operator: LogicalOperator,
        val right: SelectorExpression
    ) : SelectorExpression {
        override fun <T> match(node: T, transform: Transform<T>, option: MatchOption): T? = when (operator) {
            LogicalOperator.And -> left.match(node, transform, option)?.let { right.match(it, transform, option) }
            LogicalOperator.Or -> left.match(node, transform, option) ?: right.match(node, transform, option)
        }

        override val fastQueryList: List<FastQuery> get() = left.fastQueryList + right.fastQueryList
        override val isMatchRoot: Boolean get() = left.isMatchRoot && right.isMatchRoot
        override fun isSlow(option: MatchOption): Boolean = left.isSlow(option) || right.isSlow(option)
    }

    data class NotSelectorExpression(
        val expression: SelectorExpression
    ) : SelectorExpression {
        override fun <T> match(node: T, transform: Transform<T>, option: MatchOption): T? = null // Not 不返回目标节点
        override val fastQueryList: List<FastQuery> get() = expression.fastQueryList
        override val isMatchRoot: Boolean get() = expression.isMatchRoot
        override fun isSlow(option: MatchOption): Boolean = expression.isSlow(option)
    }

    enum class LogicalOperator { And, Or }

    // --- PropertySegment ---
    data class PropertySegment(
        val at: Boolean,
        val name: String,
        val units: List<PropertyUnit>
    ) {
        val fastQueryList: List<FastQuery> get() = units.flatMap { it.fastQueries }
        val isMatchRoot: Boolean get() = units.any { it.expression is BinaryExpression && it.expression.operator == CompareOperator.Equal &&
                (it.expression.left is ValueExpression.Identifier && it.expression.left.value == "parent" && it.expression.right is ValueExpression.NullLiteral ||
                 it.expression.right is ValueExpression.Identifier && it.expression.right.value == "parent" && it.expression.left is ValueExpression.NullLiteral)
        }

        fun <T> matchContext(node: T, transform: Transform<T>, option: MatchOption): T? {
            for (unit in units) {
                val context = QueryContext(node)
                if (!unit.match(context, transform)) return null
            }
            return node
        }
    }

    data class PropertyUnit(
        val expression: Expression
    ) {
        val fastQueries: List<FastQuery> get() = if (expression is BinaryExpression) {
            val be = expression as BinaryExpression
            when (be.operator) {
                CompareOperator.Equal -> when {
                    be.left is ValueExpression.Identifier && (be.left.value == "id" || be.left.value == "vid") -> listOf(FastQuery(FastQueryType.Vid, be.right.stringValue))
                    be.left is ValueExpression.Identifier && be.left.value == "text" -> listOf(FastQuery(FastQueryType.Text, be.right.stringValue))
                    else -> emptyList()
                }
                else -> emptyList()
            }
        } else emptyList()
    }

    // --- ConnectSegment ---
    data class ConnectSegment(
        val operator: ConnectOperator,
        val indexExpr: IndexExpression = IndexExpression.Polynomial(1, 0)
    ) {
        fun <T> traverse(node: T, transform: Transform<T>): Sequence<T> = when (operator) {
            ConnectOperator.Child -> transform.traverseChildren(node, indexExpr)
            ConnectOperator.Parent -> transform.traverseAncestors(node, indexExpr)
            ConnectOperator.Descendant -> transform.traverseDescendants(node, indexExpr)
            ConnectOperator.PrevSibling -> transform.traverseBeforeBrothers(node, indexExpr)
            ConnectOperator.NextSibling -> transform.traverseAfterBrothers(node, indexExpr)
            ConnectOperator.PrevMatch -> emptySequence() // 需要上下文
        }

        val canFastQuery = operator == ConnectOperator.Descendant
    }

    enum class ConnectOperator { Child, Parent, Descendant, PrevSibling, NextSibling, PrevMatch }

    // --- Expression AST ---
    sealed interface Expression

    data class BinaryExpression(
        val left: ValueExpression,
        val operator: CompareOperator,
        val right: ValueExpression
    ) : Expression

    data class LogicalExpression(
        val left: Expression,
        val operator: LogicalOperator,
        val right: Expression
    ) : Expression

    data class NotExpression(
        val expression: Expression
    ) : Expression

    // --- ValueExpression ---
    sealed interface ValueExpression {
        val stringValue: String
            get() = when (this) {
                is StringLiteral -> value
                is IntLiteral -> value.toString()
                is BooleanLiteral -> value.toString()
                is NullLiteral -> "null"
                is Identifier -> value
                is MemberExpression -> "$object.$property"
                is CallExpression -> "$callee(${arguments.joinToString { it.stringValue }})"
            }

        data class StringLiteral(val value: String) : ValueExpression
        data class IntLiteral(val value: Int) : ValueExpression
        data class BooleanLiteral(val value: Boolean) : ValueExpression
        object NullLiteral : ValueExpression { override val stringValue = "null" }
        data class Identifier(val value: String) : ValueExpression
        data class MemberExpression(val object: ValueExpression, val property: String) : ValueExpression
        data class CallExpression(val callee: ValueExpression, val arguments: List<ValueExpression>) : ValueExpression
    }

    // --- CompareOperator ---
    enum class CompareOperator {
        Equal("="), NotEqual("!="),
        Greater(">"), GreaterEqual(">="),
        Less("<"), LessEqual("<="),
        StartsWith("^="), EndsWith("$="),
        Contains("*="), Matches("~="), NotMatches("!~");

        companion object {
            fun fromSymbol(sym: String): CompareOperator = values().firstOrNull { it.symbol == sym }
                ?: throw SelectorParseException("Unknown operator: $sym", 0)
        }

        val symbol: String = name
    }

    // --- IndexExpression ---
    sealed interface IndexExpression {
        fun checkOffset(offset: Int): Boolean
        val minOffset: Int = 0
        val maxOffset: Int? = null
    }

    object IndexExpression {
        data class Single(val index: Int) : IndexExpression {
            override fun checkOffset(offset: Int) = offset == index
            override val minOffset = index
            override val maxOffset = index
        }
        data class List(val indices: List<Int>) : IndexExpression {
            override fun checkOffset(offset: Int) = offset in indices
            override val minOffset = indices.minOrNull() ?: 0
            override val maxOffset = indices.maxOrNull()
        }
        data class Polynomial(val a: Int, val b: Int) : IndexExpression { // an + b
            override fun checkOffset(offset: Int): Boolean {
                if (a == 0) return offset == b
                return offset >= b && (offset - b) % a == 0
            }
            override val minOffset = if (a > 0) b else 0
            override val maxOffset = null
        }
    }
}