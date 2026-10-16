package io.github.u2894638479.kotlinmcuiweb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The client script's copy of the wire format, against the Kotlin one.
 *
 * `src/main/resources/web/client.js` is a second, hand-written implementation of the same protocol:
 * the opcode table, the four message bytes, the keyframe and delta header lengths, and `strideAt`.
 * Nothing ties it to [Op] / [Encoder] at compile time, and a drift between the two is silent - the
 * browser starts misreading from one record onwards and paints fragments, while the recorder, the
 * report and the SVG writer (which only read the Kotlin side) all keep working. This test is the
 * only thing that can see that class of breakage, so it parses the shipped script and compares the
 * two implementations numerically rather than by shape.
 *
 * The script is read from the classpath, which is the same bytes the `/client.js` route serves. If it
 * is ever written in a shape this parser does not know, the parse itself fails the test and says so;
 * it never skips what it cannot read.
 */
class ClientProtocolTest {
    @Test
    fun `the opcode table in the client script matches Op`() {
        val script = clientScript()

        // The literal numbering is the protocol itself: a change on either side has to be seen here,
        // including a change that keeps the two implementations equal to each other.
        assertEquals(PROTOCOL_OPCODES, KOTLIN_OPCODES, "Op.kt 的编号不再是协议编号")
        assertEquals(PROTOCOL_OPCODES, script.opcodes, "client.js 的 Op 表与 Op.kt 不一致")
    }

    @Test
    fun `the message bytes and header lengths in the client script match Encoder`() {
        val script = clientScript()

        assertEquals(
            setOf("NOCHANGE", "KEYFRAME", "DELTA", "ASSETS"),
            script.messageBytes.keys,
            "client.js 里的首字节常量集合变了",
        )
        assertEquals(Encoder.NOCHANGE.toInt(), script.messageBytes["NOCHANGE"], "NOCHANGE 与 Encoder 不一致")
        assertEquals(Encoder.KEYFRAME.toInt(), script.messageBytes["KEYFRAME"], "KEYFRAME 与 Encoder 不一致")
        assertEquals(Encoder.DELTA.toInt(), script.messageBytes["DELTA"], "DELTA 与 Encoder 不一致")
        assertEquals(Encoder.ASSETS.toInt(), script.messageBytes["ASSETS"], "ASSETS 与 Encoder 不一致")
        assertEquals(Encoder.KEYFRAME_HEADER, script.keyframeHeader, "KEYFRAME_HEADER 与 Encoder 不一致")
        assertEquals(Encoder.DELTA_HEADER, script.deltaHeader, "DELTA_HEADER 与 Encoder 不一致")
    }

    @Test
    fun `strideAt in the client script steps every record the way Op strideAt does`() {
        val script = clientScript()

        // Each record is placed at a non-zero offset in a frame of its own, so an absolute index
        // instead of an `at`-relative one shows up as a wrong number rather than as a coincidence.
        val cases = listOf(
            strideCase("FLUSH", Op.FLUSH, 1),
            strideCase("QUAD untextured", Op.QUAD, 10, 9 to Op.NO_ASSET),
            strideCase("QUAD textured", Op.QUAD, 14, 9 to 7),
            strideCase("TRIANGLES flat", Op.TRIANGLES, 13, 1 to 3, 2 to 0),
            strideCase("TRIANGLES textured", Op.TRIANGLES, 19, 1 to 3, 2 to 1),
            strideCase("CLIP_PUSH", Op.CLIP_PUSH, 5),
            strideCase("CLIP_POP", Op.CLIP_POP, 1),
            strideCase("TRANSFORM_PUSH", Op.TRANSFORM_PUSH, 10),
            strideCase("TRANSFORM_POP", Op.TRANSFORM_POP, 1),
            strideCase("TEXT", Op.TEXT, 9),
            strideCase("BUTTON", Op.BUTTON, 7),
            strideCase("SLOT", Op.SLOT, 5),
            strideCase("CONTAINER", Op.CONTAINER, 5),
            strideCase("TOOLTIP", Op.TOOLTIP, 5),
            strideCase("DEFAULT_BACKGROUND", Op.DEFAULT_BACKGROUND, 5),
            strideCase("ITEM", Op.ITEM, 9),
            strideCase("TEXTURE", Op.TEXTURE, 8, 2 to 5),
            strideCase("TEXTURE empty", Op.TEXTURE, 3, 2 to 0),
        )

        // Every opcode has to be exercised: one added to the framework without a case here would
        // otherwise be silently untested, which is the failure this whole file exists to prevent.
        assertEquals(
            (0 until Op.COUNT).toSet(),
            cases.map { it.frame[AT] }.toSet(),
            "这个测试没有覆盖全部 opcode",
        )

        for (case in cases) {
            // The stride is protocol too, so it is pinned literally and then compared across the two
            // implementations - a pair that drifts together is still a protocol change.
            val kotlinStride = Op.strideAt(case.frame, AT)
            val clientStride = script.strideAt(case.frame, AT)
            assertEquals(case.expected, kotlinStride, "Op.kt 对 ${case.name} 的步进不是 ${case.expected}")
            assertEquals(case.expected, clientStride, "client.js 对 ${case.name} 的步进不是 ${case.expected}")
            assertEquals(kotlinStride, clientStride, "client.js 与 Op.kt 对 ${case.name} 的步进不一致")
        }
    }

