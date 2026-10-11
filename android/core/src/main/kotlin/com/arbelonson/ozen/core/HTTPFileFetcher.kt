package com.arbelonson.ozen.core

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class HTTPFileFetcher(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
) : ReleaseFileFetching {
    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
        val connection = open(url, from)
        try {
            val status = connection.responseCode
            val resumed = from > 0 && status == HttpURLConnection.HTTP_PARTIAL
            if (status != HttpURLConnection.HTTP_OK && !resumed) throw IOException("HTTP $status from ${url.host}")
            if (resumed && rangeStart(connection.getHeaderField("Content-Range")) != from) {
                throw IOException("${url.host} sent a part starting elsewhere than byte $from")
            }
            if (from > 0 && !resumed) received(-from)
            FileOutputStream(appendingTo, resumed).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        received(count.toLong())
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun open(url: URI, from: Long): HttpURLConnection {
        var target = url
        repeat(MAX_REDIRECTS + 1) {
            val connection = target.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
            val status = connection.responseCode
            if (status !in 300..399) return connection
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            target = redirectTarget(target, location ?: throw IOException("HTTP $status without a Location from ${target.host}"))
        }
        throw IOException("too many redirects from ${url.host}")
    }

    private fun rangeStart(header: String?): Long? =
        header?.removePrefix("bytes ")?.substringBefore('-')?.trim()?.toLongOrNull()

    companion object {
        private const val MAX_REDIRECTS = 5

        fun redirectTarget(from: URI, location: String): URI {
            val target = try {
                from.resolve(location)
            } catch (bad: IllegalArgumentException) {
                throw IOException("unreadable redirect from ${from.host}", bad)
            }
            if (from.scheme == "https" && target.scheme != "https") throw IOException("${from.host} redirected off https")
            return target
        }
    }
}
