package com.arbelonson.ozen.core

import java.net.URI
import java.net.URISyntaxException

internal object CloudQuery {
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    fun url(base: URI, settings: List<Pair<String, String>>): URI {
        val query = settings.map { (name, value) -> "$name=${percentEncoded(value)}" }
        return try {
            URI(base.toString() + "?" + query.joinToString("&"))
        } catch (_: URISyntaxException) {
            base
        }
    }

    private fun percentEncoded(value: String): String = buildString {
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val unit = byte.toInt() and 0xFF
            if (unit < 0x80 && unit.toChar() in UNRESERVED) append(unit.toChar()) else append("%%%02X".format(unit))
        }
    }
}