    @Test
    fun `a client script the parser cannot read fails instead of passing on an empty read`() {
        val source = clientSource()

        // Three shapes the parser does not know: the table built another way, `strideAt` turned into
        // a lookup, and a header constant renamed. Any of them must be a failure, never a skip.
        assertParseFails(source.replace("const Op = {", "const Op = Object.freeze({"), "Op 表换了写法")
        assertParseFails(
            source.replace(
                "function strideAt(words, at) {",
                "function strideAt(words, at) { const table = [1, 10]; return table[words[at]];",
            ),
            "strideAt 换成了查表",
        )
        assertParseFails(source.replace("const KEYFRAME_HEADER = 9;", "const KEYFRAME_HEAD = 9;"), "头长度常量被改名")
    }

    private fun assertParseFails(source: String, what: String) {
        val failure = assertFailsWith<AssertionError>("$what 时测试必须失败") { ClientScript.parse(source) }
        assertTrue(
            failure.message?.contains(FORM_CHANGED) == true,
            "$what 时的失败信息没有说清原因: ${failure.message}",
        )
    }

    private fun strideCase(name: String, opcode: Int, expected: Int, vararg fields: Pair<Int, Int>): StrideCase {
        val frame = IntArray(64)
        frame[AT] = opcode
        for ((offset, value) in fields) frame[AT + offset] = value
        return StrideCase(name, frame, expected)
    }

    private fun clientScript(): ClientScript = ClientScript.parse(clientSource())

    /** The bytes the `/client.js` route serves, rather than the source tree next to this test. */
    private fun clientSource(): String =
        ClientProtocolTest::class.java.getResourceAsStream("/web/client.js")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: error("classpath 上没有 /web/client.js")

    private companion object {
        /** A record's offset inside a synthetic frame; non-zero so an absolute index cannot pass. */
        const val AT = 4
    }
}

private class StrideCase(val name: String, val frame: IntArray, val expected: Int)

private const val FORM_CHANGED = "client.js 的形式变了，请更新这个测试里的解析器"

/** The protocol's published opcode numbering, written down independently of both implementations. */
private val PROTOCOL_OPCODES: Map<String, Int> = linkedMapOf(
    "FLUSH" to 0,
    "QUAD" to 1,
    "TRIANGLES" to 2,
    "CLIP_PUSH" to 3,
    "CLIP_POP" to 4,
    "TRANSFORM_PUSH" to 5,
    "TRANSFORM_POP" to 6,
    "TEXT" to 7,
    "BUTTON" to 8,
    "SLOT" to 9,
    "CONTAINER" to 10,
    "TOOLTIP" to 11,
    "DEFAULT_BACKGROUND" to 12,
    "ITEM" to 13,
    "TEXTURE" to 14,
    "NO_ASSET" to -1,
)

