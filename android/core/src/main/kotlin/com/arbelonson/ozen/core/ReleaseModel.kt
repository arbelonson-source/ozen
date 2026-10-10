package com.arbelonson.ozen.core

import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** Where a Whisper model's files come from. */
sealed class WhisperModelSource {
    /** Argmax's WhisperKit model hub on Hugging Face; WhisperKit fetches it. */
    data object WhisperKitHub : WhisperModelSource()

    /**
     * A release of Ozen's own GitHub repository, holding the model's files
     * as release assets beside a manifest. For models nobody publishes in
     * WhisperKit's format, such as ivrit.ai's Hebrew-trained Whisper.
     */
    data class OzenRelease(val tag: String) : WhisperModelSource()
}

/**
 * One file of a model published as release assets: where it goes inside
 * the model folder, which asset holds it, and how to know it arrived whole.
 */
data class ReleaseModelFile(val path: String, val asset: String, val size: Long, val sha256: String)

/**
 * The `manifest.json` asset of a model release: every file of the model.
 * Core ML packages are directories of a few files each, and a release
 * holds flat assets, so the manifest is what turns one back into the other.
 */
data class ReleaseModelManifest(val files: List<ReleaseModelFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }

    sealed class ParseFailure : Exception() {
        data object NotJSON : ParseFailure()
        data object NoFiles : ParseFailure()
        data class BadPath(val path: String) : ParseFailure()
        data class BadSize(val path: String) : ParseFailure()
        data class BadChecksum(val path: String) : ParseFailure()
        data class Duplicate(val name: String) : ParseFailure()
    }

    companion object {
        const val ASSET_NAME = "manifest.json"

        /**
         * Decodes a manifest and refuses one that could write outside the
         * model folder or that no download could ever verify.
         */
        fun parse(data: ByteArray): ReleaseModelManifest {
            val manifest = decode(data) ?: throw ParseFailure.NotJSON
            if (manifest.files.isEmpty()) throw ParseFailure.NoFiles
            val paths = HashSet<String>()
            val assets = HashSet<String>()
            for (file in manifest.files) {
                val parts = file.path.split("/")
                if (file.path.isEmpty() || file.path.startsWith("/") || "" in parts || "." in parts || ".." in parts) {
                    throw ParseFailure.BadPath(file.path)
                }
                if (file.asset.isEmpty() || file.asset.contains("/")) throw ParseFailure.BadPath(file.asset)
                if (file.size <= 0) throw ParseFailure.BadSize(file.path)
                if (file.sha256.length != 64 || !file.sha256.all { isHexDigit(it) }) throw ParseFailure.BadChecksum(file.path)
                if (!paths.add(file.path)) throw ParseFailure.Duplicate(file.path)
                if (!assets.add(file.asset)) throw ParseFailure.Duplicate(file.asset)
            }
            return manifest
        }

        private fun isHexDigit(character: Char): Boolean =
            character in '0'..'9' || character in 'a'..'f' || character in 'A'..'F' ||
                character in '０'..'９' || character in 'Ａ'..'Ｆ' || character in 'ａ'..'ｆ'

        private fun decode(data: ByteArray): ReleaseModelManifest? = try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            val root = Json.parseToJsonElement(decoder.decode(ByteBuffer.wrap(data)).toString()).jsonObject
            ReleaseModelManifest(
                root.getValue("files").jsonArray.map { element ->
                    val file = element.jsonObject
                    ReleaseModelFile(
                        path = text(file.getValue("path").jsonPrimitive),
                        asset = text(file.getValue("asset").jsonPrimitive),
                        size = file.getValue("size").jsonPrimitive.also { check(!it.isString) }.long,
                        sha256 = text(file.getValue("sha256").jsonPrimitive),
                    )
                },
            )
        } catch (_: Exception) {
            null
        }

        private fun text(primitive: kotlinx.serialization.json.JsonPrimitive): String {
            check(primitive.isString)
            return primitive.content
        }
    }
}

object ReleaseModelURLs {
    /** GitHub's download address for one asset of a release. */
    fun asset(repository: String, tag: String, name: String): URI =
        URI("https", "github.com", "/$repository/releases/download/$tag/$name", null)
}

/** One file still to fetch, and where its download continues from. */
data class ReleaseDownloadStep(val file: ReleaseModelFile, val resumeFrom: Long)

