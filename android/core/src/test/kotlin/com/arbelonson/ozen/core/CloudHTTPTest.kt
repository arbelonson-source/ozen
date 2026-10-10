package com.arbelonson.ozen.core

import java.net.URI
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CloudHTTPTest {
    private val url = URI("https://example.com/listen")

    @Test
    fun `a request defaults to a GET with no headers, no body and a twenty second timeout`() {
        val request = CloudHTTPRequest(url)
        assertEquals("GET", request.method)
        assertEquals(emptyMap(), request.headers)
        assertEquals(null, request.body)
        assertEquals(20.0, request.timeoutSeconds)
    }

    @Test
    fun `requests and responses compare by the bytes of their bodies`() {
        assertEquals(CloudHTTPRequest(url, "POST", body = byteArrayOf(1, 2)), CloudHTTPRequest(url, "POST", body = byteArrayOf(1, 2)))
        assertNotEquals(CloudHTTPRequest(url, "POST", body = byteArrayOf(1, 2)), CloudHTTPRequest(url, "POST", body = byteArrayOf(1, 3)))
        assertEquals(CloudHTTPResponse(200, byteArrayOf(5)), CloudHTTPResponse(200, byteArrayOf(5)))
        assertNotEquals(CloudHTTPResponse(200, byteArrayOf(5)), CloudHTTPResponse(500, byteArrayOf(5)))
    }

    @Test
    fun `a fake can stand in for the network`() {
        val seen = mutableListOf<CloudHTTPRequest>()
        val http = CloudHTTP { request ->
            seen.add(request)
            CloudHTTPResponse(201, "ok".toByteArray())
        }
        val response = http.send(CloudHTTPRequest(url, "POST"))
        assertEquals(201, response.status)
        assertEquals(1, seen.size)
    }

    @Test
    fun `the java client request carries the method, headers, body and timeout`() {
        val request = CloudHTTPRequest(url, "POST", mapOf("Authorization" to "Token k"), byteArrayOf(1, 2, 3), 7.5)
        val built = JavaNetCloudHTTP().javaRequest(request)
        assertEquals("POST", built.method())
        assertEquals(url, built.uri())
        assertEquals(listOf("Token k"), built.headers().allValues("Authorization"))
        assertEquals(Duration.ofMillis(7_500), built.timeout().get())
        assertEquals(3L, built.bodyPublisher().get().contentLength())
        val get = JavaNetCloudHTTP().javaRequest(CloudHTTPRequest(url))
        assertEquals("GET", get.method())
        assertTrue(get.bodyPublisher().isPresent)
        assertEquals(0L, get.bodyPublisher().get().contentLength())
    }
}