/** The same names read out of the Kotlin side, so the comparison is against [Op] and not a copy. */
private val KOTLIN_OPCODES: Map<String, Int> = linkedMapOf(
    "FLUSH" to Op.FLUSH,
    "QUAD" to Op.QUAD,
    "TRIANGLES" to Op.TRIANGLES,
    "CLIP_PUSH" to Op.CLIP_PUSH,
    "CLIP_POP" to Op.CLIP_POP,
    "TRANSFORM_PUSH" to Op.TRANSFORM_PUSH,
    "TRANSFORM_POP" to Op.TRANSFORM_POP,
    "TEXT" to Op.TEXT,
    "BUTTON" to Op.BUTTON,
    "SLOT" to Op.SLOT,
    "CONTAINER" to Op.CONTAINER,
    "TOOLTIP" to Op.TOOLTIP,
    "DEFAULT_BACKGROUND" to Op.DEFAULT_BACKGROUND,
    "ITEM" to Op.ITEM,
    "TEXTURE" to Op.TEXTURE,
    "NO_ASSET" to Op.NO_ASSET,
)

/**
 * What the parser reads out of `client.js`: the tables, the wire constants and the stride switch.
 *
 * Everything the file cares about is pulled out through [parse], which fails rather than returning a
 * partial answer - a test that quietly reads an empty table is the same silent failure it is meant
 * to catch.
 */
private class ClientScript(
    val opcodes: Map<String, Int>,
    val messageBytes: Map<String, Int>,
    val keyframeHeader: Int,
    val deltaHeader: Int,
    private val stride: Stride,
) {
    /** What the script's own `strideAt` returns for a frame whose record starts at [at]. */
    fun strideAt(words: IntArray, at: Int): Int = stride.eval(words, at)

    companion object {
        private val MESSAGE_BYTES = listOf("NOCHANGE", "KEYFRAME", "DELTA", "ASSETS")

        fun parse(source: String): ClientScript {
            val opcodes = parseOpTable(source)
            val bytes = MESSAGE_BYTES.associateWith { constant(source, it) }
            val keyframe = constant(source, "KEYFRAME_HEADER")
            val delta = constant(source, "DELTA_HEADER")
            val body = functionBody(source, "strideAt")
            val (bindings, switch) = ScriptParser(Lexer(body).lex()).parseFunctionBody()
            return ClientScript(opcodes, bytes, keyframe, delta, Stride(bindings, switch, opcodes))
        }
    }
}

private fun formChanged(detail: String): Nothing =
    throw AssertionError("$FORM_CHANGED：$detail")

/** `const NAME = <literal>;`, where the literal must be a plain integer rather than an expression. */
private fun constant(source: String, name: String): Int {
    val match = Regex("const\\s+${Regex.escape(name)}\\s*=\\s*([^;]+);").find(source)
        ?: formChanged("找不到 `const $name = ...;`")
    return numberOf(match.groupValues[1])
}

/** The `{ ... }` body of `const Op = { ... };`, split into `NAME: <literal>` entries. */
private fun parseOpTable(source: String): Map<String, Int> {
    val declaration = source.indexOf("const Op = {")
    if (declaration < 0) formChanged("找不到 `const Op = {`")
    val open = source.indexOf('{', declaration)
    val body = stripComments(source.substring(open + 1, matchingBrace(source, open)))

    val entries = LinkedHashMap<String, Int>()
    for (raw in body.split(',')) {
        val entry = raw.trim()
        if (entry.isEmpty()) continue
        val match = Regex("^([A-Za-z_$][A-Za-z0-9_$]*)\\s*:\\s*(.+)$").find(entry)
            ?: formChanged("Op 表里有读不懂的条目 `$entry`")
        entries[match.groupValues[1]] = numberOf(match.groupValues[2])
    }
    if (entries.isEmpty()) formChanged("Op 表是空的")
    return entries
}

/** The `{ ... }` body of `function NAME(...)`, with the braces counted outside comments and strings. */
private fun functionBody(source: String, name: String): String {
    val declaration = source.indexOf("function $name(")
    if (declaration < 0) formChanged("找不到 `function $name(`")
    val open = source.indexOf('{', declaration)
    if (open < 0) formChanged("`function $name` 没有函数体")
    return source.substring(open + 1, matchingBrace(source, open))
}

