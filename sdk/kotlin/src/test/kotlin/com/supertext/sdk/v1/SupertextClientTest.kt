package com.supertext.sdk.v1

import com.supertext.sdk.HttpRequestData
import com.supertext.sdk.HttpResponseData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SupertextClientTest {

    private val key = "secret-key-123"
    private val base = "https://api.example.test/v1/"

    private val sleeps = ArrayList<Duration>()
    private var clock = 0L

    /** A client on a fake transport, whose sleeps advance a fake clock. */
    private fun client(
        transport: FakeTransport,
        pollInterval: Duration = 2.seconds,
        timeout: Duration = 180.seconds,
        maxRetries: Int = 4,
    ) = SupertextClient(
        key,
        baseUrl = "https://api.example.test/v1",
        pollInterval = pollInterval,
        timeout = timeout,
        maxRetries = maxRetries,
        transport = transport,
        sleeper = { d ->
            sleeps.add(d)
            clock += d.inWholeNanoseconds
        },
        nanoTime = { clock },
    )

    private fun <T> fails(code: ErrorCode, call: () -> T): SupertextException {
        val e = assertThrows(SupertextException::class.java) { call() }
        assertEquals(code, e.code)
        return e
    }

    /** Parts of a multipart body: (headers, value), in order. */
    private fun parts(request: HttpRequestData): List<Pair<String, String>> {
        val contentType = request.headers.getValue("Content-Type")
        val boundary = contentType.substringAfter("boundary=")
        val body = FakeTransport.body(request)!!
        assertTrue(body.endsWith("--$boundary--\r\n"))
        return body.split("--$boundary").filter { it.isNotEmpty() && it != "--\r\n" }.map { raw ->
            val part = raw.substring(2, raw.length - 2) // leading and trailing CRLF
            val split = part.indexOf("\r\n\r\n")
            Pair(part.substring(0, split), part.substring(split + 4))
        }
    }

    private fun name(part: Pair<String, String>): String = Regex("name=\"([^\"]+)\"").find(part.first)!!.groupValues[1]

    private fun happyPath(translation: String) = FakeTransport()
        .answer(200, "{\"file_id\":\"f 1\"}")
        .answer(200, "{\"status\":\"translating\"}")
        .answer(200, "{\"status\":\"done\"}")
        .answer(200, translation)
        .answer(200, "{}")

    // ------------------------------------------------------------ upload

    @Test
    fun uploadSendsTheFieldsInOrderWithTheLanguageCodesAsGiven() {
        val transport = FakeTransport().answer(200, "{\"file_id\":\"abc\"}")
        assertEquals("abc", client(transport).submit("<p>Grüezi</p>", " de-CH ", " en ", Politeness.MORE))

        val request = transport.requests[0]
        assertEquals("POST", request.method)
        assertEquals(base + "translate/ai/file", request.uri.toString())
        assertTrue(request.headers.getValue("Content-Type").startsWith("multipart/form-data; boundary="))
        val parts = parts(request)
        assertEquals(listOf("target_lang", "source_lang", "politeness", "file"), parts.map(::name))
        assertEquals("de-CH", parts[0].second)
        assertEquals("en", parts[1].second)
        assertEquals("more", parts[2].second)
        assertEquals("Content-Disposition: form-data; name=\"file\"; filename=\"content.html\"\r\nContent-Type: text/html", parts[3].first)
        assertEquals("<p>Grüezi</p>", parts[3].second)
    }

    @Test
    fun uploadLeavesOutAnEmptySourceAndDefaultPoliteness() {
        val transport = FakeTransport().answer(200, "{\"file_id\":\"abc\"}").answer(200, "{\"file_id\":\"def\"}")
        val client = client(transport)
        client.submit("x", "fr-CH")
        client.submit("x", "fr-CH", "  ", Politeness.LESS)
        val first = parts(transport.requests[0])
        assertEquals(listOf("target_lang", "file"), first.map(::name))
        assertEquals("fr-CH", first[0].second)
        val second = parts(transport.requests[1])
        assertEquals(listOf("target_lang", "politeness", "file"), second.map(::name))
        assertEquals("less", second[1].second)
    }

    @Test
    fun codesAreNotConverted() {
        // No default regions: "de" and "en-US" go out unchanged, the API decides.
        val transport = FakeTransport().answer(200, "{\"file_id\":\"abc\"}")
        client(transport).submit("x", "de", "en-US")
        val parts = parts(transport.requests[0])
        assertEquals("de", parts[0].second)
        assertEquals("en-US", parts[1].second)
    }

    @Test
    fun emptyTargetIsUnsupportedLanguageWithoutARequest() {
        for (target in listOf(null, "", "  ")) {
            val transport = FakeTransport()
            val e = fails(ErrorCode.UNSUPPORTED_LANGUAGE) { client(transport).submit("x", target) }
            assertNull(e.status)
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun uploadWithoutFileIdFails() {
        for (body in listOf("{}", "{\"file_id\":\"\"}", "not json", "")) {
            fails(ErrorCode.NO_FILE_ID) { client(FakeTransport().answer(200, body)).submit("x", "de") }
        }
    }

    // ------------------------------------------------------------ headers

    @Test
    fun headers() {
        val transport = FakeTransport().answer(200, "{}").answer(200, "{}")
        client(transport).validateApiKey()
        SupertextClient("Supertext-Auth-Key $key", baseUrl = base, transport = transport, userAgent = "Typo3-Supertext-Translation/1.2.0")
            .validateApiKey()

        val plain = transport.requests[0].headers
        assertEquals("GET", transport.requests[0].method)
        assertEquals(base + "features", transport.requests[0].uri.toString())
        assertEquals("Supertext-Auth-Key $key", plain["Authorization"])
        assertEquals("application/json", plain["Accept"])
        assertEquals("supertext-sdk-kotlin", plain["User-Agent"])
        assertNull(transport.requests[0].body)
        assertEquals(30.seconds, transport.requests[0].timeout)

        val custom = transport.requests[1].headers
        assertEquals("Supertext-Auth-Key $key", custom["Authorization"])
        assertEquals("supertext-sdk-kotlin Typo3-Supertext-Translation/1.2.0", custom["User-Agent"])
    }

    @Test
    fun missingKeySendsNothing() {
        for (apiKey in listOf(null, "", "  ", "Supertext-Auth-Key ")) {
            val transport = FakeTransport()
            val client = SupertextClient(apiKey, transport = transport) // doesn't throw
            fails(ErrorCode.MISSING_API_KEY) { client.translateTexts(mapOf("a" to Segment.text("Hello")), "de") }
            assertTrue(transport.requests.isEmpty())
        }
    }

    @Test
    fun invalidOptionsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { SupertextClient("k", pollInterval = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { SupertextClient("k", timeout = (-1).seconds) }
        assertThrows(IllegalArgumentException::class.java) { SupertextClient("k", requestTimeout = Duration.ZERO) }
        assertThrows(IllegalArgumentException::class.java) { SupertextClient("k", maxRetries = -1) }
    }

    // ------------------------------------------------------------ 429

    @Test
    fun retries429WithRetryAfter() {
        val transport = FakeTransport()
            .answer(429, "{\"error_code\":\"RATE_LIMIT_EXCEEDED\"}", mapOf("retry-after" to "3"))
            .answer(429, "", mapOf("Retry-After" to "0.5"))
            .answer(200, "{}")
        client(transport).validateApiKey()
        assertEquals(3, transport.requests.size)
        assertEquals(listOf(3000.milliseconds, 500.milliseconds), sleeps)
    }

    @Test
    fun gives429UpAfterMaxRetries() {
        val transport = FakeTransport().handler { HttpResponseData(429, emptyMap(), "{\"error_code\":\"RATE_LIMIT_EXCEEDED\"}") }
        val e = fails(ErrorCode.TOO_MANY_REQUESTS) { client(transport).validateApiKey() }
        assertEquals(429, e.status)
        assertEquals("RATE_LIMIT_EXCEEDED", e.apiErrorCode)
        assertEquals(5, transport.requests.size) // 1 + 4 retries
        val ranges = listOf(1000L..1249L, 2000L..2249L, 4000L..4249L, 8000L..8249L)
        assertEquals(4, sleeps.size)
        sleeps.forEachIndexed { i, d -> assertTrue(d.inWholeMilliseconds in ranges[i], "wait $i: $d") }
    }

    @Test
    fun maxRetriesIsConfigurable() {
        val transport = FakeTransport().handler { HttpResponseData(429, mapOf("Retry-After" to "1"), "") }
        assertThrows(SupertextException::class.java) { client(transport, maxRetries = 1).validateApiKey() }
        assertEquals(2, transport.requests.size)
        assertEquals(listOf(1.seconds), sleeps)
    }

    // ------------------------------------------------------------ polling

    @Test
    fun pollsRightAwayThenEveryInterval() {
        val transport = FakeTransport()
            .answer(200, "{\"status\":\"translating\"}")
            .answer(200, "{\"status\":\"queued\"}")
            .answer(200, "{\"status\":\"done\"}")
        client(transport).waitUntilDone("a/b c")
        assertEquals(List(3) { "GET ${base}translate/ai/file/a%2Fb%20c/status" }, transport.lines())
        assertEquals(listOf(2.seconds, 2.seconds), sleeps)
    }

    @Test
    fun timesOutWhenTheNextPollWouldStartTooLate() {
        val transport = FakeTransport().handler { HttpResponseData(200, null, "{\"status\":\"translating\"}") }
        fails(ErrorCode.TIMEOUT) { client(transport, pollInterval = 3.seconds, timeout = 10.seconds).waitUntilDone("f") }
        // Polls at 0, 3, 6, 9 s; one at 12 s would be after the 10 s timeout.
        assertEquals(4, transport.requests.size)
        assertEquals(3, sleeps.size)
    }

    // ------------------------------------------------------------ translateHtml

    @Test
    fun translateHtmlUploadsWaitsDownloadsAndDeletes() {
        val transport = happyPath("<p>Hallo</p>")
        assertEquals("<p>Hallo</p>", client(transport).translateHtml("<p>Hello</p>", "de", "en"))
        assertEquals(
            listOf(
                "POST ${base}translate/ai/file",
                "GET ${base}translate/ai/file/f%201/status",
                "GET ${base}translate/ai/file/f%201/status",
                "GET ${base}translate/ai/file/f%201/translation",
                "DELETE ${base}translate/ai/file/f%201",
            ),
            transport.lines(),
        )
    }

    @Test
    fun deletesAfterAFailedTranslation() {
        val transport = FakeTransport()
            .answer(200, "{\"file_id\":\"f1\"}")
            .answer(200, "{\"status\":\"limit_exceeded\"}")
            .answer(500, "boom")
        // The delete's own failure doesn't replace the error.
        fails(ErrorCode.QUOTA_EXCEEDED) { client(transport).translateHtml("<p>x</p>", "de-DE") }
        assertEquals("DELETE ${base}translate/ai/file/f1", transport.lines()[2])
    }

    @Test
    fun deletesAfterAnEmptyDownload() {
        val transport = FakeTransport()
            .answer(200, "{\"file_id\":\"f1\"}")
            .answer(200, "{\"status\":\"done\"}")
            .answer(200, " \n ")
            .fail(ConnectException("refused"))
        fails(ErrorCode.INCOMPLETE_RESPONSE) { client(transport).translateHtml("<p>x</p>", "de-DE") }
        assertEquals(4, transport.requests.size)
        assertEquals("DELETE", transport.requests[3].method)
    }

    @Test
    fun deleteNeverThrows() {
        client(FakeTransport().answer(404, "{}")).delete("gone")
        client(FakeTransport().fail(IOException("down"))).delete("x")
        SupertextClient("", transport = FakeTransport()).delete("x")
        Thread.currentThread().interrupt()
        val transport = FakeTransport().answer(200, "{}")
        client(transport).delete("x") // still attempted, and the interrupt is kept
        assertTrue(Thread.interrupted())
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun networkFailureIsTransportError() {
        val e = fails(ErrorCode.TRANSPORT_ERROR) { client(FakeTransport().fail(ConnectException("Connection refused"))).validateApiKey() }
        assertNull(e.status)
        assertEquals("Connection refused", e.detail)
        assertTrue(e.cause is ConnectException)
    }

    @Test
    fun theKeyNeverAppearsInErrors() {
        val transport = FakeTransport()
            .answer(401, "<p>Key $key is not valid</p>")
            .fail(IOException("failed with $key"))
        val client = client(transport)
        val http = fails(ErrorCode.AUTHENTICATION_FAILURE) { client.validateApiKey() }
        val network = fails(ErrorCode.TRANSPORT_ERROR) { client.validateApiKey() }
        for (e in listOf(http, network)) {
            assertFalse(e.message!!.contains(key), e.message)
            assertFalse(e.detail.toString().contains(key))
            assertFalse(e.toString().contains(key))
        }
        assertFalse(client.toString().contains(key))
    }

    @Test
    fun messagesFollowTheCodeStatusAndDetail() {
        val transport = FakeTransport()
            .answer(400, "{\"error_code\":\"FEATURE_ERROR\",\"feature\":\"politeness\",\"source_lang\":\"de\",\"target_lang\":\"en-US\"}")
        val e = fails(ErrorCode.UNSUPPORTED_FEATURE) { client(transport).submit("x", "en-US", "de", Politeness.MORE) }
        assertEquals("politeness", e.feature)
        assertEquals("de", e.sourceLang)
        assertEquals("en-US", e.targetLang)
        assertEquals("Supertext does not support this option for the language pair. HTTP 400. Details: ${e.detail}", e.message)
        assertEquals("No Supertext API key is configured.", SupertextException(ErrorCode.MISSING_API_KEY).message)
    }

    @Test
    fun getFeaturesReadsThePairsAndSkipsMalformedEntries() {
        val transport = FakeTransport().answer(
            200,
            "{\"supported_features\":[" +
                "{\"source_lang\":\"en\",\"target_lang\":\"de-CH\",\"features\":[\"supports_politeness\",7,\"supports_glossary\"]}," +
                "{\"source_lang\":\"en\",\"target_lang\":\"en-US\"}," +
                "{\"source_lang\":\"en\"}," +
                "{\"source_lang\":1,\"target_lang\":\"fr-CH\"}," +
                "\"junk\",null]}",
        )
            .answer(200, "{}")
            .answer(200, "not json")
            .answer(200, "{\"supported_features\":{}}")
        val client = client(transport)
        assertEquals(
            listOf(
                FeatureSupport("en", "de-CH", listOf("supports_politeness", "supports_glossary")),
                FeatureSupport("en", "en-US", emptyList()),
            ),
            client.getFeatures(),
        )
        assertEquals("GET ${base}features", transport.lines()[0])
        repeat(3) { assertEquals(emptyList<FeatureSupport>(), client.getFeatures()) }
    }

    @Test
    fun longDetailIsCut() {
        val e = fails(ErrorCode.SERVICE_UNAVAILABLE) { client(FakeTransport().answer(500, "x".repeat(500))).validateApiKey() }
        assertEquals(200, e.detail!!.length)
    }

    // ------------------------------------------------------------ translateTexts

    /** Answers uploads like the stand-in: prefixes every segment's content. */
    private fun echo(prefix: String, uploads: MutableList<String>): FakeTransport {
        val files = LinkedHashMap<String, String>()
        return FakeTransport().handler { request ->
            val path = request.uri.path
            if (request.method == "POST") {
                val html = parts(request).last().second
                uploads.add(html)
                val id = "f${files.size}"
                files[id] = html.replace(Regex("(<div data-st-id=\"\\d+\">)"), "$1$prefix")
                return@handler HttpResponseData(200, null, "{\"file_id\":\"$id\"}")
            }
            val id = path.replace(Regex(".*/file/([^/]+).*"), "$1")
            when {
                path.endsWith("/status") -> HttpResponseData(200, null, "{\"status\":\"done\"}")
                path.endsWith("/translation") -> HttpResponseData(200, null, files[id])
                else -> HttpResponseData(200, null, "{}")
            }
        }
    }

    @Test
    fun translateTextsKeepsOrderAndBlanks() {
        val uploads = ArrayList<String>()
        val segments = linkedMapOf(
            "title" to Segment.text("Fish & Chips"),
            "blank" to Segment.text(" \n"),
            "body" to Segment.html("<p>Our <b>new</b> shop</p>"),
            "lines" to Segment.text("Line 1\r\nLine 2"),
        )
        val result = client(echo("[de] ", uploads)).translateTexts(segments, "de", "en-GB", Politeness.LESS)

        assertEquals(listOf("title", "blank", "body", "lines"), result.keys.toList())
        assertEquals("[de] Fish & Chips", result["title"])
        assertEquals(" \n", result["blank"])
        assertEquals("[de] <p>Our <b>new</b> shop</p>", result["body"])
        assertEquals("[de] Line 1\nLine 2", result["lines"])
        assertEquals(1, uploads.size)
        assertTrue(uploads[0].contains("<div data-st-id=\"0\">Fish &amp; Chips</div>"))
        assertTrue(uploads[0].contains("<div data-st-id=\"2\">Line 1<br>Line 2</div>"))
    }

    @Test
    fun translateTextsSplitsIntoDocumentsOneAfterTheOther() {
        val uploads = ArrayList<String>()
        val transport = echo("", uploads)
        val segments = linkedMapOf("a" to Segment.text("12345"), "b" to Segment.text("12345"), "c" to Segment.text("1"))
        val result = client(transport).translateTexts(segments, "de", maxCharacters = 10)
        assertEquals(mapOf("a" to "12345", "b" to "12345", "c" to "1"), result)
        assertEquals(2, uploads.size)
        // Second document only after the first one's delete.
        assertEquals(listOf("POST", "GET", "GET", "DELETE", "POST", "GET", "GET", "DELETE"), transport.requests.map { it.method })
    }

    @Test
    fun nothingToSendMeansNoRequest() {
        val transport = FakeTransport()
        val segments = linkedMapOf("a" to Segment.text(""), "b" to Segment.html(" \t"))
        assertEquals(mapOf("a" to "", "b" to " \t"), client(transport).translateTexts(segments, "de"))
        assertTrue(transport.requests.isEmpty())
    }

    @Test
    fun missingSegmentsAreIncompleteAfterTheDelete() {
        val transport = happyPath("<html><body><div data-st-id=\"1\">zwei</div></body></html>")
        val segments = linkedMapOf("one" to Segment.text("one"), "two" to Segment.text("two"), "three" to Segment.text("three"))
        val e = fails(ErrorCode.INCOMPLETE_RESPONSE) { client(transport).translateTexts(segments, "de") }
        assertEquals("missing: one, three", e.detail)
        assertEquals("DELETE", transport.requests.last().method)
    }

    @Test
    fun politenessParsing() {
        assertEquals(Politeness.DEFAULT, Politeness.fromValue(null))
        assertEquals(Politeness.MORE, Politeness.fromValue(" More "))
        assertEquals(Politeness.DEFAULT, Politeness.fromValue("formal"))
        assertEquals(Politeness.DEFAULT, Politeness.fromValue("default"))
    }
}
