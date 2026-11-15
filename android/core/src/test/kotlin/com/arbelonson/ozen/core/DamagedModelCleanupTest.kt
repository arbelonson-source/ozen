package com.arbelonson.ozen.core

import java.io.File
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class DamagedModelCleanupTest {
    private val folder = Files.createTempDirectory("ozen-cleanup").toFile()
    private val file = File(folder, "ggml-model.bin")
    private val data = ByteArray(1_000) { (it * 7 % 251).toByte() }
    private val hash = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private val source = PhoneModelSource(URI("https://example.invalid/ggml-model.bin"), data.size.toLong(), hash)

    @AfterTest
    fun cleanUp() {
        folder.deleteRecursively()
    }

    private val unused = object : ReleaseFileFetching {
        override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) = error("not used")
        override fun sha256(file: File): String = hash
    }

    @Test
    fun `a damaged file is removed and the follow-up runs even when the screen asking is cancelled while it is checked`() = runBlocking {
        file.writeBytes(data.copyOf().also { it[5] = (it[5] + 1).toByte() })
        var followed = false
        lateinit var job: Job
        val cancelling = object : ReleaseFileFetching {
            override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) = error("not used")
            override fun sha256(file: File): String {
                job.cancel(CancellationException("card dismissed"))
                return HTTPFileFetcher().sha256(file)
            }
        }
        job = launch(Dispatchers.Default) {
            DamagedModelCleanup(PhoneModelDownloader(file, source, cancelling)).run { followed = true }
        }
        job.join()
        assertFalse(file.exists())
        assertTrue(followed)
    }

    @Test
    fun `a file as published is left alone and nothing follows`() = runBlocking {
        file.writeBytes(data)
        var followed = false
        val removed = DamagedModelCleanup(PhoneModelDownloader(file, source, unused), Dispatchers.Unconfined).run { followed = true }
        assertFalse(removed)
        assertFalse(followed)
        assertEquals(data.size.toLong(), file.length())
    }
}
