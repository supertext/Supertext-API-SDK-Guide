package com.supertext.sdk.files

import com.supertext.sdk.v1.HtmlDocument
import com.supertext.sdk.v1.Segment

/**
 * Translation files: export segments as XLIFF 1.2 or HTML, translate them outside the API (a CAT tool, a
 * translator, Supertext's web app), import them again (`spec/translation-file.json`, integration guide →
 * Translation files).
 *
 * Not tied to an API version: nothing here talks to the API. The HTML file is the same document
 * `HtmlDocument.build` makes, plus metadata in its head.
 *
 * Imported files are untrusted. [read] refuses DTDs and files over [MAX_FILE_CHARACTERS]; [importTranslations]
 * takes markup only from the integration's current source, never from the file.
 */
public object TranslationFiles {

    /** Larger files are refused by [read] (counted in UTF-16 code units, i.e. `String.length`). */
    public const val MAX_FILE_CHARACTERS: Int = 10_000_000

    private fun fail(code: TranslationFileErrorCode, message: String) = TranslationFileException(code, message)

    private fun invalidFile(what: String) = fail(TranslationFileErrorCode.INVALID_FILE, "not a well-formed XML file ($what)")

    // ------------------------------------------------------------------ characters

    /** JavaScript's whitespace (\s, trim): the reference implementation's definition. */
    private fun isSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    private fun trim(s: String): String = s.trim(::isSpace)

    private fun trimStart(s: String): String = s.trimStart(::isSpace)

    /** Only space, tab, CR, LF, FF. */
    private fun isBlank(text: String): Boolean = text.all { it == ' ' || it == '\t' || it == '\r' || it == '\n' || it == '\u000C' }

    private fun hasControl(s: String): Boolean = s.any { it <= '\u001F' || it == '\u007F' }

    private fun isXmlChar(cp: Long): Boolean =
        cp == 0x9L || cp == 0xAL || cp == 0xDL || cp in 0x20L..0xD7FFL || cp in 0xE000L..0xFFFDL || cp in 0x10000L..0x10FFFFL

    private fun normalizeLineBreaks(s: String): String = s.replace("\r\n", "\n").replace('\r', '\n')

    private fun stripBom(s: String): String = if (s.startsWith("﻿")) s.substring(1) else s

    // ------------------------------------------------------------------ checksum

    /** FNV-1a (32 bit) of the packed segment's UTF-8 bytes, 8 lowercase hex digits. Detects changed sources; not a security check. */
    @JvmStatic
    public fun checksum(segment: FileSegment): String = checksum(segment.text, segment.html)

    /** The checksum of a segment's text ([html]: an HTML fragment). */
    @JvmStatic
    public fun checksum(text: String, html: Boolean): String {
        val packed = pack(text, html)
        var hash = 0x811c9dc5.toInt()
        var i = 0
        while (i < packed.length) {
            var cp = packed.codePointAt(i)
            if (Character.isSupplementaryCodePoint(cp)) {
                i++
            } else if (Character.isSurrogate(cp.toChar())) {
                cp = 0xFFFD // a lone surrogate is encoded as U+FFFD, as TextEncoder does
            }
            for (b in utf8(cp)) hash = (hash xor b) * 0x01000193
            i++
        }
        return "%08x".format(hash)
    }

    private fun utf8(cp: Int): IntArray = when {
        cp < 0x80 -> intArrayOf(cp)
        cp < 0x800 -> intArrayOf(0xC0 or (cp shr 6), 0x80 or (cp and 0x3F))
        cp < 0x10000 -> intArrayOf(0xE0 or (cp shr 12), 0x80 or ((cp shr 6) and 0x3F), 0x80 or (cp and 0x3F))
        else -> intArrayOf(0xF0 or (cp shr 18), 0x80 or ((cp shr 12) and 0x3F), 0x80 or ((cp shr 6) and 0x3F), 0x80 or (cp and 0x3F))
    }

    /** What `HtmlDocument.build` puts between `<div data-st-id>` and `</div>` for a segment. */
    private fun pack(text: String, html: Boolean): String = HtmlDocument.content(Segment(text, html))

    // ------------------------------------------------------------------ HTML fragments: tokens and inline codes

    private enum class Kind { TEXT, START, END, COMMENT }

    private class Token(
        val kind: Kind,
        /** Text for TEXT, the raw markup otherwise. */
        val raw: String,
        /** Lower-case tag name for START and END. */
        val name: String?,
        val selfClosing: Boolean,
        /** Lower-case attribute names and values (`null`: no value). */
        val attrs: List<Pair<String, String?>>,
        val end: Int,
    )

    private val VOID = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    /** End of a tag name ([a-zA-Z][a-zA-Z0-9:-]*) starting at [i], or -1. */
    private fun tagNameEnd(s: String, i: Int): Int {
        if (i >= s.length || !isAsciiLetter(s[i])) return -1
        var j = i + 1
        while (j < s.length) {
            val c = s[j]
            if (isAsciiLetter(c) || c in '0'..'9' || c == ':' || c == '-') j++ else break
        }
        return j
    }

