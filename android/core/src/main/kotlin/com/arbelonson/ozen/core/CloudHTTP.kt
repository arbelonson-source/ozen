package com.arbelonson.ozen.core

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class CloudHTTPRequest(
    val url: URI,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutSeconds: Double = 20.0,
) {
    override fun equals(other: Any?): Boolean =
        other is CloudHTTPRequest && other.url == url && other.method == method && other.headers == headers &&
            other.body.contentEquals(body) && other.timeoutSeconds == timeoutSeconds

    override fun hashCode(): Int =
        listOf(url, method, headers, body?.contentHashCode(), timeoutSeconds).hashCode()

    override fun toString(): String = "CloudHTTPRequest($method $url)"
}

class CloudHTTPResponse(val status: Int, val body: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is CloudHTTPResponse && other.status == status && other.body.contentEquals(body)

    override fun hashCode(): Int = 31 * status + body.contentHashCode()

    override fun toString(): String = "CloudHTTPResponse($status, ${body.size} bytes)"
}

fun interface CloudHTTP {
    fun send(request: CloudHTTPRequest): CloudHTTPResponse
}

class JavaNetCloudHTTP(private val client: HttpClient = HttpClient.newHttpClient()) : CloudHTTP {
    override fun send(request: CloudHTTPRequest): CloudHTTPResponse {
        val response = client.send(javaRequest(request), HttpResponse.BodyHandlers.ofByteArray())
        return CloudHTTPResponse(response.statusCode(), response.body())
    }

    internal fun javaRequest(request: CloudHTTPRequest): HttpRequest {
        val builder = HttpRequest.newBuilder(request.url)
            .timeout(Duration.ofMillis((request.timeoutSeconds * 1_000).toLong()))
        val body = request.body
        builder.method(request.method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        for ((name, value) in request.headers) {
            builder.header(name, value)
        }
        return builder.build()
    }
}
