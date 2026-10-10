package com.arbelonson.ozen.core

import java.net.HttpURLConnection
import java.net.URI

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

class UrlConnectionCloudHTTP : CloudHTTP {
    override fun send(request: CloudHTTPRequest): CloudHTTPResponse {
        val connection = request.url.toURL().openConnection() as HttpURLConnection
        try {
            configure(connection, request)
            request.body?.let { body -> connection.outputStream.use { it.write(body) } }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            return CloudHTTPResponse(status, stream?.use { it.readBytes() } ?: ByteArray(0))
        } finally {
            connection.disconnect()
        }
    }

    internal fun configure(connection: HttpURLConnection, request: CloudHTTPRequest) {
        val millis = (request.timeoutSeconds * 1_000).toInt()
        connection.connectTimeout = millis
        connection.readTimeout = millis
        connection.requestMethod = request.method
        for ((name, value) in request.headers) {
            connection.setRequestProperty(name, value)
        }
        val body = request.body ?: return
        connection.doOutput = true
        connection.setFixedLengthStreamingMode(body.size)
    }
}
