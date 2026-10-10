package com.arbelonson.ozen.core

import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Serves assets from memory, in chunks, and remembers every fetch. Its
 * checksum is the byte sum, padded to the shape of a real one, so a
 * manifest can be written for any content.
 */
private class FakeReleaseFetcher(private val assets: Map<String, ByteArray>) : ReleaseFileFetching {
    val fetches = mutableListOf<Pair<String, Long>>()
    var refusesResume = false
    var chunk = 5

    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) {
        val asset = url.path.substringAfterLast('/')
        synchronized(fetches) { fetches.add(asset to from) }
        val data = assets[asset] ?: throw java.io.FileNotFoundException(asset)
        var start = from.toInt()
        if (from > 0 && refusesResume) {
            appendingTo.writeBytes(ByteArray(0))
            received(-from)
            start = 0
        }
        if (!appendingTo.exists()) {
            appendingTo.writeBytes(ByteArray(0))
        }
        RandomAccessFile(appendingTo, "rw").use { handle ->
            handle.seek(handle.length())
            var index = start
            while (index < data.size) {
                val end = minOf(index + chunk, data.size)
                handle.write(data, index, end - index)
                received((end - index).toLong())
                index = end
            }
        }
    }

    override fun sha256(file: File): String = checksum(file.readBytes())

    companion object {
        fun checksum(data: ByteArray): String = "%064x".format(data.sumOf { it.toInt() and 0xFF })
    }
}

private fun temporaryFolder(): File = Files.createTempDirectory("ozen-release-${UUID.randomUUID()}").toFile()

private fun manifestData(files: List<ReleaseModelFile>): ByteArray {
    val array = JsonArray(
        files.map {
            JsonObject(
                mapOf(
                    "path" to JsonPrimitive(it.path),
                    "asset" to JsonPrimitive(it.asset),
                    "size" to JsonPrimitive(it.size),
                    "sha256" to JsonPrimitive(it.sha256),
                ),
            )
        },
    )
    return JsonObject(mapOf("files" to array)).toString().toByteArray(Charsets.UTF_8)
}

private fun releaseFile(path: String, asset: String, data: ByteArray) =
    ReleaseModelFile(path, asset, data.size.toLong(), FakeReleaseFetcher.checksum(data))

private class ReleaseProgressLog {
    private val log = mutableListOf<Double>()

    fun record(value: Double) {
        synchronized(log) { log.add(value) }
    }

    val values: List<Double> get() = synchronized(log) { log.toList() }
}

class ReleaseModelTest {
    private val good = ReleaseModelFile(
        path = "AudioEncoder.mlpackage/Data/com.apple.CoreML/weights/weight.bin",
        asset = "AudioEncoder.mlpackage__Data__com.apple.CoreML__weights__weight.bin",
        size = 12,
        sha256 = "ab".repeat(32),
    )
    private val a = ReleaseModelFile("a.bin", "a.bin", 100, "0".repeat(64))
    private val b = ReleaseModelFile("sub/b.bin", "sub__b.bin", 50, "0".repeat(64))
    private val weights = ByteArray(40) { it.toByte() }
    private val config = "{\"d_model\": 1280}".toByteArray(Charsets.UTF_8)
    private val weightsAsset = "AudioEncoder.mlpackage__weights__weight.bin"
    private val weightsPath = "AudioEncoder.mlpackage/Data/com.apple.CoreML/weights/weight.bin"

    private fun release(): Pair<List<ReleaseModelFile>, Map<String, ByteArray>> {
        val files = listOf(
            releaseFile(weightsPath, weightsAsset, weights),
            releaseFile("config.json", "config.json", config),
        )
        val assets = mapOf("manifest.json" to manifestData(files), weightsAsset to weights, "config.json" to config)
        return files to assets
    }

    private fun parseFailure(data: ByteArray): ReleaseModelManifest.ParseFailure =
        assertFailsWith<ReleaseModelManifest.ParseFailure> { ReleaseModelManifest.parse(data) }

    @Test
    fun `a well-formed manifest parses and adds up its bytes`() {
        val other = ReleaseModelFile("config.json", "config.json", 30, "0".repeat(64))
        val manifest = ReleaseModelManifest.parse(manifestData(listOf(good, other)))
        assertEquals(2, manifest.files.size)
        assertEquals(42, manifest.totalBytes)
    }

