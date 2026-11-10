package com.supertext.sdk.v1

import java.util.regex.Pattern

/**
 * Packs segments into the HTML document sent to Supertext and reads them back.
 *
 * Each segment becomes one `<div data-st-id="N">`. Supertext translates every such element on its own, so a whole
 * paragraph must be one segment, with formatting as inline tags: a segment per formatted run breaks sentences apart.
 */
public object HtmlDocument {

    private const val HEAD = "<!DOCTYPE html>\n<html><head><meta charset=\"utf-8\"></head><body>\n"
    private const val TAIL = "</body></html>\n"

    // Start tag of any element carrying data-st-id (double, single or no quotes).
    private val START = Pattern.compile(
        """<([a-zA-Z][a-zA-Z0-9]*)(?=[\s>/])[^>]*?\sdata-st-id\s*=\s*(?:"(\d+)"|'(\d+)'|(\d+))[^>]*>""",
    )
    private val BR = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val TAG = Regex("<[^>]*>")
    private val WHITESPACE = Regex("[ \\t\\n\\r\\f]+")
    private val LINE_BREAK = Regex(" ?\u001e ?")
    private val OUTER_SPACES = Regex("^ +| +$")
    private val OUTER_WHITESPACE = Regex("^[ \\t\\n\\r\\f]+|[ \\t\\n\\r\\f]+$")
    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);")

    /** The document for these segments; ids are the positions in the list. */
    @JvmStatic
    public fun build(segments: List<Segment>): String {
        val html = StringBuilder(HEAD)
        segments.forEachIndexed { i, segment ->
            html.append("<div data-st-id=\"").append(i).append("\">").append(content(segment)).append("</div>\n")
        }
        return html.append(TAIL).toString()
    }

    /**
     * Reads the translated segments back, in the order of [segments] (only their [Segment.html] matters).
     * A segment missing from the document is `null`.
     */
    @JvmStatic
    public fun parse(html: String, segments: List<Segment>): List<String?> {
        val out = arrayOfNulls<String>(segments.size)
        scan(html, { id -> id < out.size && out[id] == null }) { id, inner ->
            out[id] = if (segments[id].html) trimOuter(inner) else plain(inner)
        }
        return out.toList()
    }

    /**
     * The inner HTML (outer whitespace trimmed) of the first element with each of these ids; ids without an element
     * are absent. Same rules as [parse] for HTML segments.
     */
    internal fun parseHtml(html: String, ids: Set<Int>): Map<Int, String> {
        val out = HashMap<Int, String>()
        scan(html, { id -> id in ids && id !in out }) { id, inner -> out[id] = trimOuter(inner) }
        return out
    }

    /** Finds the elements carrying data-st-id; [found] gets the inner HTML of those [wanted] accepts. */
    private inline fun scan(html: String, wanted: (Int) -> Boolean, found: (Int, String) -> Unit) {
        val start = START.matcher(html)
        var position = 0
        while (position <= html.length && start.find(position)) {
            val tag = start.group(1).lowercase()
            val digits = start.group(2) ?: start.group(3) ?: start.group(4)
            val contentStart = start.end()
            position = contentStart

            // Find the matching end tag by counting nested elements with the same name.
            val tags = Pattern.compile("<(/?)$tag(?=[\\s>/])[^>]*?(/?)>", Pattern.CASE_INSENSITIVE).matcher(html)
            var depth = 1
            var end = -1
            var after = -1
            var from = contentStart
            while (tags.find(from)) {
                if (tags.group(1).isNotEmpty()) {
                    depth--
                } else if (tags.group(2).isEmpty()) {
                    depth++
                }
                if (depth == 0) {
                    end = tags.start()
                    after = tags.end()
                    break
                }
                from = tags.end()
            }
            if (end < 0) continue // no closing tag: ignore this element
            val id = if (digits.length > 9) Int.MAX_VALUE else digits.toInt()
            if (wanted(id)) found(id, html.substring(contentStart, end))
            position = after
        }
    }

    private fun trimOuter(inner: String): String = OUTER_WHITESPACE.replace(inner, "")

    /** What goes between `<div data-st-id>` and `</div>` for a segment. */
    internal fun content(segment: Segment): String {
        if (segment.html) return segment.text
        val text = segment.text.replace("\r\n", "\n").replace('\r', '\n')
        val out = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                '\'' -> out.append("&#39;")
                '\n' -> out.append("<br>")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** Plain text from translated inner HTML: line breaks from `<br>`, tags removed, whitespace collapsed, entities decoded. */
    internal fun plain(inner: String): String {
        var s = BR.replace(inner, "\u001e")
        s = TAG.replace(s, "")
        s = WHITESPACE.replace(s, " ")
        s = LINE_BREAK.replace(s, "\n")
        s = OUTER_SPACES.replace(s, "")
        return decode(s)
    }

    /** Decodes the known entities (amp lt gt quot apos nbsp, numeric references to Unicode scalar values). */
    internal fun decode(s: String): String = ENTITY.replace(s) { m -> entity(m.groupValues[1], m.value) }

    private fun entity(name: String, original: String): String = when (name) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> " "
        else -> {
            val hex = name[1] == 'x' || name[1] == 'X'
            val n = name.substring(if (hex) 2 else 1).toLongOrNull(if (hex) 16 else 10)
            // Only Unicode scalar values: &#0;, surrogates and values above U+10FFFF stay as written.
            if (n != null && n > 0 && n <= 0x10FFFF && (n < 0xD800 || n > 0xDFFF)) {
                String(Character.toChars(n.toInt()))
            } else {
                original // too long to be a code point, or not a scalar value
            }
        }
    }
}