private fun matchingBrace(source: String, open: Int): Int {
    var depth = 0
    var at = open
    while (at < source.length) {
        when {
            source.startsWith("//", at) -> at = source.indexOf('\n', at).let { if (it < 0) source.length else it }
            source.startsWith("/*", at) -> {
                val end = source.indexOf("*/", at)
                if (end < 0) formChanged("未闭合的块注释")
                at = end + 2
            }
            source[at] == '\'' || source[at] == '"' || source[at] == '`' -> {
                val quote = source[at]
                at += 1
                while (at < source.length && source[at] != quote) {
                    if (source[at] == '\\') at += 1
                    at += 1
                }
                if (at >= source.length) formChanged("未闭合的字符串")
                at += 1
            }
            source[at] == '{' -> {
                depth += 1
                at += 1
            }
            source[at] == '}' -> {
                depth -= 1
                if (depth == 0) return at
                at += 1
            }
            else -> at += 1
        }
    }
    formChanged("大括号没有闭合")
}

private fun stripComments(text: String): String {
    val out = StringBuilder()
    var at = 0
    while (at < text.length) {
        when {
            text.startsWith("//", at) -> {
                val end = text.indexOf('\n', at)
                at = if (end < 0) text.length else end
            }
            text.startsWith("/*", at) -> {
                val end = text.indexOf("*/", at)
                if (end < 0) formChanged("未闭合的块注释")
                at = end + 2
            }
            else -> {
                out.append(text[at])
                at += 1
            }
        }
    }
    return out.toString()
}

private fun numberOf(literal: String): Int {
    val trimmed = literal.trim()
    val negative = trimmed.startsWith("-")
    val body = (if (negative) trimmed.substring(1) else trimmed).trim()
    val value = when {
        body.startsWith("0x") || body.startsWith("0X") -> body.substring(2).toIntOrNull(16)
        else -> body.toIntOrNull()
    } ?: formChanged("不是一个整数字面量 `$literal`")
    return if (negative) -value else value
}

// ---- the `strideAt` subset ---------------------------------------------------------------------

private class Token(val text: String, val isNumber: Boolean, val isIdentifier: Boolean)

private val PUNCTUATORS = listOf(
    "!==", "===", "!=", "==", "<=", ">=", "&&", "||",
    "{", "}", "(", ")", "[", "]", ";", ":", "?", ",", "+", "-", "*", "/", ".", "<", ">", "=", "!",
)

/** Loosest first, exactly as JavaScript binds them for the operators `strideAt` uses. */
private val PRECEDENCE = listOf(
    listOf("||"),
    listOf("&&"),
    listOf("===", "!==", "==", "!="),
    listOf("+", "-"),
    listOf("*", "/"),
)

private class Lexer(private val source: String) {
    private var at = 0
    private val tokens = ArrayList<Token>()

    fun lex(): List<Token> {
        while (at < source.length) {
            val character = source[at]
            when {
                character.isWhitespace() -> at += 1
                source.startsWith("//", at) -> at = source.indexOf('\n', at).let { if (it < 0) source.length else it }
                source.startsWith("/*", at) -> {
                    val end = source.indexOf("*/", at)
                    if (end < 0) formChanged("未闭合的块注释")
                    at = end + 2
                }
                character.isDigit() -> tokens += number()
                character.isLetter() || character == '_' || character == '$' -> tokens += identifier()
                else -> {
                    val punctuator = PUNCTUATORS.firstOrNull { source.startsWith(it, at) }
                        ?: formChanged("读不懂的字符 `$character`")
                    tokens += Token(punctuator, isNumber = false, isIdentifier = false)
                    at += punctuator.length
                }
            }
        }
        return tokens
    }

    private fun number(): Token {
        val start = at
        if (source.startsWith("0x", at) || source.startsWith("0X", at)) {
            at += 2
            while (at < source.length && (source[at].isDigit() || source[at].lowercaseChar() in 'a'..'f')) at += 1
        } else {
            while (at < source.length && source[at].isDigit()) at += 1
        }
        return Token(source.substring(start, at), isNumber = true, isIdentifier = false)
    }

    private fun identifier(): Token {
        val start = at
        while (at < source.length && (source[at].isLetterOrDigit() || source[at] == '_' || source[at] == '$')) at += 1
        return Token(source.substring(start, at), isNumber = false, isIdentifier = true)
    }
}

private sealed interface Expr