    /** [^\s"'>/=] */
    private fun isAttrNameChar(c: Char): Boolean = !isSpace(c) && c != '"' && c != '\'' && c != '>' && c != '/' && c != '='

    /** [^\s"'=<>`] */
    private fun isUnquotedValueChar(c: Char): Boolean =
        !isSpace(c) && c != '"' && c != '\'' && c != '=' && c != '<' && c != '>' && c != '`'

    private fun skipSpace(s: String, from: Int): Int {
        var i = from
        while (i < s.length && isSpace(s[i])) i++
        return i
    }

    /** An optional attribute value (`\s*=\s*("…"|'…'|unquoted)`) starting at [i]: (end, value), or `null` when there is none. */
    private fun attrValue(s: String, i: Int): Pair<Int, String>? {
        var m = skipSpace(s, i)
        if (m >= s.length || s[m] != '=') return null
        m = skipSpace(s, m + 1)
        if (m >= s.length) return null
        val c = s[m]
        if (c == '"' || c == '\'') {
            val close = s.indexOf(c, m + 1)
            return if (close < 0) null else Pair(close + 1, s.substring(m + 1, close))
        }
        var e = m
        while (e < s.length && isUnquotedValueChar(s[e])) e++
        return if (e == m) null else Pair(e, s.substring(m, e))
    }

    /** All attributes in [s], scanning like a global regex search: names lower-cased, value `null` when absent. */
    private fun scanAttributes(s: String): List<Pair<String, String?>> {
        val attrs = ArrayList<Pair<String, String?>>()
        var p = 0
        while (p < s.length) {
            if (!isAttrNameChar(s[p])) {
                p++
                continue
            }
            var q = p
            while (q < s.length && isAttrNameChar(s[q])) q++
            val name = s.substring(p, q).lowercase()
            val value = attrValue(s, q)
            attrs.add(Pair(name, value?.second))
            p = value?.first ?: q
        }
        return attrs
    }

    /** The comment, end tag or start tag starting at [lt] (a `<`), or `null`. */
    private fun matchTag(s: String, lt: Int): Token? {
        if (s.startsWith("<!--", lt)) {
            val end = s.indexOf("-->", lt + 4)
            return if (end < 0) null else Token(Kind.COMMENT, s.substring(lt, end + 3), null, false, emptyList(), end + 3)
        }
        if (lt + 1 < s.length && s[lt + 1] == '/') {
            val nameEnd = tagNameEnd(s, lt + 2)
            if (nameEnd < 0) return null
            val j = skipSpace(s, nameEnd)
            return if (j < s.length && s[j] == '>') {
                Token(Kind.END, s.substring(lt, j + 1), s.substring(lt + 2, nameEnd).lowercase(), false, emptyList(), j + 1)
            } else {
                null
            }
        }
        val nameEnd = tagNameEnd(s, lt + 1)
        if (nameEnd < 0) return null
        var j = nameEnd
        while (true) {
            var k = skipSpace(s, j)
            if (k == j || k >= s.length || !isAttrNameChar(s[k])) break
            while (k < s.length && isAttrNameChar(s[k])) k++
            j = attrValue(s, k)?.first ?: k
        }
        val attrsEnd = j
        j = skipSpace(s, j)
        val selfClosing = j < s.length && s[j] == '/'
        if (selfClosing) j++
        if (j >= s.length || s[j] != '>') return null
        return Token(
            Kind.START, s.substring(lt, j + 1), s.substring(lt + 1, nameEnd).lowercase(), selfClosing,
            scanAttributes(s.substring(nameEnd, attrsEnd)), j + 1,
        )
    }

    private fun tokenize(html: String): List<Token> {
        val tokens = ArrayList<Token>()
        var last = 0
        var from = 0
        while (true) {
            val lt = html.indexOf('<', from)
            if (lt < 0) break
            val t = matchTag(html, lt)
            if (t == null) {
                from = lt + 1
                continue
            }
            if (lt > last) tokens.add(Token(Kind.TEXT, html.substring(last, lt), null, false, emptyList(), lt))
            tokens.add(t)
            last = t.end
            from = t.end
        }
        if (last < html.length) tokens.add(Token(Kind.TEXT, html.substring(last), null, false, emptyList(), html.length))
        return tokens
    }

    private enum class CodeKind(val tag: String) { BPT("bpt"), EPT("ept"), PH("ph") }

    /** An inline code; [raw] is null in an XLIFF target (the file's code content is ignored). */
    private class Code(val kind: CodeKind, val id: Long, val raw: String?)

    /** An element in an XLIFF target that isn't an inline code. */
    private object InvalidCode

