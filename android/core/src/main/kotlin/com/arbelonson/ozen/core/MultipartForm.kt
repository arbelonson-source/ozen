package com.arbelonson.ozen.core

import java.io.ByteArrayOutputStream
import java.util.UUID

internal class MultipartForm {
    val boundary = "ozen-${UUID.randomUUID().toString().uppercase()}"
    private val parts = ByteArrayOutputStream()

    val contentType: String
        get() = "multipart/form-data; boundary=$boundary"

    val body: ByteArray
        get() = parts.toByteArray() + "--$boundary--\r\n".toByteArray(Charsets.UTF_8)

    fun add(name: String, value: String) {
        parts.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray(Charsets.UTF_8))
    }

    fun add(file: String, filename: String, type: String, data: ByteArray) {
        parts.write("--$boundary\r\nContent-Disposition: form-data; name=\"$file\"; filename=\"$filename\"\r\nContent-Type: $type\r\n\r\n".toByteArray(Charsets.UTF_8))
        parts.write(data)
        parts.write("\r\n".toByteArray(Charsets.UTF_8))
    }
}