private class Num(val value: Int) : Expr
private class Ref(val name: String) : Expr
private class Member(val owner: String, val name: String) : Expr
private class Index(val target: Expr, val index: Expr) : Expr
private class Unary(val operator: String, val operand: Expr) : Expr
private class Binary(val operator: String, val left: Expr, val right: Expr) : Expr
private class Ternary(val condition: Expr, val whenTrue: Expr, val whenFalse: Expr) : Expr

private sealed interface Statement

private class Bind(val name: String, val expr: Expr) : Statement
private class Return(val expr: Expr) : Statement

private class Arm(val labels: List<Expr>, val isDefault: Boolean, val statements: List<Statement>)

private class Switch(private val discriminant: Expr, private val arms: List<Arm>) {
    fun eval(frame: Frame): Int {
        val subject = frame.eval(discriminant)
        val arm = arms.firstOrNull { arm -> arm.labels.any { frame.eval(it) == subject } }
            ?: arms.firstOrNull { it.isDefault }
            ?: formChanged("strideAt 的 switch 里没有匹配的分支，也没有 default")
        for (statement in arm.statements) {
            when (statement) {
                is Bind -> frame.bound[statement.name] = frame.eval(statement.expr)
                is Return -> return frame.eval(statement.expr)
            }
        }
        formChanged("strideAt 的 switch 分支走到了末尾却没有 return")
    }
}

/** The parsed `strideAt`: its `const` bindings first, then its switch. */
private class Stride(
    private val bindings: List<Bind>,
    private val switch: Switch,
    private val opcodes: Map<String, Int>,
) {
    fun eval(words: IntArray, at: Int): Int {
        val frame = Frame(words, HashMap(), opcodes)
        frame.bound["at"] = at
        for (binding in bindings) frame.bound[binding.name] = frame.eval(binding.expr)
        return switch.eval(frame)
    }
}

/** A frame being stepped: the words, the names the expression may use, and the script's `Op` table. */
private class Frame(val words: IntArray, val bound: MutableMap<String, Int>, val opcodes: Map<String, Int>) {
    fun eval(expr: Expr): Int = when (expr) {
        is Num -> expr.value
        is Ref -> bound[expr.name] ?: formChanged("表达式引用了没有绑定的名字 `${expr.name}`")
        is Member -> {
            if (expr.owner != "Op") formChanged("只认识 `Op.x` 形式的成员，读到 `${expr.owner}.${expr.name}`")
            opcodes[expr.name] ?: formChanged("client.js 的 Op 表里没有 `${expr.name}`")
        }
        is Index -> {
            val target = expr.target
            if (target !is Ref) formChanged("只认识 `words[...]` 形式的取值")
            if (target.name != "words") formChanged("只认识 `words[...]` 形式的取值，读到 `${target.name}[...]`")
            val index = eval(expr.index)
            if (index < 0 || index >= words.size) formChanged("合成的帧不够长，words[$index] 越界")
            words[index]
        }
        is Unary -> when (expr.operator) {
            "!" -> if (truth(eval(expr.operand))) 0 else 1
            "-" -> -eval(expr.operand)
            else -> formChanged("不认识的一元运算符 `${expr.operator}`")
        }
        is Binary -> when (expr.operator) {
            "+" -> eval(expr.left) + eval(expr.right)
            "-" -> eval(expr.left) - eval(expr.right)
            "*" -> eval(expr.left) * eval(expr.right)
            "===", "==" -> if (eval(expr.left) == eval(expr.right)) 1 else 0
            "!==", "!=" -> if (eval(expr.left) != eval(expr.right)) 1 else 0
            "&&" -> if (truth(eval(expr.left)) && truth(eval(expr.right))) 1 else 0
            "||" -> if (truth(eval(expr.left)) || truth(eval(expr.right))) 1 else 0
            else -> formChanged("不认识的运算符 `${expr.operator}`")
        }
        is Ternary -> if (truth(eval(expr.condition))) eval(expr.whenTrue) else eval(expr.whenFalse)
    }

    private fun truth(value: Int): Boolean = value != 0
}

/**
 * Reads a function body written in the subset `strideAt` uses: `const` bindings and one `switch`
 * whose arms are `case` labels, optional blocks, `const` bindings and a `return`. Anything else is a
 * shape the parser does not know, and it says so rather than guessing.
 */
private class ScriptParser(private val tokens: List<Token>) {
    private var at = 0