    /** A list of text (String, adjacent text merged) and codes. */
    private class Parts {
        private val list = ArrayList<Any>()
        private var text: StringBuilder? = null

        fun text(s: String) {
            (text ?: StringBuilder().also { text = it }).append(s)
        }

        fun add(code: Any) {
            flush()
            list.add(code)
        }

        fun done(): List<Any> {
            flush()
            return list
        }

        private fun flush() {
            text?.let { list.add(it.toString()) }
            text = null
        }
    }

    private val ENTITY = Regex("&(#[xX][0-9a-fA-F]+|#[0-9]+|[a-zA-Z][a-zA-Z0-9]*);")
    private val KNOWN_ENTITY = Regex("amp|lt|gt|quot|apos|nbsp|#[xX][0-9a-fA-F]+|#[0-9]+")

    /**
     * The inline codes of an HTML segment: a start tag with its matching end tag becomes bpt/ept, everything else
     * (void and self-closing tags, unmatched tags, comments, entities XML can't hold) a ph. Ids count from 1 in order
     * of appearance; an ept has its bpt's id. Text has the known entities decoded.
     */
    private fun segmentParts(html: String): List<Any> {
        val tokens = tokenize(html)
        val endOf = HashMap<Int, Int>()
        val startOf = HashMap<Int, Int>()
        val stack = ArrayDeque<Int>()
        tokens.forEachIndexed { i, t ->
            if (t.kind == Kind.START && !t.selfClosing && t.name !in VOID) {
                stack.addLast(i)
            } else if (t.kind == Kind.END && stack.isNotEmpty() && tokens[stack.last()].name == t.name) {
                val start = stack.removeLast()
                endOf[start] = i
                startOf[i] = start
            }
        }
        val parts = Parts()
        val ids = HashMap<Int, Long>()
        var next = 1L
        tokens.forEachIndexed { i, t ->
            when {
                t.kind == Kind.TEXT -> {
                    val text = t.raw
                    var last = 0
                    for (m in ENTITY.findAll(text)) {
                        if (m.range.first > last) parts.text(text.substring(last, m.range.first))
                        val decoded = if (KNOWN_ENTITY.matches(m.groupValues[1])) HtmlDocument.decode(m.value) else m.value
                        if (decoded != m.value && isXmlChar(decoded.codePointAt(0).toLong())) {
                            parts.text(decoded)
                        } else {
                            parts.add(Code(CodeKind.PH, next++, m.value))
                        }
                        last = m.range.last + 1
                    }
                    if (last < text.length) parts.text(text.substring(last))
                }
                t.kind == Kind.START && i in endOf -> {
                    val id = next++
                    ids[i] = id
                    parts.add(Code(CodeKind.BPT, id, t.raw))
                }
                t.kind == Kind.END && i in startOf -> parts.add(Code(CodeKind.EPT, ids.getValue(startOf.getValue(i)), t.raw))
                else -> parts.add(Code(CodeKind.PH, next++, t.raw))
            }
        }
        return parts.done()
    }

    // ------------------------------------------------------------------ export

    /** XML text: characters XML 1.0 can't hold dropped; & < > escaped, CR as &#13;. */
    private fun xmlText(s: String): String {
        val out = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) {
                out.append(c).append(s[i + 1])
                i += 2
                continue
            }
            i++
            if (Character.isSurrogate(c) || !isXmlChar(c.code.toLong())) continue
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '\r' -> out.append("&#13;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    private fun xmlAttr(s: String): String = xmlText(s).replace("\"", "&quot;")

