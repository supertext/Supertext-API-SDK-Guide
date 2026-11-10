package com.supertext.sdk

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.TreeMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/**
 * Sends one HTTP request. The default is [JdkHttpTransport] (`java.net.http.HttpClient`); replace it in tests,
 * or to route requests through your platform's HTTP stack (OkHttp, Ktor, …).
 *
 * Return every HTTP answer, whatever its status; throw [IOException] only when there is no answer (DNS, TLS,
 * connection refused, timeout). The client handles statuses, retries and errors.
 */
public fun interface Transport {
    @Throws(IOException::class, InterruptedException::class)
    public fun send(request: HttpRequestData): HttpResponseData
}

/** An HTTP request. */
public class HttpRequestData(
    public val method: String,
    public val uri: URI,
    headers: Map<String, String>,
    body: ByteArray?,
    /** How long to wait for the answer. */
    public val timeout: Duration?,
) {
    /** Request headers in the order they were added (includes `Content-Type` when there is a body). */
    public val headers: Map<String, String> = LinkedHashMap(headers)

    private val bodyBytes: ByteArray? = body?.copyOf()

    /** The body, or `null` for none. */
    public val body: ByteArray? get() = bodyBytes?.copyOf()

    override fun toString(): String = "$method $uri"
}

/**
 * An HTTP answer.
 *
 * @param headers first value per header name (looked up case-insensitively)
 * @param body the body decoded as UTF-8 (`""` for none)
 */
public class HttpResponseData(
    public val status: Int,
    headers: Map<String, String>? = null,
    body: String? = null,
) {
    private val headerMap: Map<String, String> =
        TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER).apply { if (headers != null) putAll(headers) }

    public val body: String = body ?: ""

    /** A header's value, or `null`; the name is case-insensitive. */
    public fun header(name: String): String? = headerMap[name]

    override fun toString(): String = "HTTP $status"
}

/** The default [Transport]: the JDK's [HttpClient]. */
public class JdkHttpTransport(
    /** Your own [HttpClient] (proxy, SSL context, executor, …); by default one that follows redirects, 30 s connect timeout. */
    private val http: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(30.seconds.toJavaDuration())
        .build(),
) : Transport {

    override fun send(request: HttpRequestData): HttpResponseData {
        val builder = HttpRequest.newBuilder(request.uri)
        request.timeout?.let { builder.timeout(it.toJavaDuration()) }
        for ((name, value) in request.headers) {
            builder.header(name, value)
        }
        val body = request.body
        builder.method(
            request.method,
            if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body),
        )
        // The API answers in UTF-8; decode as such whatever the Content-Type says.
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
        val headers = LinkedHashMap<String, String>()
        for ((name, values) in response.headers().map()) {
            if (values.isNotEmpty()) headers[name] = values[0]
        }
        return HttpResponseData(response.statusCode(), headers, response.body())
    }
}
