package org.kavach.core

/**
 * Minimal JSON reader and canonical writer, dependency-free so it runs identically on Android and
 * on the JVM unit-test host.
 *
 * Canonical form matches the Python hub exactly: sorted keys, no whitespace, UTF-8 characters kept
 * raw (ensure_ascii=False), and floats written in shortest round-trip form. Signatures are computed
 * over this form, so any drift here breaks cross-device verification. See JsonGoldenTest.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        val v = p.value()
        p.skipWs()
        require(p.atEnd()) { "trailing characters at ${p.pos}" }
        return v
    }

    fun canonical(value: Any?): String {
        val sb = StringBuilder()
        write(value, sb)
        return sb.toString()
    }

    private fun write(v: Any?, sb: StringBuilder) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Int -> sb.append(v)
            is Long -> sb.append(v)
            is Double -> sb.append(pyFloat(v))
            is String -> writeString(v, sb)
            is Map<*, *> -> {
                sb.append('{')
                val keys = v.keys.map { it as String }.sorted()
                keys.forEachIndexed { i, k ->
                    if (i > 0) sb.append(',')
                    writeString(k, sb)
                    sb.append(':')
                    write(v[k], sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    write(item, sb)
                }
                sb.append(']')
            }
            else -> throw IllegalArgumentException("unsupported type ${v::class.java.name}")
        }
    }

    /** Python repr-compatible float text for the value ranges used here (coordinates, lengths, times). */
    fun pyFloat(d: Double): String {
        require(d.isFinite()) { "non-finite float cannot be signed" }
        if (d == Math.floor(d) && Math.abs(d) < 1e16) {
            return d.toLong().toString() + ".0"
        }
        return d.toString()
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end of input" }
            return when (s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> num()
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (s[pos] == '}') { pos++; return out }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                require(s[pos] == ':') { "expected : at $pos" }
                pos++
                out[k] = value()
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or } at $pos")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (s[pos] == ']') { pos++; return out }
            while (true) {
                out.add(value())
                skipWs()
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return out }
                    else -> throw IllegalArgumentException("expected , or ] at $pos")
                }
            }
        }

        private fun str(): String {
            require(s[pos] == '"') { "expected string at $pos" }
            pos++
            val sb = StringBuilder()
            while (true) {
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun num(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            val t = s.substring(start, pos)
            require(t.isNotEmpty()) { "bad value at $start" }
            return if (t.any { it == '.' || it == 'e' || it == 'E' }) t.toDouble() else t.toLong()
        }
    }
}