    @Test
    fun `paths that could escape the model folder are refused`() {
        for (path in listOf("../elsewhere", "/etc/passwd", "a//b", "./x", "a/../b", "")) {
            assertEquals(ReleaseModelManifest.ParseFailure.BadPath(path), parseFailure(manifestData(listOf(good.copy(path = path)))))
        }
    }

    @Test
    fun `an asset name with a slash, a zero size, a bad checksum, a duplicate and an empty list are refused`() {
        assertEquals(
            ReleaseModelManifest.ParseFailure.BadPath("dir/file"),
            parseFailure(manifestData(listOf(good.copy(asset = "dir/file")))),
        )
        assertEquals(
            ReleaseModelManifest.ParseFailure.BadSize(good.path),
            parseFailure(manifestData(listOf(good.copy(size = 0)))),
        )
        assertEquals(
            ReleaseModelManifest.ParseFailure.BadChecksum(good.path),
            parseFailure(manifestData(listOf(good.copy(sha256 = "abc")))),
        )
        assertEquals(
            ReleaseModelManifest.ParseFailure.BadChecksum(good.path),
            parseFailure(manifestData(listOf(good.copy(sha256 = "zz".repeat(32))))),
        )
        assertEquals(ReleaseModelManifest.ParseFailure.Duplicate(good.path), parseFailure(manifestData(listOf(good, good))))
        assertEquals(ReleaseModelManifest.ParseFailure.NoFiles, parseFailure(manifestData(emptyList())))
        assertEquals(ReleaseModelManifest.ParseFailure.NotJSON, parseFailure("nope".toByteArray()))
    }

    @Test
    fun `asset addresses point at GitHub's release download path`() {
        val url = ReleaseModelURLs.asset("arbelonson-source/ozen", "model-ivrit-turbo-1", "manifest.json")
        assertEquals("https://github.com/arbelonson-source/ozen/releases/download/model-ivrit-turbo-1/manifest.json", url.toString())
        assertEquals("arbelonson-source/ozen-models", ReleaseModelDownloader.DEFAULT_REPOSITORY)
    }

    @Test
    fun `nothing on disk fetches everything from the start`() {
        val steps = ReleaseDownloadPlan.steps(ReleaseModelManifest(listOf(a, b))) { null }
        assertEquals(listOf(ReleaseDownloadStep(a, 0), ReleaseDownloadStep(b, 0)), steps)
    }

    @Test
    fun `a whole file is skipped, a cut-off one continues, an oversized one starts over`() {
        val sizes = mapOf("a.bin" to 100L, "sub/b.bin" to 20L)
        val steps = ReleaseDownloadPlan.steps(ReleaseModelManifest(listOf(a, b))) { sizes[it] }
        assertEquals(listOf(ReleaseDownloadStep(b, 20)), steps)

        val oversized = ReleaseDownloadPlan.steps(ReleaseModelManifest(listOf(a))) { 101L }
        assertEquals(listOf(ReleaseDownloadStep(a, 0)), oversized)
    }

    @Test
    fun `a fresh download lands every file where the manifest says, with progress climbing to one`() {
        val (_, assets) = release()
        val fetcher = FakeReleaseFetcher(assets)
        val folder = temporaryFolder()
        val seen = ReleaseProgressLog()

        val manifest = ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { seen.record(it) }

        assertEquals(2, manifest.files.size)
        assertContentEquals(weights, File(folder, weightsPath).readBytes())
        assertContentEquals(config, File(folder, "config.json").readBytes())
        val values = seen.values
        assertEquals(0.0, values.first())
        assertEquals(1.0, values.last())
        assertEquals(values.sorted(), values)
        assertEquals(listOf("manifest.json", weightsAsset, "config.json"), fetcher.fetches.map { it.first })
    }

    @Test
    fun `a first download makes its own folder, and the bar moves while the files come in`() {
        val (_, assets) = release()
        val folder = File(temporaryFolder(), "models/m1")
        val seen = ReleaseProgressLog()

        ReleaseModelDownloader(fetcher = FakeReleaseFetcher(assets)).download("m1", folder) { seen.record(it) }

        assertTrue(File(folder, "config.json").exists())
        assertTrue(seen.values.any { it > 0 && it < 1 }, "${seen.values}")
    }

