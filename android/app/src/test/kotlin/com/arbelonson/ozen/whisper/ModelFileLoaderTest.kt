package com.arbelonson.ozen.whisper

import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PhoneModelDownloader
import com.arbelonson.ozen.core.PhoneModelSource
import com.arbelonson.ozen.core.ReleaseFileFetching
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

private class ChunkedModel(private val data: ByteArray, private val pause: Long = 0) : ReleaseFileFetching {
    @Volatile var served = 0

    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
        FileOutputStream(appendingTo, from > 0).use { out ->
            var index = from.toInt()
            while (index < data.size) {
                val end = minOf(index + 1_000, data.size)
                out.write(data, index, end - index)
                out.flush()
                served = end
                received((end - index).toLong())
                index = end
                Thread.sleep(pause)
            }
        }
    }

    override fun sha256(file: File): String = digest(file.readBytes())
}

private class Unreachable : ReleaseFileFetching {
    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) = throw IOException("no route to host")

    override fun sha256(file: File): String = ""
}

private fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

class ModelFileLoaderTest {
    private val folder = Files.createTempDirectory("ozen-loader").toFile()
    private val file = File(folder, "ggml-model.bin")
    private val data = ByteArray(50_000) { (it * 31 % 251).toByte() }
    private val source = PhoneModelSource(URI("https://example.invalid/ggml-model.bin"), data.size.toLong(), digest(data))
    private val opened = mutableListOf<String>()

    private fun loader(fetcher: ReleaseFileFetching?) = ModelFileLoader(
        file,
        "/lib",
        download = fetcher?.let { PhoneModelDownloader(file, source, it) },
    ) { path, _ ->
        opened.add(path)
        null
    }

    @AfterTest
    fun clean() {
        folder.deleteRecursively()
    }

    @Test
    fun `a model not on the phone with nowhere to download it from is a missing model, as before`() = runBlocking {
        val loader = loader(null)
        assertNull(loader.pendingDownloadMegabytes())
        val failure = assertFailsWith<EngineUnavailability> { loader.load("he", cellularDownloadAllowed = false) {} }
        assertEquals(EngineUnavailability.Kind.ModelNotOnDevice, failure.kind)
    }

    @Test
    fun `only a model whose file is there is on the phone, with or without a place to fetch it from`() = runBlocking {
        assertFalse(loader(null).hasModelOnPhone())
        assertFalse(loader(ChunkedModel(data)).hasModelOnPhone())
        file.writeBytes(data)
        assertTrue(loader(null).hasModelOnPhone())
    }

    @Test
    fun `a missing model with a source is counted, downloaded with its progress shown, then opened under its own name`() = runBlocking {
        val loader = loader(ChunkedModel(data))
        assertEquals(1, loader.pendingDownloadMegabytes())
        val steps = mutableListOf<EnginePreparationProgress>()
        val failure = assertFailsWith<EngineUnavailability> { loader.load("en", cellularDownloadAllowed = true) { steps.add(it) } }
        assertEquals(EngineUnavailability.Kind.ModelLoadFailed, failure.kind)
        assertContentEquals(data, file.readBytes())
        assertEquals(listOf(file.path), opened)
        val downloading = steps.filter { it.stage == EnginePreparationProgress.Stage.DownloadingModel }
        assertTrue(downloading.size > 1)
        assertEquals(1.0, downloading.last().fraction)
        assertEquals(EnginePreparationProgress.Stage.LoadingModel, steps.last().stage)
        assertNull(loader.pendingDownloadMegabytes())
    }

    @Test
    fun `a download that fails is a failed download, which can be tried again, not a missing model`() = runBlocking {
        val failure = assertFailsWith<EngineUnavailability> { loader(Unreachable()).load("en", cellularDownloadAllowed = true) {} }
        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, failure.kind)
        assertFalse(file.exists())
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `a download whose Wi-Fi drops stops rather than going on over the phone plan, and goes on from there on Wi-Fi`() = runBlocking {
        var network = NetworkConditions.wifi
        val served = ChunkedModel(data)
        val loader = ModelFileLoader(file, "/lib", download = PhoneModelDownloader(file, source, served), network = { network }) { path, _ ->
            opened.add(path)
            null
        }
        val failure = assertFailsWith<EngineUnavailability> {
            loader.load("en", cellularDownloadAllowed = false) { if ((it.fraction ?: 0.0) > 0.3) network = NetworkConditions.cellular }
        }
        assertEquals(EngineUnavailability.Kind.ModelDownloadFailed, failure.kind)
        val kept = File(file.path + ".part").length()
        assertTrue(kept in 1 until data.size, "went on over the phone plan: $kept of ${data.size}")
        assertFalse(file.exists())

        network = NetworkConditions.wifi
        assertEquals(EngineUnavailability.Kind.ModelLoadFailed, assertFailsWith<EngineUnavailability> { loader.load("en", cellularDownloadAllowed = false) {} }.kind)
        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `with downloads on cellular data allowed, losing Wi-Fi does not stop the download`() = runBlocking {
        var network = NetworkConditions.wifi
        val loader = ModelFileLoader(file, "/lib", download = PhoneModelDownloader(file, source, ChunkedModel(data)), network = { network }) { path, _ ->
            opened.add(path)
            null
        }
        val failure = assertFailsWith<EngineUnavailability> {
            loader.load("en", cellularDownloadAllowed = true) { if ((it.fraction ?: 0.0) > 0.3) network = NetworkConditions.cellular }
        }
        assertEquals(EngineUnavailability.Kind.ModelLoadFailed, failure.kind)
        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun `stopping the captions mid-download stops the download and keeps what came for next time`() = runBlocking {
        val served = ChunkedModel(data, pause = 20)
        val loader = loader(served)
        val started = CompletableDeferred<Unit>()
        val loading = launch(Dispatchers.Default) {
            loader.load("en", cellularDownloadAllowed = true) { if ((it.fraction ?: 0.0) > 0.0) started.complete(Unit) }
        }
        withTimeout(10_000) { started.await() }
        loading.cancel()
        withTimeout(10_000) { loading.join() }
        val kept = File(file.path + ".part").length()
        assertEquals(served.served.toLong(), kept)
        assertTrue(kept in 1 until data.size)
        assertFalse(file.exists())
        assertTrue(opened.isEmpty())
    }
}
