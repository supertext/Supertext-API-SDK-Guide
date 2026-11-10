// Conformance program for stand-in/conformance.mjs (see spec/conformance.json): reads one request as JSON on stdin,
// translates it with the v1 client's translateTexts (language codes passed through as given) and prints the result
// as JSON.
//
//     mvn -q package -DskipTests   (compiles this file with the tests and copies the Kotlin stdlib to target/lib)
//     node ../../stand-in/conformance.mjs -- java -cp 'target/classes:target/test-classes:target/lib/*' ConformanceKt
//
// It carries its own small JSON reader and writer: the SDK has no JSON dependency.


import com.supertext.sdk.v1.Politeness
import com.supertext.sdk.v1.Segment
import com.supertext.sdk.v1.SupertextClient
import com.supertext.sdk.v1.SupertextException
import java.io.PrintStream
import kotlin.time.Duration.Companion.milliseconds

fun main() {
    val out = PrintStream(System.out, true, "UTF-8")
    val request = JsonReader(System.`in`.readBytes().toString(Charsets.UTF_8)).parse() as Map<*, *>

    val segments = LinkedHashMap<String, Segment>()
    for (item in request["segments"] as List<*>) {
        val s = item as Map<*, *>
        segments[s["key"] as String] = Segment(s["text"] as String, s["html"] == true)
    }
    val client = SupertextClient(
        System.getenv("SUPERTEXT_API_KEY"),
        baseUrl = System.getenv("SUPERTEXT_API_URL"),
        pollInterval = 100.milliseconds,
    )
    try {
        val translations = client.translateTexts(
            segments,
            request["targetLang"] as String?,
            request["sourceLang"] as String?,
            Politeness.fromValue(request["politeness"] as String?),
        )
        out.println(translations.entries.joinToString(",", "{\"translations\":{", "}}") { quote(it.key) + ":" + quote(it.value) })
    } catch (e: SupertextException) {
        out.println("{\"error\":{\"code\":${quote(e.code.value)},\"status\":${e.status ?: "null"}}}")
    }
}

private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
    append('"')
}

/** Just enough JSON for the request: objects, arrays, strings, booleans, null, numbers. */
private class JsonReader(private val s: String) {
    private var i = 0

    fun parse(): Any? {
        skip()
        when (val c = s[i]) {
            '{' -> {
                val map = LinkedHashMap<String, Any?>()
                i++
                skip()
                if (s[i] == '}') {
                    i++
                    return map
                }
                do {
                    skip()
                    val key = parse() as String
                    skip()
                    i++ // ':'
                    map[key] = parse()
                    skip()
                } while (s[i++] == ',')
                return map
            }
            '[' -> {
                val list = ArrayList<Any?>()
                i++
                skip()
                if (s[i] == ']') {
                    i++
                    return list
                }
                do {
                    list.add(parse())
                    skip()
                } while (s[i++] == ',')
                return list
            }
            '"' -> {
                val out = StringBuilder()
                i++
                while (true) {
                    val ch = s[i++]
                    if (ch == '"') break
                    if (ch != '\\') {
                        out.append(ch)
                        continue
                    }
                    when (val e = s[i++]) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000c')
                        'u' -> {
                            out.append(s.substring(i, i + 4).toInt(16).toChar())
                            i += 4
                        }
                        else -> out.append(e)
                    }
                }
                return out.toString()
            }
            else -> {
                for (word in listOf("true", "false", "null")) {
                    if (s.startsWith(word, i)) {
                        i += word.length
                        return if (word == "null") null else word.toBoolean()
                    }
                }
                val start = i
                while (i < s.length && "-+.eE0123456789".indexOf(s[i]) >= 0) i++
                return s.substring(start, i).toDoubleOrNull() ?: error("bad JSON at $start: $c")
            }
        }
    }

    private fun skip() {
        while (i < s.length && s[i].isWhitespace()) i++
    }
}
