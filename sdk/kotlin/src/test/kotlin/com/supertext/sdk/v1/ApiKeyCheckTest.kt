package com.supertext.sdk.v1

import com.supertext.sdk.HttpRequestData
import com.supertext.sdk.HttpResponseData
import com.supertext.sdk.SDK_VERSION
import com.supertext.sdk.Transport
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.seconds

/** spec/api-key-check.json: the API key check Supertext Plugin Health reports. */
class ApiKeyCheckTest {

    private val spec = Spec.load("api-key-check.json")

    /** Answers GET features from a case's answer, records the requests. */
    private class FakeHttp(private val answer: Any?) : Transport {
        val requests = ArrayList<HttpRequestData>()

        override fun send(request: HttpRequestData): HttpResponseData {
            requests.add(request)
            assertEquals("https://api.test/v1/features", request.uri.toString())
            assertEquals("GET", request.method)
            val a = answer as? Map<*, *> ?: throw AssertionError("unexpected request")
            if (a["transportError"] == true) throw IOException("connection refused")
            val status = (a["status"] as Number?)?.toInt() ?: 200
            return HttpResponseData(status, emptyMap(), a["body"] as String? ?: "")
        }
    }

    @Test
    fun timeout() {
        val d = Spec.obj(spec["defaults"])
        assertEquals((d["keyCheckTimeoutSeconds"] as Number).toLong(), ApiKeyCheck.TIMEOUT.inWholeSeconds)
    }

    @TestFactory
    fun fingerprint(): List<DynamicTest> = Spec.list(spec, "fingerprint").map { c ->
        dynamicTest("fingerprint ${c["apiKey"]}") { assertEquals(c["out"], ApiKeyCheck.fingerprint(c["apiKey"] as String?)) }
    }

    @TestFactory
    fun apiKeyCheck(): List<DynamicTest> = Spec.list(spec, "apiKeyCheck").map { c ->
        dynamicTest("apiKeyCheck ${Json.write(c["answer"])}") {
            val http = FakeHttp(c["answer"])
            val client = SupertextClient(
                c["apiKey"] as String?,
                baseUrl = "https://api.test/v1/",
                transport = http,
                sleeper = RecordingSleeper(),
                wallClock = Clock.fixed(Instant.parse("2026-10-13T08:00:00.500Z"), ZoneOffset.UTC),
            )
            val check = client.checkApiKey()
            val expected = linkedMapOf(
                "status" to c["status"],
                "checkedAt" to "2026-10-13T08:00:00Z",
                "httpStatus" to c["httpStatus"],
                "errorCode" to c["errorCode"],
                "fingerprint" to ApiKeyCheck.fingerprint(c["apiKey"] as String?),
            )
            assertEquals(expected, Json.parse(Json.write(check.toMap())))
            assertEquals((c["requests"] as Number?)?.toInt() ?: 1, http.requests.size)
            for (r in http.requests) assertEquals(10.seconds, r.timeout)
        }
    }

    @Test
    fun storedFormRoundTrips() {
        val check = ApiKeyCheck(ApiKeyCheck.Status.INVALID, "2026-10-13T08:00:00Z", 401, "AUTH", "ab")
        assertEquals(check, ApiKeyCheck.fromMap(Json.parse(Json.write(check.toMap()))))
        assertNull(ApiKeyCheck.fromMap(mapOf("status" to "nope", "checkedAt" to "x")))
        assertEquals("x".repeat(100), ApiKeyCheck(ApiKeyCheck.Status.ERROR, "t", null, "x".repeat(150), null).errorCode)
    }

    @Test
    fun interruptedCheckIsUnreachableAndKeepsTheFlag() {
        val client = SupertextClient("key", transport = { throw InterruptedException() })
        try {
            assertEquals(ApiKeyCheck.Status.UNREACHABLE, client.checkApiKey().status)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun sdkVersionMatchesThePom() {
        val pom = String(Files.readAllBytes(Paths.get("pom.xml")), Charsets.UTF_8)
        val m = Regex("<artifactId>supertext-sdk-kotlin</artifactId>\\s*<version>([^<]+)</version>").find(pom)
        assertNotNull(m)
        assertEquals(m!!.groupValues[1], SDK_VERSION)
    }

    @Test
    fun jsonWriterRoundTrips() {
        val value = linkedMapOf(
            "text" to "quote \" backslash \\ newline \n tab \t ctrl \u0001 umlaut Ä",
            "int" to 200,
            "double" to 1.5,
            "whole" to 3.0,
            "list" to listOf(true, null, "x"),
        )
        val json = Json.write(value)
        assertTrue(json.contains("\"whole\":3,"))
        val back = Json.parse(json) as Map<*, *>
        assertEquals(value["text"], back["text"])
        assertEquals(200.0, back["int"])
        assertEquals(value["list"], back["list"])
    }
}