    private fun htmlAttr(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun checkJob(job: FileJob): List<FileSegment> {
        val fields = listOf(
            "plugin" to job.plugin,
            "reference" to job.reference,
            "sourceLanguage" to job.sourceLanguage,
            "targetLanguage" to job.targetLanguage,
        )
        for ((name, value) in fields) {
            if (trim(value).isEmpty() || hasControl(value)) {
                throw fail(TranslationFileErrorCode.INVALID_JOB, "$name must be a non-empty string without control characters")
            }
        }
        val keys = HashSet<String>()
        for (segment in job.segments) {
            if (segment.key.isEmpty() || hasControl(segment.key)) {
                throw fail(TranslationFileErrorCode.INVALID_JOB, "segment keys must be non-empty strings without control characters")
            }
            if (!keys.add(segment.key)) {
                throw fail(TranslationFileErrorCode.INVALID_JOB, "duplicate segment key \"${segment.key}\"")
            }
        }
        val segments = job.segments.filter { !isBlank(it.text) }
        if (segments.isEmpty()) {
            throw fail(TranslationFileErrorCode.INVALID_JOB, "the job has nothing to translate (all segments blank)")
        }
        return segments
    }

    private fun xliffSource(segment: FileSegment): String {
        if (!segment.html) return xmlText(normalizeLineBreaks(segment.text))
        val out = StringBuilder()
        for (p in segmentParts(segment.text)) {
            if (p is String) {
                out.append(xmlText(p))
            } else {
                val c = p as Code
                out.append('<').append(c.kind.tag).append(" id=\"").append(c.id).append("\">").append(xmlText(c.raw!!))
                    .append("</").append(c.kind.tag).append('>')
            }
        }
        return out.toString()
    }

    /**
     * One XLIFF 1.2 file with one `<file>` per job. Blank segments are left out.
     *
     * @throws TranslationFileException `INVALID_JOB`: no jobs, an empty or blank metadata field or key, a control
     *   character, a duplicate key, a job with only blank segments
     */
    @JvmStatic
    public fun exportXliff(jobs: List<FileJob>): String {
        if (jobs.isEmpty()) throw fail(TranslationFileErrorCode.INVALID_JOB, "at least one job is needed")
        val out = StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<xliff version=\"1.2\" xmlns=\"urn:oasis:names:tc:xliff:document:1.2\">\n",
        )
        for (job in jobs) {
            val segments = checkJob(job)
            out.append("  <file original=\"").append(xmlAttr(job.reference))
                .append("\" source-language=\"").append(xmlAttr(job.sourceLanguage))
                .append("\" target-language=\"").append(xmlAttr(job.targetLanguage))
                .append("\" datatype=\"html\" product-name=\"").append(xmlAttr(job.plugin))
                .append("\" tool-id=\"supertext-sdk\">\n")
                .append("    <header>\n      <tool tool-id=\"supertext-sdk\" tool-name=\"Supertext SDK\"/>\n    </header>\n    <body>\n")
            for (segment in segments) {
                out.append("      <trans-unit id=\"").append(xmlAttr(segment.key)).append('"')
                    .append(if (segment.html) "" else " datatype=\"plaintext\"")
                    .append(" extradata=\"").append(checksum(segment)).append("\" xml:space=\"preserve\">\n")
                    .append("        <source>").append(xliffSource(segment)).append("</source>\n      </trans-unit>\n")
            }
            out.append("    </body>\n  </file>\n")
        }
        return out.append("</xliff>\n").toString()
    }

    /** [exportXliff] for one job. */
    @JvmStatic
    public fun exportXliff(job: FileJob): String = exportXliff(listOf(job))

    /**
     * The HTML document for one job (what `HtmlDocument.build` makes) with the job's metadata in its head. Blank
     * segments are left out.
     *
     * @throws TranslationFileException `INVALID_JOB`, as for [exportXliff]
     */
    @JvmStatic
    public fun exportHtml(job: FileJob): String {
        val segments = checkJob(job)
        val out = StringBuilder("<!DOCTYPE html>\n<html lang=\"").append(htmlAttr(job.sourceLanguage))
            .append("\"><head><meta charset=\"utf-8\">\n")
            .append("<meta name=\"supertext:plugin\" content=\"").append(htmlAttr(job.plugin)).append("\">\n")
            .append("<meta name=\"supertext:reference\" content=\"").append(htmlAttr(job.reference)).append("\">\n")
            .append("<meta name=\"supertext:source-language\" content=\"").append(htmlAttr(job.sourceLanguage)).append("\">\n")
            .append("<meta name=\"supertext:target-language\" content=\"").append(htmlAttr(job.targetLanguage)).append("\">\n")
        segments.forEachIndexed { i, segment ->
            out.append("<meta name=\"supertext:segment\" content=\"").append(i).append(' ').append(checksum(segment)).append(' ')
                .append(htmlAttr(segment.key)).append("\">\n")
        }
        out.append("</head><body>\n")
        segments.forEachIndexed { i, segment ->
            out.append("<div data-st-id=\"").append(i).append("\">").append(pack(segment.text, segment.html)).append("</div>\n")
        }
        return out.append("</body></html>\n").toString()
    }

    // ------------------------------------------------------------------ reading files

    private class XElement(val name: String) {
        val attrs = HashMap<String, String>()
        val children = ArrayList<Any>()
    }

    /** XML name ([A-Za-z_:][-A-Za-z0-9_:.]*) at [i], searched only up to [limit]; `null` if none. */
    private fun xmlName(s: String, i: Int, limit: Int): String? {
        val end = minOf(limit, s.length)
        if (i >= end) return null
        val first = s[i]
        if (!(isAsciiLetter(first) || first == '_' || first == ':')) return null
        var j = i + 1
        while (j < end) {
            val c = s[j]
            if (isAsciiLetter(c) || c in '0'..'9' || c == '-' || c == '_' || c == ':' || c == '.') j++ else break
        }
        return s.substring(i, j)
    }

    private val XML_REF = Regex("&(?:#x([0-9a-fA-F]+)|#([0-9]+)|(amp|lt|gt|quot|apos));")

    private fun localName(name: String): String = name.substring(name.lastIndexOf(':') + 1)