object ReleaseDownloadPlan {
    /**
     * What a download cut off part way still has to fetch. A file already
     * at its full size is skipped here and checked by its checksum at the
     * end; one larger than it should be is started over.
     */
    fun steps(manifest: ReleaseModelManifest, sizeOnDisk: (String) -> Long?): List<ReleaseDownloadStep> =
        manifest.files.mapNotNull { file ->
            val onDisk = sizeOnDisk(file.path) ?: 0
            if (onDisk == file.size) null else ReleaseDownloadStep(file, if (onDisk < file.size) onDisk else 0)
        }
}

/**
 * The two things a release download needs from the network and the disk,
 * kept behind an interface so the download itself is tested here with fakes.
 * The Android app supplies the real one (an HTTP client that sends a Range
 * header and hashes the file with SHA-256).
 */
interface ReleaseFileFetching {
    /**
     * Appends the bytes of [url] from [offset] on to the file at
     * [destination], reporting each chunk as it lands. A server that will
     * not resume from [offset] makes the file start over: the fetcher
     * truncates it and reports `-offset` once before the first chunk.
     */
    fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit)

    fun sha256(file: File): String
}

/**
 * Downloads a model published as release assets into a model folder,
 * file by file, continuing where a cut-off download stopped.
 */
class ReleaseModelDownloader(
    val repository: String = DEFAULT_REPOSITORY,
    val fetcher: ReleaseFileFetching,
) {
    sealed class Failure : Exception() {
        data class BadManifest(val reason: ReleaseModelManifest.ParseFailure) : Failure()

        /** A file that arrived whole but not as published. It has been deleted, so the next attempt fetches it again. */
        data class ChecksumMismatch(val path: String) : Failure()
    }

    /**
     * Fetches (or resumes) every file of the release at [tag] into
     * [into], reporting 0..1 progress by bytes, and returns the manifest.
     * Every file is checked against its checksum before this returns.
     */
    fun download(tag: String, into: File, progress: (Double) -> Unit): ReleaseModelManifest {
        Files.createDirectories(into.toPath())

        val manifestFile = File(into, ReleaseModelManifest.ASSET_NAME)
        manifestFile.delete()
        fetcher.fetch(assetURL(tag, ReleaseModelManifest.ASSET_NAME), 0, manifestFile) { }
        val manifest = try {
            ReleaseModelManifest.parse(manifestFile.readBytes())
        } catch (failure: ReleaseModelManifest.ParseFailure) {
            throw Failure.BadManifest(failure)
        }

        val steps = ReleaseDownloadPlan.steps(manifest) { path -> fileSize(File(into, path)) }
        val total = manifest.totalBytes
        val counter = ReleaseByteCounter(total - steps.sumOf { it.file.size - it.resumeFrom })
        progress(counter.fraction(total))

        for (step in steps) {
            val destination = File(into, step.file.path)
            Files.createDirectories(destination.absoluteFile.parentFile.toPath())
            if (step.resumeFrom == 0L) {
                destination.delete()
            }
            fetcher.fetch(assetURL(tag, step.file.asset), step.resumeFrom, destination) { bytes ->
                counter.add(bytes)
                progress(counter.fraction(total))
            }
        }

        // Every bad file goes, not just the first: one left behind would be
        // resumed on the next attempt, its real tail stacked on the bad head.
        val mismatched = mutableListOf<String>()
        for (file in manifest.files) {
            val target = File(into, file.path)
            if (fileSize(target) != file.size || fetcher.sha256(target).lowercase() != file.sha256.lowercase()) {
                target.delete()
                mismatched.add(file.path)
            }
        }
        mismatched.firstOrNull()?.let { throw Failure.ChecksumMismatch(it) }
        progress(1.0)
        return manifest
    }

    private fun assetURL(tag: String, name: String): URI = ReleaseModelURLs.asset(repository, tag, name)

    private fun fileSize(file: File): Long? = if (file.exists()) file.length() else null

    companion object {
        /**
         * Public on its own so the phone can download without signing in,
         * whether or not the app's code repository is public.
         */
        const val DEFAULT_REPOSITORY = "arbelonson-source/ozen-models"
    }
}

/** Bytes landed so far, shared with the fetcher's callback. */
private class ReleaseByteCounter(private var done: Long) {
    private val lock = Any()

    fun add(bytes: Long) {
        synchronized(lock) { done += bytes }
    }

    fun fraction(total: Long): Double {
        if (total <= 0) return 1.0
        val soFar = synchronized(lock) { done }
        return minOf(maxOf(soFar.toDouble() / total.toDouble(), 0.0), 1.0)
    }
}
