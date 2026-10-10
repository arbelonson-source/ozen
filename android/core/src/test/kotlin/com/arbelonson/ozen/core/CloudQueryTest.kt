package com.arbelonson.ozen.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals

class CloudQueryTest {
    private val base = URI("https://api.example.com/v1/listen")

    @Test
    fun `values are percent-encoded as UTF-8 and only unreserved characters stay`() {
        val url = CloudQuery.url(base, listOf("model" to "nova 3", "keyterm" to "דנה", "x" to "b&c=d", "ok" to "A-z0_9.~"))
        assertEquals("https://api.example.com/v1/listen?model=nova%203&keyterm=%D7%93%D7%A0%D7%94&x=b%26c%3Dd&ok=A-z0_9.~", url.toString())
    }

    @Test
    fun `settings keep their order and a name may repeat`() {
        val url = CloudQuery.url(base, listOf("keyterm" to "a", "language" to "he", "keyterm" to "b"))
        assertEquals("https://api.example.com/v1/listen?keyterm=a&language=he&keyterm=b", url.toString())
    }

    @Test
    fun `an address that cannot be built falls back to the base`() {
        assertEquals(base, CloudQuery.url(base, listOf("bad name" to "x")))
    }
}
