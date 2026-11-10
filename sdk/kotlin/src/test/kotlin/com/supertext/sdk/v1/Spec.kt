package com.supertext.sdk.v1

import com.supertext.sdk.HttpRequestData
import com.supertext.sdk.HttpResponseData
import com.supertext.sdk.Transport
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Loads the shared test vectors from the repository's spec/ folder. */
object Spec {
    fun dir(): Path {
        val dir = Paths.get(System.getProperty("supertext.spec.dir", "../../spec"))
        check(Files.isDirectory(dir)) { "spec folder not found: ${dir.toAbsolutePath()}" }
        return dir
    }

    @Suppress("UNCHECKED_CAST")
    fun load(name: String): Map<String, Any?> = Json.parse(String(Files.readAllBytes(dir().resolve(name)), Charsets.UTF_8)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun list(map: Map<String, Any?>, key: String): List<Map<String, Any?>> = map[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    fun strings(value: Any?): Map<String, String>? = value as Map<String, String>?

    @Suppress("UNCHECKED_CAST")
    fun obj(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    fun int(value: Any?): Int = (value as Double).toInt()
}

/** Records requests and answers them from a queue (or a function). */
class FakeTransport : Transport {
    val requests = ArrayList<HttpRequestData>()
    private val answers = ArrayDeque<Any>()
    private var handler: ((HttpRequestData) -> HttpResponseData)? = null

    fun answer(status: Int, body: String?, headers: Map<String, String> = emptyMap()): FakeTransport {
        answers.addLast(HttpResponseData(status, headers, body))
        return this
    }

    fun fail(error: IOException): FakeTransport {
        answers.addLast(error)
        return this
    }

    fun handler(handler: (HttpRequestData) -> HttpResponseData): FakeTransport {
        this.handler = handler
        return this
    }

    override fun send(request: HttpRequestData): HttpResponseData {
        requests.add(request)
        handler?.let { return it(request) }
        return when (val next = answers.removeFirstOrNull()) {
            null -> throw AssertionError("unexpected request ${request.method} ${request.uri}")
            is IOException -> throw next
            else -> next as HttpResponseData
        }
    }

    fun lines(): List<String> = requests.map { "${it.method} ${it.uri}" }

    companion object {
        fun body(request: HttpRequestData): String? = request.body?.let { String(it, Charsets.UTF_8) }
    }
}

/** A sleeper that doesn't wait and records what it was asked. */
class RecordingSleeper : Sleeper {
    val slept = ArrayList<Long>()

    override fun sleep(duration: kotlin.time.Duration) {
        slept.add(duration.inWholeMilliseconds)
    }
}
