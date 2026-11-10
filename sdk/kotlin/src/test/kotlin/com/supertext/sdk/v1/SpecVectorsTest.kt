package com.supertext.sdk.v1

import com.supertext.sdk.HttpResponseData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random

/** Every case in spec/\*.json (the shared test vectors of all Supertext SDKs). */
class SpecVectorsTest {

    // ------------------------------------------------------------ languages.json

    @Test
    fun languageListsMatchTheSpec() {
        val spec = Spec.load("languages.json")
        val sources = Spec.list(spec, "sources").map { SourceLanguage(it["code"] as String, it["name"] as String) }
        val targets = Spec.list(spec, "targets").map {
            TargetLanguage(it["code"] as String, it["name"] as String, it["politeness"] as Boolean)
        }
        assertEquals(sources, Languages.SOURCES)
        assertEquals(targets, Languages.TARGETS)
        assertEquals(Spec.strings(spec["defaultTargets"]), Languages.DEFAULT_TARGETS)
        assertEquals(spec["targetOptions"], Languages.targetOptions().map { it.code })
        assertEquals(Languages.TARGETS.toSet(), Languages.targetOptions().toSet())
    }

    /** Runs [call]; expects either `c.out` or the error `c.error`. */
    private fun outOrError(c: Map<String, Any?>, call: () -> String): SupertextException? {
        if ("error" in c) {
            val e = assertThrows(SupertextException::class.java) { call() }
            assertEquals(c["error"], e.code.value)
            assertNull(e.status)
            return e
        }
        assertEquals(c["out"], call())
        return null
    }

    @TestFactory
    fun languages(): List<DynamicTest> {
        val spec = Spec.load("languages.json")
        val tests = ArrayList<DynamicTest>()
        for (c in Spec.list(spec, "matchTarget")) {
            val input = c["in"] as String?
            tests += dynamicTest("matchTarget '$input'") {
                assertEquals(c["out"], Languages.matchTarget(input))
                assertEquals(c["out"] != null, Languages.isSupportedTarget(input))
            }
        }
        for (c in Spec.list(spec, "defaultTarget")) {
            tests += dynamicTest("defaultTarget '${c["in"]}'") { assertEquals(c["out"], Languages.defaultTarget(c["in"] as String?)) }
        }
        for (c in Spec.list(spec, "suggestTarget")) {
            tests += dynamicTest("suggestTarget '${c["in"]}'") { assertEquals(c["out"], Languages.suggestTarget(c["in"] as String?)) }
        }
        for (c in Spec.list(spec, "matchSource")) {
            tests += dynamicTest("matchSource '${c["in"]}'") { assertEquals(c["out"], Languages.matchSource(c["in"] as String?)) }
        }
        for (c in Spec.list(spec, "resolveTarget")) {
            val input = c["in"] as String
            val mapping = Spec.strings(c["mapping"])
            tests += dynamicTest("target '$input' $mapping") {
                outOrError(c) { Languages.target(input, mapping) }?.let { e ->
                    assertEquals(input.trim().ifEmpty { null }, e.targetLang)
                    assertNull(e.sourceLang)
                }
            }
        }
        for (c in Spec.list(spec, "resolveSource")) {
            val input = c["in"] as String
            val mapping = Spec.strings(c["mapping"])
            tests += dynamicTest("source '$input' $mapping") {
                outOrError(c) { Languages.source(input, mapping) }?.let { e ->
                    assertEquals(input.trim(), e.sourceLang)
                    assertNull(e.targetLang)
                }
            }
        }
        for (c in Spec.list(spec, "supportsPoliteness")) {
            tests += dynamicTest("supportsPoliteness ${c["in"]}") {
                assertEquals(c["out"], Languages.supportsPoliteness(c["in"] as String?))
            }
        }
        return tests
    }

    // ------------------------------------------------------------ auth-header.json

