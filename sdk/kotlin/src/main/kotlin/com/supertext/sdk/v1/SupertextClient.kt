package com.supertext.sdk.v1

import com.supertext.sdk.HttpRequestData
import com.supertext.sdk.HttpResponseData
import com.supertext.sdk.JdkHttpTransport
import com.supertext.sdk.Transport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Waits between polls and retries; replace it in tests. */
public fun interface Sleeper {
    @Throws(InterruptedException::class)
    public fun sleep(duration: Duration)
}

/**
 * Client for the Supertext AI file translation API v1.
 *
 * The protocol: upload one HTML document, poll its status, download the translation, delete the file. On top of
 * it, [translateTexts] packs texts into documents and reads them back. The client applies the lessons from
 * Supertext's CMS plugins: a pasted `Supertext-Auth-Key` prefix is stripped, HTTP 429 is retried, and the file part
 * is sent as exactly `text/html`. Language codes are sent as given (trimmed): resolve CMS languages with
 * [Languages.target] and [Languages.source] first.
 *
 * Instances are immutable and thread-safe. Calls block (run them on `Dispatchers.IO` from coroutines); documents
 * are translated one after the other because the API limits requests per second per key.
 *
 * A blank key doesn't fail here: the first request throws [ErrorCode.MISSING_API_KEY] without sending anything.
 *
 * @param baseUrl API base URL (default [LIVE_BASE_URL]); a trailing slash is added
 * @param pollInterval time between status polls (default 2 s)
 * @param timeout how long to wait for one document (default 180 s)
 * @param requestTimeout timeout of each HTTP request (default 30 s)
 * @param maxRetries retries after HTTP 429 (default 4)
 * @param userAgent appended to the User-Agent, e.g. `"MyCms-Supertext/1.2.0"`
 * @param transport how requests are sent (default [JdkHttpTransport])
 * @param sleeper how the client waits between polls and retries (default `Thread.sleep`)
 * @param nanoTime monotonic clock in nanoseconds for the polling timeout (default `System.nanoTime`)
 * @param wallClock wall clock for [ApiKeyCheck.checkedAt] (default `Clock.systemUTC()`)
 */