    fun parseFunctionBody(): Pair<List<Bind>, Switch> {
        val bindings = ArrayList<Bind>()
        var switch: Switch? = null
        while (at < tokens.size) {
            when (val token = tokens[at].text) {
                "const" -> bindings += parseConst()
                "switch" -> {
                    if (switch != null) formChanged("strideAt 里出现了不止一个 switch")
                    switch = parseSwitch()
                }
                else -> formChanged("strideAt 的函数体里有读不懂的语句 `$token`")
            }
        }
        return bindings to (switch ?: formChanged("strideAt 里找不到 switch"))
    }

    private fun parseConst(): Bind {
        expect("const")
        val name = take()
        if (!name.isIdentifier) formChanged("`const` 后面不是标识符: `${name.text}`")
        expect("=")
        val value = expression()
        expect(";")
        return Bind(name.text, value)
    }

    private fun parseSwitch(): Switch {
        expect("switch")
        expect("(")
        val discriminant = expression()
        expect(")")
        expect("{")

        val arms = ArrayList<Arm>()
        while (!match("}")) {
            val labels = ArrayList<Expr>()
            var isDefault = false
            while (true) {
                if (match("case")) {
                    labels += expression()
                    expect(":")
                } else if (match("default")) {
                    isDefault = true
                    expect(":")
                } else {
                    break
                }
            }
            if (labels.isEmpty() && !isDefault) {
                formChanged("switch 里有既不是 case 也不是 default 的内容 `${peek()?.text ?: "结束"}`")
            }

            val statements = ArrayList<Statement>()
            while (true) {
                val token = peek()?.text ?: formChanged("switch 没有闭合")
                if (token == "case" || token == "default" || token == "}") break
                if (token == "{") {
                    at += 1
                    while (!match("}")) statements += parseStatement()
                } else {
                    statements += parseStatement()
                }
            }
            arms += Arm(labels, isDefault, statements)
        }
        return Switch(discriminant, arms)
    }

    private fun parseStatement(): Statement = when (peek()?.text) {
        "const" -> parseConst()
        "return" -> {
            at += 1
            val value = expression()
            expect(";")
            Return(value)
        }
        else -> formChanged("strideAt 里有读不懂的语句 `${peek()?.text ?: "结束"}`")
    }

    private fun expression(): Expr = ternary()

    private fun ternary(): Expr {
        val condition = binary(0)
        if (!match("?")) return condition
        val whenTrue = ternary()
        expect(":")
        val whenFalse = ternary()
        return Ternary(condition, whenTrue, whenFalse)
    }

    private fun binary(level: Int): Expr {
        if (level == PRECEDENCE.size) return unary()
        var left = binary(level + 1)
        while (true) {
            val operator = peek()?.text ?: break
            if (operator !in PRECEDENCE[level]) break
            at += 1
            left = Binary(operator, left, binary(level + 1))
        }
        return left
    }

    private fun unary(): Expr {
        val operator = peek()?.text
        if (operator == "!" || operator == "-") {
            at += 1
            return Unary(operator, unary())
        }
        return postfix()
    }

    private fun postfix(): Expr {
        var target = primary()
        while (match("[")) {
            val index = ternary()
            expect("]")
            target = Index(target, index)
        }
        return target
    }

    private fun primary(): Expr {
        val token = take()
        if (token.isNumber) return Num(numberOf(token.text))
        if (token.isIdentifier) {
            if (match(".")) {
                val member = take()
                if (!member.isIdentifier) formChanged("`${token.text}.` 后面不是标识符")
                return Member(token.text, member.text)
            }
            return Ref(token.text)
        }
        if (token.text == "(") {
            val inner = ternary()
            expect(")")
            return inner
        }
        formChanged("读不懂的表达式片段 `${token.text}`")
    }

    private fun peek(): Token? = tokens.getOrNull(at)

    private fun take(): Token = tokens.getOrNull(at)?.also { at += 1 } ?: formChanged("表达式意外结束")

    private fun match(text: String): Boolean {
        if (tokens.getOrNull(at)?.text != text) return false
        at += 1
        return true
    }

    private fun expect(text: String) {
        if (!match(text)) formChanged("期望 `$text`，读到 `${tokens.getOrNull(at)?.text ?: "结束"}`")
    }
}