    @TestFactory
    fun authHeader(): List<DynamicTest> = Spec.list(Spec.load("auth-header.json"), "cases").map { c ->
        val input = c["in"] as String?
        dynamicTest("authHeader " + input.toString().replace("\n", "\\n")) {
            if ("error" in c) {
                val e = assertThrows(SupertextException::class.java) { SupertextClient.authHeader(input) }
                assertEquals(c["error"], e.code.value)
            } else {
                assertEquals(c["out"], SupertextClient.authHeader(input))
            }
        }
    }

    // ------------------------------------------------------------ retry-delay.json

    /** Randomness that always gives the lowest or the highest jitter. */
    private fun fixed(highest: Boolean): Random = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextInt(until: Int): Int = if (highest) until - 1 else 0
    }

    @TestFactory
    fun retryDelay(): List<DynamicTest> {
        val spec = Spec.load("retry-delay.json")
        assertEquals(4, Spec.int(spec["maxRetries"]))
        return Spec.list(spec, "cases").map { c ->
            dynamicTest("retryDelay attempt ${c["attempt"]} retryAfter ${c["retryAfter"]}") {
                val attempt = Spec.int(c["attempt"])
                val retryAfter = c["retryAfter"] as String?
                val min = (c["min"] as Double).toLong()
                val max = (c["max"] as Double).toLong()
                assertEquals(min, SupertextClient.retryDelayMs(attempt, retryAfter, fixed(false)))
                assertEquals(max, SupertextClient.retryDelayMs(attempt, retryAfter, fixed(true)))
                repeat(50) {
                    val delay = SupertextClient.retryDelayMs(attempt, retryAfter)
                    assertTrue(delay in min..max, "delay $delay")
                }
            }
        }
    }

    // ------------------------------------------------------------ errors.json

    @TestFactory
    fun httpErrors(): List<DynamicTest> = Spec.list(Spec.load("errors.json"), "http").map { c ->
        dynamicTest("HTTP ${c["status"]} ${c["body"]}") {
            val status = Spec.int(c["status"])
            val transport = FakeTransport().answer(status, c["body"] as String?)
            val client = SupertextClient("key-1234", transport = transport, maxRetries = 0)
            val e = assertThrows(SupertextException::class.java) { client.validateApiKey() }
            assertEquals(c["code"], e.code.value)
            assertEquals(status, e.status)
            assertEquals(c["apiErrorCode"], e.apiErrorCode)
            assertEquals(c["sourceLang"], e.sourceLang)
            assertEquals(c["targetLang"], e.targetLang)
            assertEquals(c["detail"], e.detail)
            assertEquals(c["feature"], e.feature)
        }
    }

    @TestFactory
    fun fileStatuses(): List<DynamicTest> = Spec.list(Spec.load("errors.json"), "fileStatus").map { c ->
        dynamicTest("status ${c["status"]}") {
            val transport = FakeTransport()
                .answer(200, "{\"status\":\"${c["status"]}\"}")
                .answer(200, "{\"status\":\"done\"}")
            val client = SupertextClient("key", transport = transport, sleeper = RecordingSleeper())
            if (c["code"] == null) {
                client.waitUntilDone("f1")
            } else {
                val e = assertThrows(SupertextException::class.java) { client.waitUntilDone("f1") }
                assertEquals(c["code"], e.code.value)
                assertNull(e.status)
            }
        }
    }

    @Test
    fun errorCodesMatchTheSpec() {
        val codes = Spec.obj(Spec.load("errors.json")["codes"])
        assertEquals(codes.keys.toList(), ErrorCode.entries.map { it.value })
        for (code in ErrorCode.entries) assertEquals(code, ErrorCode.fromValue(code.value))
    }

    // ------------------------------------------------------------ html-document.json

    private fun segments(list: Any?, withText: Boolean): List<Segment> = (list as List<*>).map {
        val m = it as Map<*, *>
        Segment(if (withText) m["text"] as String else "", m["html"] == true)
    }

    @TestFactory
    fun htmlDocument(): List<DynamicTest> {
        val spec = Spec.load("html-document.json")
        return Spec.list(spec, "build").map { c ->
            dynamicTest("build: ${c["name"]}") { assertEquals(c["html"], HtmlDocument.build(segments(c["segments"], true))) }
        } + Spec.list(spec, "parse").map { c ->
            dynamicTest("parse: ${c["name"]}") {
                assertEquals(c["out"], HtmlDocument.parse(c["html"] as String, segments(c["segments"], false)))
            }
        }
    }

    // ------------------------------------------------------------ batching.json

    @TestFactory
    fun batching(): List<DynamicTest> {
        val spec = Spec.load("batching.json")
        assertEquals(SupertextClient.DEFAULT_MAX_CHARACTERS, Spec.int(spec["defaultMaxCharacters"]))
        return Spec.list(spec, "cases").map { c ->
            dynamicTest(c["name"] as String) {
                val segments = LinkedHashMap<String, Segment>()
                for (o in c["segments"] as List<*>) {
                    val m = o as Map<*, *>
                    segments[m["key"] as String] = Segment(m["text"] as String, m["html"] == true)
                }
                assertEquals(c["batches"], SupertextClient.batches(segments, Spec.int(c["maxCharacters"])))
            }
        }
    }

    // ------------------------------------------------------------ corrections.json

    private fun ackBody(from: Int, to: Int, version: String): String =
        (from..to).joinToString(",", "{\"corrections\":[", "]}") { "{\"id\":\"$it\",\"version\":\"$version\"}" }

    private fun correction(id: String, version: String) =
        Correction(id, "", null, "", Politeness.DEFAULT, "", "", "", "", "", version)

    @TestFactory
    fun corrections(): List<DynamicTest> = Spec.list(Spec.load("corrections.json"), "scenarios").map { c ->
        dynamicTest("corrections ${c["name"]}") {
            val exchanges = ArrayDeque(Spec.list(c, "exchanges"))
            val generate = c["generate"]?.let(Spec::obj)
            val version = generate?.get("version") as String? ?: ""
            val transport = FakeTransport().handler { request ->
                val x = exchanges.removeFirstOrNull() ?: throw AssertionError("unexpected ${request.method} ${request.uri}")
                val req = Spec.obj(x["request"])
                assertEquals(req["method"], request.method)
                assertEquals("https://review.test/v1/${req["path"]}", request.uri.toString())
                val body = FakeTransport.body(request)
                if ("body" in req) assertEquals(req["body"], body)
                if ("bodyIds" in req) {
                    val ids = req["bodyIds"] as List<*>
                    assertEquals(ackBody(Spec.int(ids[0]), Spec.int(ids[1]), version), body)
                }
                if (body != null) assertEquals("application/json", request.headers["Content-Type"])
                val res = Spec.obj(x["response"])
                HttpResponseData(Spec.int(res["status"]), emptyMap(), res["body"] as String?)
            }
            val client = SupertextClient("key-1", baseUrl = "https://review.test/v1/", transport = transport, sleeper = RecordingSleeper())
            val corrections = when {
                generate != null -> (1..Spec.int(generate["count"])).map { correction(it.toString(), version) }
                "corrections" in c -> Spec.list(c, "corrections").map { correction(it["id"] as String, it["version"] as String) }
                else -> emptyList()
            }
            val run: () -> Any = {
                if (c["call"] == "pending") {
                    val o = Spec.obj(c["options"])
                    val p = o["politeness"] as String?
                    client.pendingCorrections(o["targetLang"] as String?, o["sourceLang"] as String?, p?.let { Politeness.fromValue(it) })
                        .map {
                            linkedMapOf(
                                "id" to it.id, "segmentId" to it.segmentId, "sourceLang" to it.sourceLang,
                                "targetLang" to it.targetLang, "politeness" to it.politeness.value, "sourceHtml" to it.sourceHtml,
                                "previousHtml" to it.previousHtml, "html" to it.html, "status" to it.status,
                                "correctedAt" to it.correctedAt, "version" to it.version,
                            )
                        }
                } else {
                    client.acknowledgeCorrections(corrections).toDouble()
                }
            }
            if ("error" in c) {
                val e = assertThrows(SupertextException::class.java) { run() }
                assertEquals(c["error"], e.code.value)
            } else {
                assertEquals(c["result"], run())
            }
            assertEquals(0, exchanges.size)
        }
    }
}