    @Test
    fun `a download cut off part way continues from where each file stopped`() {
        val (_, assets) = release()
        val fetcher = FakeReleaseFetcher(assets)
        val folder = temporaryFolder()
        val partial = File(folder, weightsPath)
        partial.parentFile.mkdirs()
        partial.writeBytes(weights.copyOf(17))
        File(folder, "config.json").writeBytes(config)
        val seen = ReleaseProgressLog()

        ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { seen.record(it) }

        assertContentEquals(weights, partial.readBytes())
        assertEquals(listOf("manifest.json", weightsAsset), fetcher.fetches.map { it.first })
        assertEquals(17L, fetcher.fetches.last().second)
        // Starts from the bytes already there, not from nothing.
        assertTrue(seen.values.first() > 0.5)
        assertEquals(1.0, seen.values.last())
    }

    @Test
    fun `a leftover file longer than the manifest's is fetched again from the start, not added to`() {
        val (_, assets) = release()
        val fetcher = FakeReleaseFetcher(assets)
        val folder = temporaryFolder()
        val leftover = File(folder, "config.json")
        leftover.writeBytes(config + config)

        ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { }

        assertContentEquals(config, leftover.readBytes())
        assertTrue(fetcher.fetches.any { it.first == "config.json" && it.second == 0L })
    }

    @Test
    fun `a server that will not resume makes the file start over and still lands it whole`() {
        val (_, assets) = release()
        val fetcher = FakeReleaseFetcher(assets)
        fetcher.refusesResume = true
        val folder = temporaryFolder()
        val partial = File(folder, weightsPath)
        partial.parentFile.mkdirs()
        partial.writeBytes(weights.copyOf(17))
        val seen = ReleaseProgressLog()

        ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { seen.record(it) }

        assertContentEquals(weights, partial.readBytes())
        assertEquals(1.0, seen.values.last())
        assertTrue(seen.values.all { it >= 0 && it <= 1 })
    }

    @Test
    fun `a file that arrived whole but wrong is deleted and reported, and the next attempt fetches it again`() {
        val (_, assets) = release()
        val fetcher = FakeReleaseFetcher(assets)
        val folder = temporaryFolder()
        val wrong = File(folder, "config.json")
        wrong.writeBytes(ByteArray(config.size) { 0x41 })

        val failure = assertFailsWith<ReleaseModelDownloader.Failure> {
            ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { }
        }
        assertEquals(ReleaseModelDownloader.Failure.ChecksumMismatch("config.json"), failure)
        assertFalse(wrong.exists())

        ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { }
        assertContentEquals(config, wrong.readBytes())
    }

    @Test
    fun `a Wi-Fi sign-in page answering for every file costs one attempt, not one per file`() {
        val (files, assets) = release()
        val page = "<p>sign in</p>".toByteArray()
        val portalAssets = assets.mapValues { page }.toMutableMap()
        portalAssets[ReleaseModelManifest.ASSET_NAME] = manifestData(files)
        val folder = temporaryFolder()

        assertFailsWith<ReleaseModelDownloader.Failure> {
            ReleaseModelDownloader(fetcher = FakeReleaseFetcher(portalAssets)).download("m1", folder) { }
        }
        for (file in files) {
            assertFalse(File(folder, file.path).exists())
        }

        ReleaseModelDownloader(fetcher = FakeReleaseFetcher(assets)).download("m1", folder) { }
        assertContentEquals(config, File(folder, "config.json").readBytes())
        assertContentEquals(weights, File(folder, files[0].path).readBytes())
    }

    @Test
    fun `a manifest that can't be trusted stops the download before any file is written`() {
        val fetcher = FakeReleaseFetcher(mapOf("manifest.json" to "not json".toByteArray()))
        val folder = temporaryFolder()

        val failure = assertFailsWith<ReleaseModelDownloader.Failure> {
            ReleaseModelDownloader(fetcher = fetcher).download("m1", folder) { }
        }
        assertEquals(ReleaseModelDownloader.Failure.BadManifest(ReleaseModelManifest.ParseFailure.NotJSON), failure)
        assertEquals(1, fetcher.fetches.size)
    }

    @Test
    fun `a manifest with a missing field or a wrong type is not JSON for our purposes`() {
        assertEquals(ReleaseModelManifest.ParseFailure.NotJSON, parseFailure("""{"files":[{"path":"a","asset":"a","size":1}]}""".toByteArray()))
        assertEquals(ReleaseModelManifest.ParseFailure.NotJSON, parseFailure("""{"files":[{"path":"a","asset":"a","size":"1","sha256":"${"0".repeat(64)}"}]}""".toByteArray()))
        assertEquals(ReleaseModelManifest.ParseFailure.NotJSON, parseFailure("""{"other":[]}""".toByteArray()))
    }
}