    private fun decodeXml(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val amp = text.indexOf('&', i)
            if (amp < 0) return out.append(text, i, text.length).toString()
            out.append(text, i, amp)
            val window = text.substring(amp, minOf(amp + 12, text.length))
            val m = XML_REF.matchAt(window, 0) ?: throw invalidFile("unknown entity")
            val named = m.groups[3]?.value
            if (named != null) {
                out.append(
                    when (named) {
                        "amp" -> '&'
                        "lt" -> '<'
                        "gt" -> '>'
                        "quot" -> '"'
                        else -> '\''
                    },
                )
            } else {
                val hex = m.groups[1]?.value
                // At most 8 digits fit in the 12-character window, so these always parse.
                val cp = hex?.toLong(16) ?: m.groupValues[2].toLong()
                if (!isXmlChar(cp)) throw invalidFile("character reference")
                out.appendCodePoint(cp.toInt())
            }
            i = amp + m.range.last + 1
        }
        return out.toString()
    }

    /** A small XML parser: elements, attributes, text, CDATA; comments and processing instructions skipped; no DTD. */
    private fun parseXml(source: String): XElement {
        val s = normalizeLineBreaks(stripBom(source))
        var i = 0
        var root: XElement? = null
        val stack = ArrayDeque<XElement>()
        while (i < s.length) {
            val lt = s.indexOf('<', i)
            val textEnd = if (lt < 0) s.length else lt
            if (textEnd > i) {
                val text = s.substring(i, textEnd)
                if (stack.isNotEmpty()) {
                    stack.last().children.add(decodeXml(text))
                } else if (!isBlank(text)) {
                    throw invalidFile("text outside the root element")
                }
                if (text.contains("]]>")) throw invalidFile("]]> in text")
            }
            if (lt < 0) break
            when {
                s.startsWith("<!--", lt) -> {
                    val end = s.indexOf("-->", lt + 4)
                    if (end < 0) throw invalidFile("unclosed comment")
                    i = end + 3
                }
                s.startsWith("<![CDATA[", lt) -> {
                    val end = s.indexOf("]]>", lt + 9)
                    if (end < 0 || stack.isEmpty()) throw invalidFile("CDATA")
                    stack.last().children.add(s.substring(lt + 9, end))
                    i = end + 3
                }
                s.startsWith("<?", lt) -> {
                    val end = s.indexOf("?>", lt + 2)
                    if (end < 0) throw invalidFile("unclosed processing instruction")
                    i = end + 2
                }
                s.startsWith("<!", lt) -> throw invalidFile("declarations are not allowed")
                s.startsWith("</", lt) -> {
                    val name = xmlName(s, lt + 2, s.length)
                    val end = s.indexOf('>', lt)
                    if (name == null || end < 0 || end < lt + 2 + name.length || trim(s.substring(lt + 2 + name.length, end)).isNotEmpty()) {
                        throw invalidFile("end tag")
                    }
                    val open = stack.removeLastOrNull()
                    if (open == null || open.name != name) throw invalidFile("mismatched end tag")
                    i = end + 1
                }
                else -> {
                    val name = xmlName(s, lt + 1, s.length) ?: throw invalidFile("start tag")
                    var j = lt + 1 + name.length
                    val element = XElement(name)
                    val seen = HashSet<String>()
                    while (true) {
                        var ws = 0
                        while (ws < 200 && j + ws < s.length && isSpace(s[j + ws])) ws++
                        j += ws
                        if (s.startsWith("/>", j) || (j < s.length && s[j] == '>')) break
                        if (ws == 0) throw invalidFile("attribute")
                        val attr = xmlName(s, j, j + 200) ?: throw invalidFile("attribute")
                        j += attr.length
                        // \s*=\s*(["']), within the next 200 characters
                        val limit = minOf(j + 200, s.length)
                        var k = j
                        while (k < limit && isSpace(s[k])) k++
                        if (k >= limit || s[k] != '=') throw invalidFile("attribute value")
                        k++
                        while (k < limit && isSpace(s[k])) k++
                        if (k >= limit || (s[k] != '"' && s[k] != '\'')) throw invalidFile("attribute value")
                        val quote = s[k]
                        j = k + 1
                        val close = s.indexOf(quote, j)
                        if (close < 0) throw invalidFile("attribute value")
                        val raw = s.substring(j, close)
                        if (raw.indexOf('<') >= 0) throw invalidFile("< in attribute value")
                        if (!seen.add(attr)) throw invalidFile("duplicate attribute")
                        element.attrs[localName(attr)] = decodeXml(raw.replace('\t', ' ').replace('\n', ' '))
                        j = close + 1
                    }
                    val selfClosing = s[j] == '/'
                    i = j + if (selfClosing) 2 else 1
                    when {
                        stack.isNotEmpty() -> stack.last().children.add(element)
                        root != null -> throw invalidFile("more than one root element")
                        else -> root = element
                    }
                    if (!selfClosing) stack.addLast(element)
                }
            }
        }
        if (root == null || stack.isNotEmpty()) throw invalidFile("unclosed element")
        return root
    }

    /** A unit's key, checksum and translation: XLIFF target parts or HTML inner HTML (either `null`: none). */
    internal class UnitData(val key: String, val checksum: String?, val xliffTarget: List<Any>?, val htmlTarget: String?)

    private val CHECKSUM = Regex("[0-9a-f]{8}")
    private val CODE_ID = Regex("[1-9][0-9]{0,8}")

    private fun isElement(node: Any, name: String): Boolean = node is XElement && localName(node.name) == name

    /** The target's text and codes; `<mrk>` unwrapped (iteratively: nesting depth is the file's choice). */
    private fun targetParts(nodes: List<Any>, out: Parts) {
        val stack = ArrayDeque<Iterator<Any>>()
        stack.addLast(nodes.iterator())
        while (stack.isNotEmpty()) {
            val it = stack.last()
            if (!it.hasNext()) {
                stack.removeLast()
                continue
            }
            val node = it.next()
            if (node is String) {
                out.text(node)
                continue
            }
            val e = node as XElement
            val name = localName(e.name)
            val id = e.attrs["id"] ?: ""
            if (name == "mrk") {
                stack.addLast(e.children.iterator())
            } else if ((name == "bpt" || name == "ept" || name == "ph") && CODE_ID.matches(id)) {
                val kind = when (name) {
                    "bpt" -> CodeKind.BPT
                    "ept" -> CodeKind.EPT
                    else -> CodeKind.PH
                }
                out.add(Code(kind, id.toLong(), null))
            } else {
                out.add(InvalidCode)
            }
        }
    }

    /** Every trans-unit in document order, also inside groups (iteratively); the first unit wins for an id. */
    private fun walkUnits(nodes: List<Any>, units: MutableMap<String, UnitData>) {
        val stack = ArrayDeque<Iterator<Any>>()
        stack.addLast(nodes.iterator())
        while (stack.isNotEmpty()) {
            val it = stack.last()
            if (!it.hasNext()) {
                stack.removeLast()
                continue
            }
            val node = it.next()
            if (isElement(node, "group")) {
                stack.addLast((node as XElement).children.iterator())
            } else if (isElement(node, "trans-unit")) {
                val unit = node as XElement
                val key = unit.attrs["id"]
                if (key.isNullOrEmpty() || key in units) continue
                val target = unit.children.firstOrNull { isElement(it, "target") }?.let {
                    Parts().also { parts -> targetParts((it as XElement).children, parts) }.done()
                }
                val extradata = unit.attrs["extradata"] ?: ""
                units[key] = UnitData(key, if (CHECKSUM.matches(extradata)) extradata else null, target, null)
            }
        }
    }

    private fun readXliff(content: String): List<TranslationFile> {
        val root = parseXml(content)
        if (localName(root.name) != "xliff") throw fail(TranslationFileErrorCode.INVALID_FILE, "not an XLIFF file")
        val files = ArrayList<TranslationFile>()
        for (node in root.children) {
            if (!isElement(node, "file")) continue
            val file = node as XElement
            val original = file.attrs["original"]
            val sourceLanguage = file.attrs["source-language"]
            val targetLanguage = file.attrs["target-language"]
            val plugin = file.attrs["product-name"]
            if (original.isNullOrEmpty() || sourceLanguage.isNullOrEmpty() || targetLanguage.isNullOrEmpty() || plugin.isNullOrEmpty()) {
                throw fail(
                    TranslationFileErrorCode.MISSING_METADATA,
                    "the <file> element lacks original, source-language, target-language or product-name",
                )
            }
            val units = LinkedHashMap<String, UnitData>()
            for (body in file.children) {
                if (isElement(body, "body")) walkUnits((body as XElement).children, units)
            }
            files.add(register(FileFormat.XLIFF, plugin, original, sourceLanguage, targetLanguage, units))
        }
        if (files.isEmpty()) throw fail(TranslationFileErrorCode.INVALID_FILE, "the XLIFF file has no <file> element")
        return files
    }

    private val SEGMENT_META = Regex("([0-9]{1,9}) ([0-9a-f]{8}) ([\\s\\S]+)")

    /** The attribute strings of all `<meta ...>` tags (as /<meta\b((?:[^>"']|"[^"]*"|'[^']*')*)>/gi finds them). */
    private fun metaTags(content: String): List<String> {
        val metas = ArrayList<String>()
        var from = 0
        while (from < content.length) {
            val lt = content.indexOf('<', from)
            if (lt < 0) break
            from = lt + 1
            if (!content.regionMatches(lt + 1, "meta", 0, 4, ignoreCase = true)) continue
            var j = lt + 5
            if (j < content.length) {
                val c = content[j]
                if (isAsciiLetter(c) || c in '0'..'9' || c == '_') continue // \b
            }
            val start = j
            var end = -1
            while (j < content.length) {
                val c = content[j]
                if (c == '>') {
                    end = j
                    break
                }
                if (c == '"' || c == '\'') {
                    val close = content.indexOf(c, j + 1)
                    if (close < 0) break
                    j = close + 1
                } else {
                    j++
                }
            }
            if (end >= 0) {
                metas.add(content.substring(start, end))
                from = end + 1
            }
        }
        return metas
    }

    private class SegmentMeta(val position: Int, val checksum: String, val key: String)

    private fun readHtml(content: String): TranslationFile {
        val meta = HashMap<String, String>()
        val segments = ArrayList<SegmentMeta>()
        for (tag in metaTags(content)) {
            val attrs = HashMap<String, String>()
            for ((name, value) in scanAttributes(tag)) attrs.putIfAbsent(name, HtmlDocument.decode(value ?: ""))
            val name = (attrs["name"] ?: "").lowercase()
            val value = attrs["content"]
            if (!name.startsWith("supertext:") || value == null) continue
            if (name == "supertext:segment") {
                SEGMENT_META.matchEntire(value)?.let {
                    segments.add(SegmentMeta(it.groupValues[1].toInt(), it.groupValues[2], it.groupValues[3]))
                }
            } else {
                meta.putIfAbsent(name, value)
            }
        }
        val plugin = meta["supertext:plugin"]
        val reference = meta["supertext:reference"]
        val sourceLanguage = meta["supertext:source-language"]
        val targetLanguage = meta["supertext:target-language"]
        if (plugin.isNullOrEmpty() || reference.isNullOrEmpty() || sourceLanguage.isNullOrEmpty() ||
            targetLanguage.isNullOrEmpty() || segments.isEmpty()
        ) {
            throw fail(TranslationFileErrorCode.MISSING_METADATA, "the HTML file lacks the supertext:* metadata in its head")
        }
        val inner = HtmlDocument.parseHtml(content, segments.mapTo(HashSet()) { it.position })
        val units = LinkedHashMap<String, UnitData>()
        val positions = HashSet<Int>()
        for (s in segments.sortedBy { it.position }) {
            if (s.key in units || s.position in positions) continue
            positions.add(s.position)
            units[s.key] = UnitData(s.key, s.checksum, null, inner[s.position])
        }
        return register(FileFormat.HTML, plugin, reference, sourceLanguage, targetLanguage, units)
    }

    private fun register(
        format: FileFormat,
        plugin: String,
        reference: String,
        sourceLanguage: String,
        targetLanguage: String,
        units: Map<String, UnitData>,
    ): TranslationFile = TranslationFile(
        format, plugin, reference, sourceLanguage, targetLanguage,
        units.values.map { FileUnit(it.key, it.checksum) }, units,
    )

    /**
     * Reads an exported file after translation: XLIFF (one result per `<file>`) or HTML (one result).
     *
     * @throws TranslationFileException `TOO_LARGE`; `INVALID_FILE` (neither XLIFF nor HTML, not well-formed, a DTD);
     *   `MISSING_METADATA`
     */
    @JvmStatic
    public fun read(content: String): List<TranslationFile> {
        if (content.length > MAX_FILE_CHARACTERS) {
            throw fail(TranslationFileErrorCode.TOO_LARGE, "the file is larger than $MAX_FILE_CHARACTERS characters")
        }
        val start = trimStart(stripBom(content)).take(200).lowercase()
        if (start.startsWith("<?xml") || start.startsWith("<xliff")) return readXliff(content)
        if (start.startsWith("<!doctype html") ||
            (start.startsWith("<html") && start.length > 5 && (isSpace(start[5]) || start[5] == '>'))
        ) {
            return listOf(readHtml(content))
        }
        throw fail(TranslationFileErrorCode.INVALID_FILE, "neither an XLIFF nor an HTML file")
    }

    // ------------------------------------------------------------------ importing

    private fun escapeHtmlText(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Rebuilds an HTML segment from XLIFF target parts with the current source's codes; null if the target uses codes the source doesn't have. */
    private fun rebuildHtml(parts: List<Any>, source: String): String? {
        val codes = HashMap<String, String>()
        for (p in segmentParts(source)) {
            if (p is Code) codes[p.kind.tag + ":" + p.id] = p.raw!!
        }
        val open = ArrayDeque<Long>()
        val used = HashSet<Long>()
        val out = StringBuilder()
        for (p in parts) {
            if (p is String) {
                out.append(escapeHtmlText(p))
                continue
            }
            if (p === InvalidCode) return null
            val c = p as Code
            val raw = codes[c.kind.tag + ":" + c.id] ?: return null
            if (c.kind == CodeKind.BPT) {
                if (!used.add(c.id)) return null
                open.addLast(c.id)
            } else if (c.kind == CodeKind.EPT) {
                if (open.removeLastOrNull() != c.id) return null
            }
            out.append(raw)
        }
        return if (open.isEmpty()) out.toString() else null
    }

    private val URL_ATTRIBUTES = setOf("href", "src", "action", "formaction", "poster", "cite", "xlink:href")
    private val SAFE_SCHEMES = setOf("http", "https", "mailto", "tel")

    private fun safeUrl(value: String): Boolean {
        val lower = HtmlDocument.decode(value).filter { it > ' ' && it != '\u007F' }.lowercase()
        val cut = lower.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val head = if (cut < 0) lower else lower.substring(0, cut)
        if (head.indexOf('&') >= 0) return false
        val colon = head.indexOf(':')
        return colon < 0 || head.substring(0, colon) in SAFE_SCHEMES
    }

    private fun safeSrcset(value: String): Boolean = value.split(",").all { candidate ->
        val c = trim(candidate)
        var end = 0
        while (end < c.length && !isSpace(c[end])) end++
        safeUrl(c.substring(0, end))
    }

    /** A translated HTML segment from an HTML file, rebuilt; null if it has tags, attributes, comments or URLs the source doesn't allow. */
    private fun sanitizeHtml(inner: String, source: String): String? {
        val allowed = HashMap<String, MutableSet<String>>()
        val comments = HashSet<String>()
        for (t in tokenize(source)) {
            if (t.kind == Kind.COMMENT) {
                comments.add(t.raw)
            } else if (t.kind != Kind.TEXT) {
                val names = allowed.getOrPut(t.name!!) { HashSet() }
                if (t.kind == Kind.START) t.attrs.forEach { names.add(it.first) }
            }
        }
        val out = StringBuilder()
        for (t in tokenize(inner)) {
            when (t.kind) {
                Kind.TEXT -> out.append(t.raw.replace("<", "&lt;"))
                Kind.COMMENT -> {
                    if (t.raw !in comments) return null
                    out.append(t.raw)
                }
                else -> {
                    val names = allowed[t.name] ?: return null
                    if (t.kind == Kind.START) {
                        for ((name, value) in t.attrs) {
                            if (name !in names) return null
                            if (value != null && name in URL_ATTRIBUTES && !safeUrl(value)) return null
                            if (value != null && name == "srcset" && !safeSrcset(value)) return null
                        }
                    }
                    out.append(t.raw)
                }
            }
        }
        return out.toString()
    }

    /**
     * The translations for the integration's current segments (collected again from the item the file's `reference`
     * names), one per non-blank segment in their order. Markup comes from the current source only: XLIFF codes are
     * replaced by the source's tags; HTML translations may only use the source's tags, attributes and comments, and
     * safe URLs.
     *
     * @throws TranslationFileException `INVALID_FILE`: [file] wasn't returned by [read]
     */
    @JvmStatic
    public fun importTranslations(file: TranslationFile, current: List<FileSegment>): List<ImportedSegment> {
        val units = file.data ?: throw fail(TranslationFileErrorCode.INVALID_FILE, "not a file returned by read()")
        val out = ArrayList<ImportedSegment>()
        for (segment in current) {
            if (isBlank(segment.text)) continue
            val unit = units[segment.key]
            if (unit == null) {
                out.add(ImportedSegment(segment.key, ImportStatus.MISSING, null))
                continue
            }
            // text: the translation; null with untranslated false: invalid markup
            var text: String? = null
            var untranslated = false
            val htmlTarget = unit.htmlTarget
            val xliffTarget = unit.xliffTarget
            if (htmlTarget == null && xliffTarget == null) {
                untranslated = true
            } else if (htmlTarget != null) {
                if (segment.html) {
                    untranslated = isBlank(htmlTarget)
                    text = if (untranslated) null else sanitizeHtml(htmlTarget, segment.text)
                } else {
                    text = HtmlDocument.plain(htmlTarget)
                    untranslated = text.isEmpty()
                }
            } else if (xliffTarget != null) {
                val hasCode = xliffTarget.any { it !is String }
                val strings = xliffTarget.filterIsInstance<String>()
                if (!hasCode && strings.all(::isBlank)) {
                    untranslated = true
                } else if (segment.html) {
                    text = rebuildHtml(xliffTarget, segment.text)
                } else {
                    text = if (hasCode) null else strings.joinToString("")
                }
            }
            out.add(
                when {
                    untranslated -> ImportedSegment(segment.key, ImportStatus.UNTRANSLATED, null)
                    text == null -> ImportedSegment(segment.key, ImportStatus.INVALID_MARKUP, null)
                    else -> ImportedSegment(
                        segment.key,
                        if (checksum(segment) == unit.checksum) ImportStatus.OK else ImportStatus.SOURCE_CHANGED,
                        text,
                    )
                },
            )
        }
        return out
    }
}
