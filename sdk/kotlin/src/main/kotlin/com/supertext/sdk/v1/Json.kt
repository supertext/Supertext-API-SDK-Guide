package com.supertext.sdk.v1

/**
 * Minimal JSON reader and writer, so the SDK has no runtime dependencies. The API answers with a few flat fields
 * (`file_id`, `status`, `error_code`); this reads any valid JSON into [Map] (insertion-ordered), [List], [String],
 * [Double], [Boolean] or `null`, and writes those types (plus other [Number]s) back as compact JSON.
 */
internal class Json private constructor(private val s: String) {

    private var i = 0

    companion object {
        /** Parses a JSON text; throws [IllegalArgumentException] when it isn't valid JSON. */
        fun parse(text: String): Any? {
            val p = Json(text)
            p.skip()
            val value = p.value()
            p.skip()
            if (p.i != text.length) throw p.fail("trailing characters")
            return value
        }

        /** The string field [name] of a JSON object text, or `null` (not JSON, not an object, missing or not a string). */
        fun stringField(text: String?, name: String): String? {
            if (text == null) return null
            return try {
                (parse(text) as? Map<*, *>)?.get(name) as? String
            } catch (e: IllegalArgumentException) {
                null // Not JSON: no field.
            }
        }

        /** Compact JSON for maps (string keys), lists, strings, numbers, booleans and `null`. */
        fun write(value: Any?): String = StringBuilder().also { write(it, value) }.toString()

        private fun write(out: StringBuilder, value: Any?) {
            when (value) {
                null -> out.append("null")
                is String -> quote(out, value)
                is Boolean -> out.append(value.toString())
                is Double, is Float -> {
                    val d = (value as Number).toDouble()
                    when {
                        d.isNaN() || d.isInfinite() -> out.append("null")
                        d == Math.rint(d) && Math.abs(d) < 1e15 -> out.append(d.toLong())
                        else -> out.append(d)
                    }
                }
                is Number -> out.append(value.toString())
                is Map<*, *> -> {
                    out.append('{')
                    var first = true
                    for ((key, item) in value) {
                        if (!first) out.append(',')
                        first = false
                        quote(out, key.toString())
                        out.append(':')
                        write(out, item)
                    }
                    out.append('}')
                }
                is List<*> -> {
                    out.append('[')
                    var first = true
                    for (item in value) {
                        if (!first) out.append(',')
                        first = false
                        write(out, item)
                    }
                    out.append(']')
                }
                else -> throw IllegalArgumentException("Cannot write ${value.javaClass.name} as JSON")
            }
        }

        private fun quote(out: StringBuilder, text: String) {
            out.append('"')
            for (c in text) {
                when (c) {
                    '"' -> out.append("\\\"")
                    '\\' -> out.append("\\\\")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    '\b' -> out.append("\\b")
                    '\u000c' -> out.append("\\f")
                    else ->
                        if (c.code < 0x20 || c.code == 0x2028 || c.code == 0x2029) {
                            out.append(String.format("\\u%04x", c.code))
                        } else {
                            out.append(c)
                        }
                }
            }
            out.append('"')
        }
    }

    private fun value(): Any? {
        if (i >= s.length) throw fail("unexpected end")
        val c = s[i]
        return when (c) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c in '0'..'9') number() else throw fail("unexpected character")
        }
    }

    private fun obj(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        i++
        skip()
        if (peek() == '}') {
            i++
            return map
        }
        while (true) {
            skip()
            if (peek() != '"') throw fail("expected a key")
            val key = string()
            skip()
            expect(':')
            skip()
            map[key] = value()
            skip()
            if (peek() == ',') {
                i++
            } else {
                expect('}')
                return map
            }
        }
    }

    private fun array(): List<Any?> {
        val list = ArrayList<Any?>()
        i++
        skip()
        if (peek() == ']') {
            i++
            return list
        }
        while (true) {
            skip()
            list.add(value())
            skip()
            if (peek() == ',') {
                i++
            } else {
                expect(']')
                return list
            }
        }
    }

    private fun string(): String {
        i++ // opening quote
        val out = StringBuilder()
        while (true) {
            if (i >= s.length) throw fail("unterminated string")
            val c = s[i++]
            if (c == '"') return out.toString()
            if (c.code < 0x20) throw fail("control character in string")
            if (c != '\\') {
                out.append(c)
                continue
            }
            if (i >= s.length) throw fail("unterminated escape")
            when (val e = s[i++]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    if (i + 4 > s.length) throw fail("bad unicode escape")
                    val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw fail("bad unicode escape")
                    out.append(code.toChar())
                    i += 4
                }
                else -> throw fail("bad escape '$e'")
            }
        }
    }

    private fun number(): Double {
        val start = i
        if (peek() == '-') i++
        while (i < s.length && "0123456789.eE+-".indexOf(s[i]) >= 0) i++
        return s.substring(start, i).toDoubleOrNull() ?: throw fail("bad number")
    }

    private fun literal(word: String, value: Any?): Any? {
        if (!s.startsWith(word, i)) throw fail("unexpected literal")
        i += word.length
        return value
    }

    private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

    private fun expect(c: Char) {
        if (peek() != c) throw fail("expected '$c'")
        i++
    }

    private fun skip() {
        while (i < s.length) {
            val c = s[i]
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') return
            i++
        }
    }

    private fun fail(why: String): IllegalArgumentException = IllegalArgumentException("Invalid JSON at $i: $why")
}
