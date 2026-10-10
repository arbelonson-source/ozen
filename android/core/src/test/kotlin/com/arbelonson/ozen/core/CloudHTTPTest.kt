package com.arbelonson.ozen.core

import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `the connection carries the method, headers, body length and timeout, without connecting`() {
        val request = CloudHTTPRequest(url, "POST", mapOf("Authorization" to "Token k", "Content-Type" to "audio/wav"), byteArrayOf(1, 2, 3), 7.5)
        val post = url.toURL().openConnection() as HttpURLConnection
        UrlConnectionCloudHTTP().configure(post, request)
        assertEquals("POST", post.requestMethod)
        assertEquals(url.toURL(), post.url)
        assertEquals("audio/wav", post.getRequestProperty("Content-Type"))
        assertEquals(7_500, post.connectTimeout)
        assertEquals(7_500, post.readTimeout)
        assertTrue(post.doOutput)
        val get = url.toURL().openConnection() as HttpURLConnection
        UrlConnectionCloudHTTP().configure(get, CloudHTTPRequest(url))
        assertEquals("GET", get.requestMethod)
        assertEquals(20_000, get.readTimeout)
        assertFalse(get.doOutput)
    }
}