public class SupertextClient @JvmOverloads constructor(
    apiKey: String?,
    baseUrl: String? = LIVE_BASE_URL,
    private val pollInterval: Duration = 2.seconds,
    private val timeout: Duration = 180.seconds,
    private val requestTimeout: Duration = 30.seconds,
    private val maxRetries: Int = 4,
    userAgent: String? = null,
    private val transport: Transport = JdkHttpTransport(),
    private val sleeper: Sleeper = Sleeper { Thread.sleep(it.inWholeMilliseconds) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val wallClock: Clock = Clock.systemUTC(),
) {
    private val apiKey: String = apiKey ?: ""
    private val baseUrl: String = (if (baseUrl.isNullOrBlank()) LIVE_BASE_URL else baseUrl.trim()).let {
        if (it.endsWith("/")) it else "$it/"
    }
    private val userAgent: String =
        if (userAgent.isNullOrBlank()) SDK_USER_AGENT else SDK_USER_AGENT + " " + userAgent.trim()

    init {
        require(pollInterval.isPositive()) { "pollInterval must be positive" }
        require(timeout.isPositive()) { "timeout must be positive" }
        require(requestTimeout.isPositive()) { "requestTimeout must be positive" }
        require(maxRetries >= 0) { "maxRetries must not be negative" }
    }

    public companion object {
        /** The live API. */
        public const val LIVE_BASE_URL: String = "https://api.supertext.com/v1/"

        /** Characters per document in [translateTexts], below the API's limit of 1,000,000. */
        public const val DEFAULT_MAX_CHARACTERS: Int = 900_000

        private const val SDK_USER_AGENT = "supertext-sdk-kotlin"
        private const val AUTH_PREFIX = "Supertext-Auth-Key "
        private val PASTED_PREFIX = Regex("^supertext-auth-key(\\s+|$)", RegexOption.IGNORE_CASE)
        private val RETRY_AFTER_SECONDS = Regex("^[0-9]+(\\.[0-9]+)?$")
        private val TAGS = Regex("<[^>]*>")
        private const val MAX_RETRY_DELAY_MS = 30_000L

        /**
         * The `Authorization` header value for a key as an administrator pasted it: Supertext shows the key with its
         * prefix, so a pasted prefix is stripped and exactly one is sent.
         *
         * @throws SupertextException [ErrorCode.MISSING_API_KEY] for a blank key
         */
        @JvmStatic
        public fun authHeader(apiKey: String?): String {
            val key = PASTED_PREFIX.replaceFirst(apiKey?.trim() ?: "", "").trim()
            if (key.isEmpty()) throw SupertextException(ErrorCode.MISSING_API_KEY)
            return AUTH_PREFIX + key
        }

        /**
         * Milliseconds to wait before retrying an HTTP 429. A `Retry-After` in seconds wins (capped at 30 s);
         * otherwise 1, 2, 4, 8 s plus 0–249 ms jitter, so clients sharing a key spread out.
         *
         * @param attempt 0-based retry number
         * @param retryAfter the `Retry-After` header, or `null`
         * @param random randomness for the jitter
         */
        @JvmStatic
        @JvmOverloads
        public fun retryDelayMs(attempt: Int, retryAfter: String?, random: Random = Random.Default): Long {
            if (retryAfter != null) {
                val value = trimAscii(retryAfter)
                // Only seconds; HTTP dates and garbage fall back to the backoff.
                if (RETRY_AFTER_SECONDS.matches(value)) {
                    return minOf(MAX_RETRY_DELAY_MS, Math.round(value.toDouble() * 1000))
                }
            }
            // 1000 × 2^attempt; past attempt 5 the cap applies anyway, so the shift is bounded to avoid overflow.
            val backoff = 1000L shl attempt.coerceIn(0, 20)
            return minOf(MAX_RETRY_DELAY_MS, backoff + random.nextInt(250))
        }

        /**
         * Splits the non-blank segments into documents (spec/batching.json): in order, while the packed content
         * length (code points) stays at or below [maxCharacters]; an oversized segment goes alone.
         */
        internal fun batches(segments: Map<String, Segment>, maxCharacters: Int): List<List<String>> {
            val batches = ArrayList<List<String>>()
            var current = ArrayList<String>()
            var size = 0L
            for ((key, segment) in segments) {
                if (isBlank(segment.text)) continue
                val content = HtmlDocument.content(segment)
                val length = content.codePointCount(0, content.length).toLong()
                if (current.isNotEmpty() && size + length > maxCharacters) {
                    batches.add(current)
                    current = ArrayList()
                    size = 0
                }
                current.add(key)
                size += length
            }
            if (current.isNotEmpty()) batches.add(current)
            return batches
        }

        /** The field [name] of a JSON object text, or `null` (not JSON, not an object, or missing). */
        private fun jsonObjectField(text: String, name: String): Any? = try {
            (Json.parse(text) as? Map<*, *>)?.get(name)
        } catch (e: IllegalArgumentException) {
            null
        }

        private fun filePath(fileId: String): String =
            // URLEncoder is for forms; a path needs %20, not +.
            "translate/ai/file/" + URLEncoder.encode(fileId, StandardCharsets.UTF_8).replace("+", "%20")

        private fun isAsciiSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n' || c == '\u000c'

        /** Removes leading and trailing ASCII whitespace (space, tab, CR, LF, FF), unlike [String.trim]. */
        private fun trimAscii(text: String): String = text.trim(::isAsciiSpace)

        /** Empty or only ASCII whitespace (space, tab, CR, LF, FF). */
        private fun isBlank(text: String): Boolean = text.all(::isAsciiSpace)
    }

    // ---------------------------------------------------------------- API calls

    /**
     * The language pairs the API offers right now, with their features (`supports_politeness`, …): the live version
     * of [Languages.SOURCES]/[Languages.TARGETS], e.g. for a settings page. Free of charge. Malformed entries are
     * skipped.
     */
    @Throws(InterruptedException::class)
    public fun getFeatures(): List<FeatureSupport> {
        val list = jsonObjectField(request("GET", "features").body, "supported_features") as? List<*> ?: return emptyList()
        return list.mapNotNull { item ->
            val o = item as? Map<*, *> ?: return@mapNotNull null
            val source = o["source_lang"] as? String ?: return@mapNotNull null
            val target = o["target_lang"] as? String ?: return@mapNotNull null
            FeatureSupport(source, target, (o["features"] as? List<*>)?.filterIsInstance<String>() ?: emptyList())
        }
    }

    /** Checks that the key works (`GET features`, free of charge). */
    @Throws(InterruptedException::class)
    public fun validateApiKey() {
        request("GET", "features")
    }

    /**
     * The automatic API key check (spec/api-key-check.json): `GET features` with a timeout of at most
     * [ApiKeyCheck.TIMEOUT] and the usual 429 retries. Never throws: 2xx is `ok`, 401/403 `invalid`, 5xx or no answer
     * `unreachable`, anything else `error`. A blank key gives `missing` without a request. If the thread is
     * interrupted, the check is `unreachable` and the interrupt flag stays set.
     */
    public fun checkApiKey(): ApiKeyCheck {
        val checkedAt = ApiKeyCheck.isoSeconds(wallClock.instant())
        val fingerprint = ApiKeyCheck.fingerprint(apiKey)
        if (fingerprint.isEmpty()) return ApiKeyCheck(ApiKeyCheck.Status.MISSING, checkedAt, null, null, fingerprint)
        return try {
            val response = request("GET", "features", timeout = minOf(requestTimeout, ApiKeyCheck.TIMEOUT))
            ApiKeyCheck(ApiKeyCheck.Status.OK, checkedAt, response.status, null, fingerprint)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ApiKeyCheck(ApiKeyCheck.Status.UNREACHABLE, checkedAt, null, null, fingerprint)
        } catch (e: RuntimeException) {
            ApiKeyCheck.fromError(e, checkedAt, fingerprint)
        }
    }

    /**
     * Uploads an HTML document for translation.
     *
     * The language codes are sent as given (trimmed): resolve CMS languages with [Languages.target] and
     * [Languages.source] first.
     *
     * @param targetLang a Supertext target code, e.g. `de-CH` (see [Languages.TARGETS])
     * @param sourceLang a Supertext source code, e.g. `en`, or `null`/empty to auto-detect
     * @return the file id
     * @throws SupertextException [ErrorCode.UNSUPPORTED_LANGUAGE] for an empty target, without a request
     */
    @JvmOverloads
    @Throws(InterruptedException::class)
    public fun submit(
        html: String,
        targetLang: String?,
        sourceLang: String? = null,
        politeness: Politeness = Politeness.DEFAULT,
    ): String {
        val target = targetLang?.trim() ?: ""
        if (target.isEmpty()) throw SupertextException(ErrorCode.UNSUPPORTED_LANGUAGE)
        val source = sourceLang?.trim() ?: ""
        val boundary = "----SupertextSdk" + UUID.randomUUID().toString().replace("-", "")
        val form = Multipart(boundary)
        // Field order as the API documents it: target_lang, source_lang, politeness, file.
        form.field("target_lang", target)
        if (source.isNotEmpty()) form.field("source_lang", source) // omitted: auto-detect
        if (politeness == Politeness.MORE || politeness == Politeness.LESS) form.field("politeness", politeness.value)
        // Exactly "text/html": a charset parameter gets HTTP 415.
        form.file("file", "content.html", "text/html", html.toByteArray(Charsets.UTF_8))

        val response = request("POST", "translate/ai/file", form.finish(), "multipart/form-data; boundary=$boundary")
        val fileId = Json.stringField(response.body, "file_id")
        if (fileId.isNullOrEmpty()) throw SupertextException(ErrorCode.NO_FILE_ID)
        return fileId
    }

    /** The file's status: `translating`, `done`, `error`, `limit_exceeded`, `deleted`, or others in future. */
    @Throws(InterruptedException::class)
    public fun getStatus(fileId: String): String =
        Json.stringField(request("GET", filePath(fileId) + "/status").body, "status") ?: ""

    /**
     * Polls until the file is done: first poll right away, then every poll interval, until the timeout. Unknown
     * statuses keep polling.
     *
     * @throws SupertextException `translation_error`, `quota_exceeded`, `file_deleted`, `timeout`, or an HTTP error
     */
    @Throws(InterruptedException::class)
    public fun waitUntilDone(fileId: String) {
        val start = nanoTime()
        while (true) {
            when (getStatus(fileId)) {
                "done" -> return
                "error" -> throw SupertextException(ErrorCode.TRANSLATION_ERROR)
                "limit_exceeded" -> throw SupertextException(ErrorCode.QUOTA_EXCEEDED)
                "deleted" -> throw SupertextException(ErrorCode.FILE_DELETED)
                else -> Unit // translating, queued, or a status this SDK doesn't know yet
            }
            val nextPoll = nanoTime() - start + pollInterval.inWholeNanoseconds
            if (nextPoll > timeout.inWholeNanoseconds) throw SupertextException(ErrorCode.TIMEOUT)
            sleeper.sleep(pollInterval)
        }
    }

    /**
     * Downloads the translated HTML.
     *
     * @throws SupertextException `incomplete_response` when the answer is empty
     */
    @Throws(InterruptedException::class)
    public fun download(fileId: String): String {
        val body = request("GET", filePath(fileId) + "/translation").body
        if (body.isBlank()) throw SupertextException(ErrorCode.INCOMPLETE_RESPONSE, detail = "empty document")
        return body
    }

    /** Deletes the file at Supertext. Never throws: files expire after 24 h anyway. */
    public fun delete(fileId: String) {
        // Run even when the thread was interrupted (e.g. a cancelled wait), so the file doesn't linger.
        var interrupted = Thread.interrupted()
        try {
            request("DELETE", filePath(fileId))
        } catch (e: InterruptedException) {
            interrupted = true
        } catch (e: RuntimeException) {
            // Ignore: best effort.
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /**
     * Translates one HTML document: upload, wait, download, and always delete the file afterwards. Keep each text
     * that belongs together in one `data-st-id` element (see [HtmlDocument]).
     */
    @JvmOverloads
    @Throws(InterruptedException::class)
    public fun translateHtml(
        html: String,
        targetLang: String?,
        sourceLang: String? = null,
        politeness: Politeness = Politeness.DEFAULT,
    ): String {
        val fileId = submit(html, targetLang, sourceLang, politeness)
        try {
            waitUntilDone(fileId)
            return download(fileId)
        } finally {
            delete(fileId)
        }
    }

    /**
     * Translates texts: packs them into as few documents as fit [maxCharacters], translates the documents one after
     * the other and reads the texts back. Blank texts come back unchanged and aren't sent; when nothing is left, no
     * request is made.
     *
     * @param segments key → segment (iteration order is kept; `mapOf` and `linkedMapOf` keep insertion order)
     * @return key → translated text, in the order of [segments]
     * @throws SupertextException `incomplete_response` (detail `"missing: key1, key2"`) when texts are missing from the answer
     */
    @JvmOverloads
    @Throws(InterruptedException::class)
    public fun translateTexts(
        segments: Map<String, Segment>,
        targetLang: String?,
        sourceLang: String? = null,
        politeness: Politeness = Politeness.DEFAULT,
        maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String?>()
        for ((key, segment) in segments) {
            result[key] = if (isBlank(segment.text)) segment.text else null // placeholder keeps the input order
        }
        for (batch in batches(segments, maxCharacters)) {
            val documentSegments = batch.map { segments.getValue(it) }
            val translated = translateHtml(HtmlDocument.build(documentSegments), targetLang, sourceLang, politeness)
            val texts = HtmlDocument.parse(translated, documentSegments)
            val missing = ArrayList<String>()
            batch.forEachIndexed { i, key ->
                val text = texts[i]
                if (text == null) missing.add(key) else result[key] = text
            }
            if (missing.isNotEmpty()) {
                throw SupertextException(ErrorCode.INCOMPLETE_RESPONSE, detail = "missing: " + missing.joinToString(", "))
            }
        }
        @Suppress("UNCHECKED_CAST") // every placeholder is filled by now
        return result as Map<String, String>
    }

    /**
     * Corrections reviewers made that the integration hasn't written into the CMS yet (Supertext Localization
     * Review; spec/corrections.json). Only the review service answers this: against Supertext's API it throws
     * [ErrorCode.NOT_FOUND]. Follows all pages. [Correction.segmentId] is the `data-st-id` the integration sent, so
     * this is useful when the integration builds its documents with its own ids ([translateHtml]).
     *
     * @param targetLang only this target language; `null`: all
     * @param sourceLang only this source language; `null`: all
     * @param politeness only this politeness; `null`: all
     */
    @JvmOverloads
    @Throws(InterruptedException::class)
    public fun pendingCorrections(
        targetLang: String? = null,
        sourceLang: String? = null,
        politeness: Politeness? = null,
    ): List<Correction> {
        val all = ArrayList<Correction>()
        var after: String? = null
        for (page in 0 until Corrections.MAX_PAGES) {
            val result = Corrections.parsePage(request("GET", Corrections.path(targetLang, sourceLang, politeness, after)).body)
            all.addAll(result.corrections)
            if (result.nextAfter == null || result.nextAfter == after) break
            after = result.nextAfter
        }
        return all.toList()
    }

    /**
     * Tells the review service these corrections are in the CMS now; returns how many it accepted. Only the fetched
     * [Correction.version] counts: a newer correction stays pending.
     */
    @Throws(InterruptedException::class)
    public fun acknowledgeCorrections(corrections: List<Correction>): Int =
        corrections.chunked(Corrections.ACKNOWLEDGE_BATCH_SIZE).sumOf { chunk ->
            val body = Corrections.acknowledgeBody(chunk).toByteArray(Charsets.UTF_8)
            Corrections.acknowledgedCount(request("POST", "review/corrections/acknowledge", body, "application/json").body)
        }

    // ---------------------------------------------------------------- HTTP

    private fun request(
        method: String,
        path: String,
        body: ByteArray? = null,
        contentType: String? = null,
        timeout: Duration = requestTimeout,
    ): HttpResponseData {
        val authorization = authHeader(apiKey) // throws missing_api_key before anything is sent
        val headers = linkedMapOf(
            "Authorization" to authorization,
            "Accept" to "application/json",
            "User-Agent" to userAgent,
        )
        if (contentType != null) headers["Content-Type"] = contentType
        val request = HttpRequestData(method, URI.create(baseUrl + path), headers, body, timeout)

        var attempt = 0
        while (true) {
            val response = try {
                transport.send(request)
            } catch (e: IOException) {
                throw SupertextException(ErrorCode.TRANSPORT_ERROR, detail = scrub(e.message ?: e.javaClass.simpleName), cause = e)
            }
            val status = response.status
            if (status in 200..299) return response
            if (status == 429 && attempt < maxRetries) {
                sleeper.sleep(retryDelayMs(attempt, response.header("Retry-After")).milliseconds)
                attempt++
                continue
            }
            throw httpError(status, response.body)
        }
    }

    /** Maps an HTTP error answer to a [SupertextException] (spec/errors.json). */
    internal fun httpError(status: Int, body: String?): SupertextException {
        val text = body ?: ""
        val apiErrorCode = Json.stringField(text, "error_code")
        var detail = scrub(trimAscii(TAGS.replace(text, "")))
        if (detail.codePointCount(0, detail.length) > 200) detail = detail.substring(0, detail.offsetByCodePoints(0, 200))
        var sourceLang: String? = null
        var targetLang: String? = null
        var feature: String? = null
        val code = when {
            (status == 400 || status == 404) && text.contains("INVALID_LANGUAGE_PAIR") -> {
                sourceLang = Json.stringField(text, "source_lang")
                targetLang = Json.stringField(text, "target_lang")
                ErrorCode.INVALID_LANGUAGE_PAIR
            }
            status == 400 && apiErrorCode == "FEATURE_ERROR" -> {
                sourceLang = Json.stringField(text, "source_lang")
                targetLang = Json.stringField(text, "target_lang")
                feature = Json.stringField(text, "feature")
                ErrorCode.UNSUPPORTED_FEATURE
            }
            status == 401 || status == 403 -> ErrorCode.AUTHENTICATION_FAILURE
            status == 404 -> ErrorCode.NOT_FOUND
            status == 413 -> ErrorCode.PAYLOAD_TOO_LARGE
            status == 429 -> ErrorCode.TOO_MANY_REQUESTS
            status in 500..599 -> ErrorCode.SERVICE_UNAVAILABLE
            else -> ErrorCode.UNEXPECTED_STATUS
        }
        return SupertextException(code, status, detail, apiErrorCode, sourceLang, targetLang, feature)
    }

    /** Removes the API key from text that leaves the SDK, in case an answer or error echoes it. */
    private fun scrub(text: String): String {
        val key = PASTED_PREFIX.replaceFirst(apiKey.trim(), "").trim()
        return if (key.length >= 4) text.replace(key, "***") else text
    }

    override fun toString(): String = "SupertextClient{baseUrl=$baseUrl}"

    /** multipart/form-data written by hand: the JDK has no builder, and the file part must carry exactly "text/html". */
    private class Multipart(private val boundary: String) {
        private val out = ByteArrayOutputStream()

        fun field(name: String, value: String) {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        }

        fun file(name: String, fileName: String, type: String, content: ByteArray) {
            write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"; filename=\"$fileName\"\r\nContent-Type: $type\r\n\r\n")
            out.write(content, 0, content.size)
            write("\r\n")
        }

        fun finish(): ByteArray {
            write("--$boundary--\r\n")
            return out.toByteArray()
        }

        private fun write(text: String) {
            val bytes = text.toByteArray(Charsets.UTF_8)
            out.write(bytes, 0, bytes.size)
        }
    }
}
