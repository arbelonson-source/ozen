package com.arbelonson.ozen.core

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class MultipartFormPart(val name: String, val headers: String, val body: ByteArray) {
    val text: String get() = String(body, Charsets.UTF_8)
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int = 0): Int {
    var start = from
    while (start <= haystack.size - needle.size) {
        if (needle.indices.all { haystack[start + it] == needle[it] }) return start
        start += 1
    }
    return -1
}

private fun formParts(request: CloudHTTPRequest): List<MultipartFormPart> {
    val prefix = "multipart/form-data; boundary="
    val type = assertNotNull(request.headers["Content-Type"])
    assertTrue(type.startsWith(prefix))
    val boundary = type.drop(prefix.length)
    val body = assertNotNull(request.body)
    val delimiter = "--$boundary".toByteArray(Charsets.UTF_8)
    val lineBreak = "\r\n".toByteArray(Charsets.UTF_8)
    val found = mutableListOf<MultipartFormPart>()
    val first = indexOf(body, delimiter)
    if (first < 0) return found
    var position = first + delimiter.size
    while (true) {
        val next = indexOf(body, delimiter, position)
        if (next < 0) break
        val part = body.copyOfRange(position, next)
        position = next + delimiter.size
        val split = indexOf(part, "\r\n\r\n".toByteArray(Charsets.UTF_8))
        if (split < 0) continue
        val headers = String(part.copyOfRange(0, split), Charsets.UTF_8)
        var content = part.copyOfRange(split + 4, part.size)
        assertTrue(
            content.size >= 2 && content.copyOfRange(content.size - 2, content.size).contentEquals(lineBreak),
            "a part must end with a line break before the next boundary",
        )
        content = content.copyOfRange(0, maxOf(content.size - 2, 0))
        val start = headers.indexOf("name=\"")
        if (start < 0) continue
        found.add(MultipartFormPart(headers.substring(start + 6).takeWhile { it != '"' }, headers, content))
    }
    val rest = body.copyOfRange(position, body.size)
    assertTrue(rest.size >= 4 && rest.copyOfRange(0, 4).contentEquals("--\r\n".toByteArray(Charsets.UTF_8)))
    return found
}

class MultipartFormTest {
    @Test
    fun `fields keep their order, a name may repeat, and a file keeps its bytes, name and type`() {
        val form = MultipartForm()
        form.add("model_id", "scribe_v2")
        form.add("keyterms", "דנה")
        form.add("keyterms", "ד\"ר כהן")
        val wav = byteArrayOf(0, 1, 2, 13, 10, 45, 45, 255.toByte())
        form.add(file = "file", filename = "speech.wav", type = "audio/wav", data = wav)
        val request = CloudHTTPRequest(URI("https://example.com"), "POST", mapOf("Content-Type" to form.contentType), form.body)
        val parts = formParts(request)
        assertEquals(listOf("model_id", "keyterms", "keyterms", "file"), parts.map { it.name })
        assertEquals(listOf("scribe_v2", "דנה", "ד\"ר כהן"), parts.map { it.text }.take(3))
        assertContentEquals(wav, parts.last().body)
        assertTrue(parts.last().headers.contains("filename=\"speech.wav\""))
        assertTrue(parts.last().headers.contains("Content-Type: audio/wav"))
    }

    @Test
    fun `each form has its own boundary`() {
        assertNotEquals(MultipartForm().boundary, MultipartForm().boundary)
    }
}
