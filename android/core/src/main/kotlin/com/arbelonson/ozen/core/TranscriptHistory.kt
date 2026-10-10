package com.arbelonson.ozen.core

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.text.BreakIterator
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One committed or pending caption line as saved to disk. [speakerName] is
 * a snapshot of whatever was actually shown onscreen at save time (a
 * profile's name, or a generic "dover 2" - "speaker 2") rather than a live
 * reference to a speaker profile - a profile can be renamed or deleted
 * later, and history should keep reading the way the conversation actually
 * looked.
 */
class SavedSegment(
    val id: UUID,
    val text: String,
    val speakerName: String?,
    val speakerClusterID: Int?,
    val startTimestamp: Double,
    val isCommitted: Boolean,
    /**
     * Marked as important while it was said ("what the doctor said about
     * the pills"), so it can be found again.
     */
    val isStarred: Boolean = false,
    confidence: Float? = null,
    /**
     * See `TranscriptSegment.scoredBy`. Lines saved before it existed are
     * judged by the conversation's engine and model.
     */
    val scoredBy: CaptionConfidence.Scorer? = null,
) {
    /**
     * The engine's confidence in the line when it was saved. Only a real
     * number is kept: JSON can't hold "not a number" or infinity, and one
     * such line made every save of its conversation fail.
     */
    val confidence: Float? = confidence?.takeIf { it.isFinite() }

    fun copy(
        id: UUID = this.id,
        text: String = this.text,
        speakerName: String? = this.speakerName,
        speakerClusterID: Int? = this.speakerClusterID,
        startTimestamp: Double = this.startTimestamp,
        isCommitted: Boolean = this.isCommitted,
        isStarred: Boolean = this.isStarred,
        confidence: Float? = this.confidence,
        scoredBy: CaptionConfidence.Scorer? = this.scoredBy,
    ): SavedSegment = SavedSegment(id, text, speakerName, speakerClusterID, startTimestamp, isCommitted, isStarred, confidence, scoredBy)

    override fun equals(other: Any?): Boolean =
        other is SavedSegment && id == other.id && text == other.text && speakerName == other.speakerName &&
            speakerClusterID == other.speakerClusterID && startTimestamp == other.startTimestamp &&
            isCommitted == other.isCommitted && isStarred == other.isStarred && confidence == other.confidence &&
            scoredBy == other.scoredBy

    override fun hashCode(): Int =
        listOf(id, text, speakerName, speakerClusterID, startTimestamp, isCommitted, isStarred, confidence, scoredBy).hashCode()

    override fun toString(): String = "SavedSegment(id=$id, text=$text, speakerName=$speakerName, isStarred=$isStarred)"

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", historyUuidText(id))
        put("text", text)
        speakerName?.let { put("speakerName", it) }
        speakerClusterID?.let { put("speakerClusterID", it) }
        put("startTimestamp", historyNumber(startTimestamp))
        put("isCommitted", isCommitted)
        put("isStarred", isStarred)
        confidence?.let { put("confidence", JsonPrimitive(it)) }
        scoredBy?.let { scorer ->
            put(
                "scoredBy",
                buildJsonObject {
                    put("engine", scorer.engine.rawValue)
                    scorer.model?.let { put("model", it) }
                },
            )
        }
    }

    companion object {
        /** Lines saved before stars existed load as not starred. */
        internal fun fromJsonElement(element: JsonElement): SavedSegment {
            val container = element.asHistoryObject()
            return SavedSegment(
                id = container.required("id").asHistoryUuid(),
                text = container.required("text").asHistoryString(),
                speakerName = container.optional("speakerName")?.asHistoryString(),
                speakerClusterID = container.optional("speakerClusterID")?.asHistoryInt(),
                startTimestamp = container.required("startTimestamp").asHistoryDouble(),
                isCommitted = container.required("isCommitted").asHistoryBoolean(),
                isStarred = container.optional("isStarred")?.asHistoryBoolean() ?: false,
                confidence = container.optional("confidence")?.asHistoryFloat(),
                scoredBy = lenientHistory { container.optional("scoredBy")?.let { decodedScorer(it) } },
            )
        }

        private fun decodedScorer(element: JsonElement): CaptionConfidence.Scorer {
            val container = element.asHistoryObject()
            val engine = container.required("engine").asHistoryEngine()
            return CaptionConfidence.Scorer.stored(engine, container.optional("model")?.asHistoryString())
        }

        fun fromJson(json: String): SavedSegment = fromJsonElement(parseHistoryJson(json))
    }

    fun toJson(): String = toJsonObject().toString()
}

/**
 * One captioning session as persisted to disk. `CaptionStabilizer` and
 * `CaptionPipeline` only ever hold the current session in memory, so this
 * is the whole answer to "can I look back at what was said yesterday" -
 * anything meant to survive past the current run has to become one of
 * these first.
 */
data class TranscriptSessionRecord(
    val id: UUID = UUID.randomUUID(),
    val startedAt: Double,
    val endedAt: Double? = null,
    val engine: TranscriptionEngineKind,
    val modelVariant: String?,
    val inputName: String?,
    val segments: List<SavedSegment>,
    /** A name the reader gave the conversation ("bikur etzel harofe" - "visit to the doctor"). */
    val title: String? = null,
) {
    /**
     * The details line under a saved conversation: what wrote it, and
     * through which microphone. The record keeps the technical model id
     * (`AppSettings.modelDescription`, which Diagnostics also reads); here
     * a Whisper id becomes the name the model screen shows, and the home
     * computer's placeholder, which only repeated the engine in English,
     * is left out.
     */
    val sourceLine: String get() = sourceLine(Localization.language)

    fun sourceLine(language: UILanguage): String =
        listOfNotNull(engine.displayName(language), modelName, inputName).joinToString(" · ")

    private val modelName: String?
        get() {
            val variant = modelVariant ?: return null
            return when (engine) {
                TranscriptionEngineKind.WhisperKit -> WhisperModelCatalog.option(variant)?.displayName ?: variant
                TranscriptionEngineKind.Cloud -> when (variant) {
                    CloudSpeech.FAST_MODEL -> "OpenRouter · Gemini Flash Lite"
                    CloudSpeech.ACCURATE_MODEL -> "OpenRouter · Gemini Flash"
                    DeepgramSpeech.MODEL -> "Deepgram · Nova-3"
                    SonioxSpeech.model -> "Soniox"
                    OpenAICompatibleSpeech.openAI.model -> "OpenAI"
                    OpenAICompatibleSpeech.groq.model -> "Groq · Whisper large-v3"
                    ElevenLabsSpeech.MODEL -> "ElevenLabs · Scribe v2"
                    GeminiSpeech.MODEL -> "Google Gemini · Transcribe"
                    SpeechmaticsSpeech.model -> "Speechmatics · Enhanced"
                    AssemblyAISpeech.model -> "AssemblyAI · Universal-3.6 Pro"
                    else -> variant
                }
                TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.AppleSpeech -> null
            }
        }

    fun toJson(): String = buildJsonObject {
        put("id", historyUuidText(id))
        put("startedAt", historyNumber(startedAt))
        endedAt?.let { put("endedAt", historyNumber(it)) }
        put("engine", engine.rawValue)
        modelVariant?.let { put("modelVariant", it) }
        inputName?.let { put("inputName", it) }
        put("segments", JsonArray(segments.map { it.toJsonObject() }))
        title?.let { put("title", it) }
    }.toString()

    companion object {
        /**
         * Converts a live in-memory transcript into a saveable record. A
         * segment whose text is empty (an utterance the engine opened but
         * never filled in, e.g. right as the app was stopped) carries no
         * information and is dropped rather than becoming a blank line in
         * exported text.
         */
        fun make(
            from: List<TranscriptSegment>,
            speakerName: (TranscriptSegment) -> String?,
            id: UUID,
            startedAt: Double,
            endedAt: Double?,
            engine: TranscriptionEngineKind,
            modelVariant: String?,
            inputName: String?,
            starred: Set<UUID> = emptySet(),
        ): TranscriptSessionRecord {
            val saved = from.mapNotNull { segment ->
                if (historyTrimmed(segment.text).isEmpty()) {
                    null
                } else {
                    SavedSegment(
                        id = segment.id,
                        text = segment.text,
                        speakerName = speakerName(segment),
                        speakerClusterID = segment.speakerClusterID,
                        startTimestamp = segment.startTimestamp,
                        isCommitted = segment.isCommitted,
                        isStarred = segment.id in starred,
                        confidence = segment.confidence,
                        scoredBy = segment.scoredBy,
                    )
                }
            }
            return TranscriptSessionRecord(
                id = id,
                startedAt = startedAt,
                endedAt = endedAt,
                engine = engine,
                modelVariant = modelVariant,
                inputName = inputName,
                segments = saved,
            )
        }

        /**
         * Decoding is tolerant of missing keys on the fields a later build
         * could plausibly add or a caller could plausibly omit: an old
         * record on disk must still load rather than losing a whole session
         * to a decode error. Throws when the text isn't a record at all.
         */
        fun fromJson(json: String): TranscriptSessionRecord {
            val container = parseHistoryJson(json).asHistoryObject()
            return TranscriptSessionRecord(
                id = container.required("id").asHistoryUuid(),
                startedAt = container.required("startedAt").asHistoryDouble(),
                endedAt = lenientHistory { container.optional("endedAt")?.asHistoryDouble() },
                // Which engine wrote it only labels the conversation; one this
                // build doesn't know mustn't hide everything that was said.
                engine = lenientHistory { container.optional("engine")?.asHistoryEngine() } ?: TranscriptionEngineKind.WhisperKit,
                modelVariant = lenientHistory { container.optional("modelVariant")?.asHistoryString() },
                inputName = lenientHistory { container.optional("inputName")?.asHistoryString() },
                // A damaged line is left out; the rest of the conversation loads.
                segments = lenientHistory {
                    container.optional("segments")?.asHistoryArray()?.mapNotNull { entry -> lenientHistory { SavedSegment.fromJsonElement(entry) } }
                } ?: emptyList(),
                title = lenientHistory { container.optional("title")?.asHistoryString() },
            )
        }
    }
}

/** One line marked as important, with the conversation it came from. */
data class StarredLine(
    val sessionID: UUID,
    val sessionStartedAt: Double,
    val segment: SavedSegment,
    /** The conversation's name, if it was given one. */
    val sessionTitle: String? = null,
    /**
     * What the conversation was recorded with: the line's score is on
     * that engine's scale.
     */
    val engine: TranscriptionEngineKind = TranscriptionEngineKind.WhisperKit,
    /** And with which of the phone's models, whose cutoffs can be its own. */
    val model: String? = null,
) {
    val id: UUID get() = segment.id

    /** Whether the line carried the question mark on screen. */
    val isUncertain: Boolean get() = CaptionConfidence.isUncertain(segment, engine, model)
}

/**
 * A lightweight stand-in for a [TranscriptSessionRecord] used for listing
 * and searching, so browsing years of history never has to decode every
 * segment of every session just to show a list of dates and previews.
 */
data class TranscriptSessionSummary(
    val id: UUID,
    val startedAt: Double,
    val endedAt: Double?,
    val segmentCount: Int,
    val preview: String,
    val engine: TranscriptionEngineKind,
    /**
     * The real names that took part, in order of first appearance. Generic
     * labels ("dover 2" - "speaker 2") say nothing about who was there and are
     * left out.
     */
    val speakerNames: List<String> = emptyList(),
    /** Lines marked as important. */
    val starredCount: Int = 0,
    val title: String? = null,
    /**
     * When the newest line began. A conversation the app never got to
     * close (the system ended the app in the background) has no end time, and
     * this is the closest thing to one.
     */
    val lastLineAt: Double? = null,
) {
    /**
     * Up to the last line when the conversation was never closed: the
     * app ended or was put away mid-conversation, and nothing goes back
     * to close a record, so its row showed no length forever.
     */
    val durationSeconds: Double?
        get() {
            val end = endedAt ?: lastLineAt ?: return null
            return maxOf(0.0, end - startedAt)
        }

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        put("id", historyUuidText(id))
        put("startedAt", historyNumber(startedAt))
        endedAt?.let { put("endedAt", historyNumber(it)) }
        put("segmentCount", segmentCount)
        put("preview", preview)
        put("engine", engine.rawValue)
        put("speakerNames", JsonArray(speakerNames.map { JsonPrimitive(it) }))
        put("starredCount", starredCount)
        title?.let { put("title", it) }
        lastLineAt?.let { put("lastLineAt", historyNumber(it)) }
    }

    companion object {
        /**
         * The preview is capped well short of a full segment so a list of
         * sessions stays scannable at a glance instead of each row wrapping
         * to several lines.
         */
        internal const val previewCharacterLimit = 80

        internal fun summarizing(record: TranscriptSessionRecord): TranscriptSessionSummary {
            val firstNonEmpty = record.segments.firstOrNull { historyTrimmed(it.text).isNotEmpty() }
            return TranscriptSessionSummary(
                id = record.id,
                startedAt = record.startedAt,
                endedAt = record.endedAt,
                segmentCount = record.segments.size,
                preview = truncatedForPreview(firstNonEmpty?.text ?: ""),
                engine = record.engine,
                speakerNames = realNames(record.segments),
                starredCount = record.segments.count { it.isStarred },
                title = record.title,
                lastLineAt = record.segments.maxOfOrNull { it.startTimestamp },
            )
        }

        internal fun fromJsonElement(element: JsonElement): TranscriptSessionSummary {
            val container = element.asHistoryObject()
            return TranscriptSessionSummary(
                id = container.required("id").asHistoryUuid(),
                startedAt = container.required("startedAt").asHistoryDouble(),
                endedAt = container.optional("endedAt")?.asHistoryDouble(),
                segmentCount = container.required("segmentCount").asHistoryInt(),
                preview = container.required("preview").asHistoryString(),
                engine = container.required("engine").asHistoryEngine(),
                speakerNames = container.required("speakerNames").asHistoryArray().map { it.asHistoryString() },
                starredCount = container.required("starredCount").asHistoryInt(),
                title = container.optional("title")?.asHistoryString(),
                lastLineAt = container.optional("lastLineAt")?.asHistoryDouble(),
            )
        }

        internal fun realNames(segments: List<SavedSegment>): List<String> {
            val seen = HashSet<String>()
            val names = ArrayList<String>()
            for (segment in segments) {
                val name = segment.speakerName?.let { historyTrimmed(it) } ?: continue
                if (name.isEmpty() || isGenericLabel(name) || name in seen) continue
                seen.add(name)
                names.add(name)
            }
            return names
        }

        /**
         * The name chosen above the history list, after it loaded [found]. A
         * name none of every conversation holds any more (its last one was
         * deleted) is dropped, or it would filter everything out with no chip
         * left to clear it. A search that finds none of theirs keeps it: the
         * list then says nothing was found, instead of quietly showing other
         * people's conversations under her search.
         */
        fun speakerFilter(filter: String?, keptAfterLoading: List<TranscriptSessionSummary>, everyConversation: Boolean): String? {
            if (filter == null) return null
            if (!everyConversation) return filter
            return if (keptAfterLoading.any { filter in it.speakerNames }) filter else null
        }

        /**
         * Named speakers across [summaries], most-appeared first, ties broken
         * alphabetically, for a short list of filter chips above a history
         * list a person can actually scan.
         */
        fun topSpeakerNames(summaries: List<TranscriptSessionSummary>, limit: Int = 8): List<String> {
            val counts = HashMap<String, Int>()
            val order = ArrayList<String>()
            for (summary in summaries) {
                for (name in summary.speakerNames.toSet()) {
                    if (counts[name] == null) order.add(name)
                    counts[name] = (counts[name] ?: 0) + 1
                }
            }
            return order
                .sortedWith { a, b -> if (counts[a] != counts[b]) counts.getValue(b).compareTo(counts.getValue(a)) else compareScalars(a, b) }
                .take(limit)
        }

        /**
         * "dover 3" ("speaker 3"), "dover lo yadu'a" ("unknown speaker"), in
         * any of the app's languages.
         */
        fun isGenericLabel(name: String): Boolean {
            if (isUnknownSpeakerLabel(name)) return true
            val characters = historyGraphemes(name)
            return numberedLabels.any { (prefix, suffix) ->
                val prefixLength = historyGraphemes(prefix).size
                val suffixLength = historyGraphemes(suffix).size
                name.startsWith(prefix) && name.endsWith(suffix) && characters.size > prefixLength + suffixLength &&
                    isSwiftInteger(characters.subList(prefixLength, characters.size - suffixLength).joinToString(""))
            }
        }

        /**
         * Checked against every language's text, not the current language's
         * `EmbeddingClusterer.unknownSpeakerName`: a saved line keeps the
         * placeholder of the language it was saved in (the speaker name is a
         * snapshot), so the current language alone stops recognizing it the
         * moment the language is switched.
         */
        fun isUnknownSpeakerLabel(name: String): Boolean = name in unknownLabels

        private val unknownLabels: Set<String> by lazy {
            UILanguage.entries.map { tr("דובר לא ידוע", "Unknown speaker", it) }.toSet()
        }

        private val numberedLabels: List<Pair<String, String>> by lazy {
            UILanguage.entries.mapNotNull { language ->
                val marker = ""
                val label = tr("דובר %1", "Speaker %1", listOf(marker), language)
                val at = label.indexOf(marker)
                if (at < 0) null else label.substring(0, at) to label.substring(at + marker.length)
            }
        }

        private fun isSwiftInteger(text: String): Boolean =
            Regex("[+-]?[0-9]+").matches(text) && text.toIntOrNull() != null

        private fun compareScalars(a: String, b: String): Int {
            val left = a.codePoints().toArray()
            val right = b.codePoints().toArray()
            for (index in 0 until minOf(left.size, right.size)) {
                if (left[index] != right[index]) return left[index].compareTo(right[index])
            }
            return left.size.compareTo(right.size)
        }

        /**
         * Shared by the default line-1 preview and by [TranscriptHistoryStore]'s
         * search-result preview, so both read the same length in a history
         * list row.
         */
        internal fun truncatedForPreview(text: String): String {
            val characters = historyGraphemes(text)
            if (characters.size <= previewCharacterLimit) return text
            return characters.subList(0, previewCharacterLimit).joinToString("") + "…"
        }
    }
}

/**
 * Reads and writes [TranscriptSessionRecord]s as one JSON file per session
 * in a caller-supplied directory. Kept file-based and pointed at an
 * injected directory purely so tests can use a temp directory instead of
 * touching real app storage - there's no database here, just a folder of
 * small JSON files.
 *
 * Next to each conversation sits a tiny summary file in `summaries/`.
 * The history list reads only those, so opening it after a year of daily
 * conversations doesn't decode every line ever captioned. A summary is a
 * cache: if it is missing (a session saved by an older build), older than
 * its conversation, or unreadable, the list rebuilds it from the full
 * record and writes it back.
 */
class TranscriptHistoryStore(private val directory: File) {
    private val summariesDirectory: File get() = File(directory, SUMMARIES_FOLDER_NAME)

    private fun fileFor(id: UUID): File = File(directory, "${historyUuidText(id)}.json")

    private fun summaryFile(recordFile: File): File = File(summariesDirectory, recordFile.name)

    /**
     * The searchable words of one conversation, already lowercased and
     * stripped of niqqud, one caption line or speaker name per line. The
     * format is in the file name, so a future change simply stops
     * finding the old files and rebuilds them. (v1 also held "speaker 2"
     * and "unknown speaker" labels.)
     */
    private fun searchTextFile(recordFile: File): File =
        File(summariesDirectory, recordFile.nameWithoutExtension + ".search-v3.txt")

    /**
     * Search files of earlier formats hold the conversation's words too,
     * so deleting it deletes them.
     */
    private fun olderSearchTextFiles(recordFile: File): List<File> =
        listOf("v1", "v2").map { File(summariesDirectory, recordFile.nameWithoutExtension + ".search-$it.txt") }

    /**
     * Saves a session, overwriting any earlier save with the same id -
     * that's what lets a caller autosave periodically during a live
     * session and again when it ends, without creating duplicates. A
     * session with no segments is noise rather than history (the user
     * opened the app and closed it again) so it's deliberately not
     * written at all.
     *
     * A conversation still being captioned is autosaved from the live
     * transcript, which knows nothing of a name given to it meanwhile on
     * the history screen. A save without a title therefore keeps the one
     * already on disk (read from the small summary file, not the whole
     * conversation); [rename] is how a title is changed or removed.
     *
     * [updateSearchCaches] skips the summary and search-text files: real
     * work over every segment, worth skipping when a caller is waiting
     * synchronously for this save to land. Reading the history back without
     * them is unaffected - a missing or stale cache is already rebuilt from
     * the record the next time anything asks for it (see [listSummaries]).
     */
    fun save(record: TranscriptSessionRecord, updateSearchCaches: Boolean = true): Boolean {
        if (record.segments.isEmpty()) return false
        Files.createDirectories(directory.toPath())
        val file = fileFor(record.id)
        var toSave = record
        if (toSave.title == null) {
            // A save that skipped the caches leaves the summary older than
            // the conversation, so the next save can't trust it: the name
            // is then read from the conversation file itself.
            val summary = cachedSummary(file)
            if (summary != null) {
                toSave = toSave.copy(title = summary.title)
            } else {
                readBytes(file)?.let { data ->
                    toSave = toSave.copy(title = lenientHistory { savedTitle(data) })
                }
            }
        }
        // What wrote a conversation is what its first save said, within
        // seconds of its first lines. Later saves (the stop after an engine
        // switch, a rename the next day) stamped it with whatever engine
        // and microphone were in use by then, and History showed those,
        // and judged its uncertain lines by them.
        readBytes(file)?.let { data ->
            val source = lenientHistory { savedSource(data) }
            val engine = source?.engine
            if (source != null && engine != null) {
                toSave = toSave.copy(engine = engine, modelVariant = source.modelVariant, inputName = source.inputName)
            }
        }
        PrivateFileWrites.write(file, toSave.toJson().toByteArray(Charsets.UTF_8))
        if (!updateSearchCaches) return true
        // Written after the record, so a fresh summary is never older than
        // its conversation. If this write fails the conversation is still
        // saved; the list just rebuilds the summary next time.
        val written = modificationDate(file)
        writeSummary(TranscriptSessionSummary.summarizing(toSave), file, written)
        writeSearchText(searchableText(toSave), file, written)
        return true
    }

    private class SavedSource(val engine: TranscriptionEngineKind?, val modelVariant: String?, val inputName: String?)

    private fun savedTitle(data: ByteArray): String? =
        parseHistoryBytes(data).asHistoryObject().optional("title")?.asHistoryString()

    private fun savedSource(data: ByteArray): SavedSource {
        val container = parseHistoryBytes(data).asHistoryObject()
        return SavedSource(
            engine = container.optional("engine")?.asHistoryEngine(),
            modelVariant = container.optional("modelVariant")?.asHistoryString(),
            inputName = container.optional("inputName")?.asHistoryString(),
        )
    }

    fun load(id: UUID): TranscriptSessionRecord? = decodeRecord(fileFor(id))

    /** The conversation files in the directory, without reading them. */
    private fun recordFiles(): List<File> =
        directory.listFiles()?.filter { it.extension == "json" } ?: emptyList()

    private fun readBytes(file: File): ByteArray? = try {
        file.readBytes()
    } catch (_: IOException) {
        null
    }

    /**
     * A file that fails to decode (truncated write, a future format the
     * current build doesn't understand) comes back null and is skipped by
     * the list and search - one bad session must never hide every other.
     */
    private fun decodeRecord(file: File): TranscriptSessionRecord? {
        val data = readBytes(file) ?: return null
        return lenientHistory { TranscriptSessionRecord.fromJson(strictUtf8(data)) }
    }

    /** A cache file is trusted only if it was written after its conversation. */
    private fun isFresh(cache: File, recordFile: File): Boolean {
        val cacheDate = modificationDate(cache) ?: return false
        val recordDate = modificationDate(recordFile) ?: return false
        return cacheDate >= recordDate
    }

    private fun cachedSummary(recordFile: File): TranscriptSessionSummary? {
        val cache = summaryFile(recordFile)
        if (!isFresh(cache, recordFile)) return null
        val data = readBytes(cache) ?: return null
        return lenientHistory {
            val container = parseHistoryBytes(data).asHistoryObject()
            val format = container.required("format").asHistoryInt()
            val summary = TranscriptSessionSummary.fromJsonElement(container.required("summary"))
            if (format == SUMMARY_FORMAT) summary else null
        }
    }

    internal fun writeSummary(summary: TranscriptSessionSummary, recordFile: File, recordModifiedAt: FileTime?) {
        val data = buildJsonObject {
            put("format", SUMMARY_FORMAT)
            put("summary", summary.toJsonObject())
        }.toString().toByteArray(Charsets.UTF_8)
        try {
            summariesDirectory.mkdirs()
            PrivateFileWrites.write(summaryFile(recordFile), data)
        } catch (_: IOException) {
        }
        removeCacheIfConversationChanged(summaryFile(recordFile), recordFile, recordModifiedAt)
    }

    /**
     * A listing off the main thread can read a conversation just before
     * it is deleted, or saved again, and write its cache just after. A
     * cache for a deleted conversation still holds its words; one for an
     * older version is newer than the file and would pass as fresh. Either
     * way the cache goes, and the next listing rebuilds it.
     */
    private fun removeCacheIfConversationChanged(cache: File, recordFile: File, recordModifiedAt: FileTime?) {
        val now = modificationDate(recordFile)
        if (now == null || now != recordModifiedAt) cache.delete()
    }

    private fun cachedSearchText(recordFile: File): String? {
        val cache = searchTextFile(recordFile)
        if (!isFresh(cache, recordFile)) return null
        val data = readBytes(cache) ?: return null
        return lenientHistory { strictUtf8(data) }
    }

    private fun writeSearchText(text: String, recordFile: File, recordModifiedAt: FileTime?) {
        try {
            summariesDirectory.mkdirs()
            PrivateFileWrites.write(searchTextFile(recordFile), text.toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
        }
        removeCacheIfConversationChanged(searchTextFile(recordFile), recordFile, recordModifiedAt)
    }

    fun listSummaries(): List<TranscriptSessionSummary> = summaries(recordFiles())

    /**
     * Summaries of the conversations whose file was written at or after
     * [cutoff]. Checking a file's date is far cheaper than opening it, so
     * "what was being saved in the last half hour" doesn't read a year of
     * history at launch.
     */
    fun summariesModifiedSince(cutoff: Double): List<TranscriptSessionSummary> {
        val recent = recordFiles().filter { file ->
            val modified = modificationDate(file) ?: return@filter true
            modified.toMillis() / 1_000.0 >= cutoff
        }
        return summaries(recent)
    }

    private fun summaries(files: List<File>): List<TranscriptSessionSummary> =
        files
            .mapNotNull { file ->
                cachedSummary(file) ?: run {
                    val read = modificationDate(file)
                    val record = decodeRecord(file) ?: return@run null
                    val summary = TranscriptSessionSummary.summarizing(record)
                    writeSummary(summary, file, read)
                    summary
                }
            }
            .sortedByDescending { it.startedAt }

    /**
     * Case-insensitive substring search over segment text and speaker
     * names, with Hebrew niqqud stripped from both the query and the
     * stored text first. Niqqud is how vowels are written in Hebrew, but
     * almost nobody types it when searching, and speech engines rarely
     * emit it either - without stripping it, a search for a plain-typed
     * word would fail to find a session where the transcript happened to
     * include the pointed form.
     *
     * A matching summary's `preview` is replaced with the line that
     * actually matched (see [matchingSnippet]), so a search result shows
     * why it matched instead of always repeating the conversation's
     * first line - this only changes the copy returned here, never the
     * cached summary written to disk.
     */
    fun search(query: String): List<TranscriptSessionSummary> {
        val words = searchWords(query)
        if (words.isEmpty()) return listSummaries()

        return recordFiles()
            .mapNotNull { file ->
                // Fast path: the conversation's prepared search text says
                // no, or says yes and its summary is ready.
                val prepared = cachedSearchText(file)
                if (prepared != null) {
                    if (!words.all { it.found(prepared) }) return@mapNotNull null
                    val cached = cachedSummary(file)
                    if (cached != null) {
                        val snippet = matchingSnippet(query, prepared)
                        return@mapNotNull if (snippet != null) cached.copy(preview = snippet) else cached
                    }
                }
                // Slow path, once per conversation: read it whole and write
                // the files that make the next search fast.
                val read = modificationDate(file)
                val record = decodeRecord(file) ?: return@mapNotNull null
                val summary = TranscriptSessionSummary.summarizing(record)
                val text = searchableText(record)
                writeSummary(summary, file, read)
                writeSearchText(text, file, read)
                if (!words.all { it.found(text) }) return@mapNotNull null
                val snippet = matchingSnippet(query, text)
                if (snippet != null) summary.copy(preview = snippet) else summary
            }
            .sortedByDescending { it.startedAt }
    }

    /**
     * Every starred line in saved history, newest conversation first and
     * in spoken order within one. Only conversations whose summary counts
     * a star are opened.
     */
    fun starredLines(): List<StarredLine> =
        listSummaries()
            .filter { it.starredCount > 0 }
            .mapNotNull { load(it.id) }
            .flatMap { record ->
                record.segments
                    .filter { it.isStarred }
                    .map { StarredLine(record.id, record.startedAt, it, record.title, record.engine, record.modelVariant) }
            }

    /**
     * A saved voice was renamed: every saved conversation that said the
     * old name now says the new one, so fixing a misspelling reaches the
     * days before it too, and a search for the new name finds them. A
     * conversation whose search text doesn't hold the old name is skipped
     * without being opened. Returns how many conversations changed.
     */
    fun renameSpeaker(from: String, to: String): Int {
        if (from.isEmpty() || to.isEmpty() || from == to) return 0
        val needle = normalizedForSearch(from)
        var changed = 0
        for (file in recordFiles()) {
            val cached = cachedSearchText(file)
            if (cached != null && !cached.contains(needle)) continue
            val record = decodeRecord(file) ?: continue
            if (record.segments.none { it.speakerName == from }) continue
            val renamed = record.copy(segments = record.segments.map { if (it.speakerName == from) it.copy(speakerName = to) else it })
            PrivateFileWrites.write(file, renamed.toJson().toByteArray(Charsets.UTF_8))
            val written = modificationDate(file)
            writeSummary(TranscriptSessionSummary.summarizing(renamed), file, written)
            writeSearchText(searchableText(renamed), file, written)
            changed += 1
        }
        return changed
    }

    /** Names a saved conversation, or removes its name with an empty one. */
    fun rename(id: UUID, title: String) {
        val record = load(id) ?: return
        val trimmed = historyTrimmed(title)
        val renamed = record.copy(title = trimmed.ifEmpty { null })
        val file = fileFor(id)
        PrivateFileWrites.write(file, renamed.toJson().toByteArray(Charsets.UTF_8))
        val written = modificationDate(file)
        writeSummary(TranscriptSessionSummary.summarizing(renamed), file, written)
        writeSearchText(searchableText(renamed), file, written)
    }

    /**
     * Stars a saved line, or takes its star away, after the conversation
     * ended: a line starred later protects its conversation from being
     * cleared out just as one starred while it was said does. Returns the
     * line's new state, or null when the conversation or line is gone.
     */
    fun toggleStar(segmentID: UUID, inSession: UUID): Boolean? {
        val record = load(inSession) ?: return null
        val index = record.segments.indexOfFirst { it.id == segmentID }
        if (index < 0) return null
        val toggled = record.segments[index].copy(isStarred = !record.segments[index].isStarred)
        val updated = record.copy(segments = record.segments.toMutableList().also { it[index] = toggled })
        val file = fileFor(inSession)
        PrivateFileWrites.write(file, updated.toJson().toByteArray(Charsets.UTF_8))
        writeSummary(TranscriptSessionSummary.summarizing(updated), file, modificationDate(file))
        return toggled.isStarred
    }

    fun delete(id: UUID) {
        val file = fileFor(id)
        summaryFile(file).delete()
        searchTextFile(file).delete()
        for (older in olderSearchTextFiles(file)) older.delete()
        if (!file.exists()) return
        Files.delete(file.toPath())
    }

    fun deleteAll() {
        for (file in recordFiles()) Files.delete(file.toPath())
        // The caches are rebuilt from the conversations. A folder that can't
        // go at once (a listing writing into it at that moment) must not make
        // deleting fail after the conversations themselves are gone; what
        // can be removed of it is.
        if (summariesDirectory.exists() && !summariesDirectory.deleteRecursively()) {
            for (entry in summariesDirectory.listFiles() ?: emptyArray()) entry.deleteRecursively()
        }
    }

    /** Bytes used by conversations and their summaries. */
    fun totalSizeOnDisk(): Long {
        if (!directory.exists()) return 0
        var total = 0L
        Files.walk(directory.toPath()).use { paths ->
            for (path in paths) {
                if (Files.isRegularFile(path)) total += Files.size(path)
            }
        }
        return total
    }

    /**
     * One word of a search. Saved text is searched for the word as typed,
     * which also finds it with a prefix attached ("rofe" finds "la-rofe",
     * to the doctor). The reverse needs help: a query itself carrying one
     * of Hebrew's inseparable prefixes - vav ("and"), he ("the"), bet
     * ("in/with"), lamed ("to"), mem ("from"), shin ("that"), kaf ("as"),
     * and the everyday two-letter stacks of them (vav-he, vav-lamed,
     * vav-bet, vav-mem, shin-he, bet-he, lamed-he, mem-he) - is also looked
     * for with that prefix stripped, so "la-rofe" or "ve-ha-rofe" (typed
     * with the prefix, as people naturally do) also finds a bare "rofe".
     * Only stripped when three letters or more remain underneath, so a
     * short name is not cut down to something found everywhere.
     *
     * Each of those is also looked for with the other number's ending,
     * which changes the word's last letter so "contains" can't reach it:
     * "trufa" (medicine) finds "trufot" and "trufat" (medicine of), "makom"
     * (place) finds "mekomot", and the other way round, "yeladim"
     * (children) finds "yeled" and "zmanim" (times) finds "zman". Run
     * over 4,635 broadcast and lecture lines, a singular from an everyday
     * list found 102 more of them, about nine in ten the same word ("zman"
     * also finds "muzmanim", invited); a plural finds the singular and
     * words on the same letters ("she'elot", questions, also finds
     * "sha'alti", I asked). A word of two letters is left as it is.
     */
    internal class SearchWord(word: String) {
        val forms: List<String>

        init {
            val collected = arrayListOf(word)
            for (prefix in attachedPrefixes) {
                if (!word.startsWith(prefix)) continue
                val stem = word.substring(prefix.length)
                if (historyGraphemes(stem).size < 3 || stem in collected) continue
                collected.add(stem)
            }
            for (form in ArrayList(collected)) {
                for (other in numberForms(form)) {
                    if (other !in collected) collected.add(other)
                }
            }
            forms = collected
        }

        fun found(text: String): Boolean = forms.any { text.contains(it) }

        companion object {
            /**
             * Longest first, so a two-letter stack is tried whole before its
             * first letter alone is tried on top of it.
             */
            val attachedPrefixes: List<String> = listOf(
                "וה", "ול", "וב", "ומ", "שה", "בה", "לה", "מה",
                "ו", "ה", "ב", "ל", "מ", "ש", "כ",
            )

            val finalForms: Map<String, String> = HebrewText.finalLetters.entries.associate { (final, ordinary) -> ordinary to final }

            fun numberForms(word: String): List<String> {
                val characters = historyGraphemes(word)
                if (characters.size < 3) return emptyList()
                val last = characters.last()
                val forms = ArrayList<String>()
                if (last == "ה" && characters.size >= 4) {
                    val stem = characters.dropLast(1).joinToString("")
                    forms += listOf(stem + "ות", stem + "ת")
                }
                val ordinary = HebrewText.finalLetters[last]
                if (ordinary != null && !word.endsWith("ים")) {
                    val stem = characters.dropLast(1).joinToString("") + ordinary
                    forms += listOf(stem + "ים", stem + "ות")
                }
                for (ending in listOf("ות", "ים")) {
                    if (word.endsWith(ending) && characters.size >= 5) {
                        val stemCharacters = characters.dropLast(2)
                        val stem = stemCharacters.joinToString("")
                        if (ending == "ות") forms.add(stem + "ה")
                        val stemLast = stemCharacters.lastOrNull()
                        val final = stemLast?.let { finalForms[it] }
                        if (final != null) {
                            forms.add(stemCharacters.dropLast(1).joinToString("") + final)
                        } else {
                            forms.add(stem)
                        }
                    }
                }
                return forms
            }
        }
    }

    companion object {
        /**
         * Bumped whenever [TranscriptSessionSummary] changes meaning, so
         * summaries written by an older build are rebuilt instead of trusted.
         */
        internal const val SUMMARY_FORMAT = 4
        internal const val SUMMARIES_FOLDER_NAME = "summaries"

        internal fun modificationDate(file: File): FileTime? = try {
            Files.getLastModifiedTime(file.toPath())
        } catch (_: IOException) {
            null
        }

        internal fun searchableText(record: TranscriptSessionRecord): String {
            val lines = ArrayList<String>()
            record.title?.let { lines.add(normalizedForSearch(it)) }
            for (segment in record.segments) {
                lines.add(normalizedForSearch(segment.text))
                // Not "speaker 2" or "unknown speaker": searching for "2" found
                // every conversation with a numbered voice.
                val name = segment.speakerName
                if (name != null && !TranscriptSessionSummary.isGenericLabel(name)) {
                    lines.add(normalizedForSearch(name))
                }
            }
            return lines.joinToString("\n")
        }

        /**
         * Turns saved or typed text into the form both sides of a search are
         * compared in. A hyphen or Hebrew Maqaf joining two halves of a word
         * is turned into a space before niqqud is stripped, so "tel-aviv" and
         * a Maqaf'd "tel-aviv" both split into two words the same way a plain
         * "tel aviv" does, instead of fusing into one word neither half of a
         * two-word search can find. Punctuation is turned into a space too,
         * rather than deleted outright, so dictation punctuation or Hebrew
         * gershayim quotes glued onto a word by voice dictation or typing
         * don't stop it matching the bare word.
         *
         * A mark between two digits is dropped instead, so an amount, a phone
         * number or a time saved as "2,500", "050-1234567" or "10:30" is
         * found by "2500", "0501234567" or "1030" too, not split in two.
         */
        private fun normalizedForSearch(text: String): String {
            val separated = HebrewText.separatingJoiners(joiningDigitGroups(text.replace("\n", " ")))
            val withoutNiqqud = HebrewText.stripNiqqud(separated)
            val result = StringBuilder(withoutNiqqud.length)
            withoutNiqqud.codePoints().forEach { scalar ->
                if (isPunctuationOrSymbol(scalar)) result.append(' ') else result.appendCodePoint(scalar)
            }
            return result.toString().lowercase(Locale.ROOT)
        }

        private fun isPunctuationOrSymbol(scalar: Int): Boolean = when (Character.getType(scalar).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
            -> true
            else -> false
        }

        private fun isPunctuation(scalar: Int): Boolean = when (Character.getType(scalar).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION,
            -> true
            else -> false
        }

        private fun isDecimalDigit(scalar: Int): Boolean = Character.getType(scalar).toByte() == Character.DECIMAL_DIGIT_NUMBER

        private fun joiningDigitGroups(text: String): String {
            val scalars = text.codePoints().toArray()
            val kept = StringBuilder()
            for ((index, scalar) in scalars.withIndex()) {
                val betweenDigits = index > 0 && index + 1 < scalars.size &&
                    isDecimalDigit(scalars[index - 1]) && isDecimalDigit(scalars[index + 1])
                if (betweenDigits && isPunctuation(scalar)) continue
                kept.appendCodePoint(scalar)
            }
            return kept.toString()
        }

        private fun isSearchWhitespace(codePoint: Int): Boolean =
            codePoint in 0x09..0x0D || codePoint == 0x20 || codePoint == 0x85 || codePoint == 0xA0 || codePoint == 0x1680 ||
                codePoint in 0x2000..0x200A || codePoint == 0x2028 || codePoint == 0x2029 || codePoint == 0x202F ||
                codePoint == 0x205F || codePoint == 0x3000

        /**
         * The words of a search, compared the way saved text is: without
         * niqqud, in lower case. A conversation is found when it holds every
         * one of them, in any order and on any line, so "rofe kadurim"
         * ("doctor pills") finds the visit where the doctor spoke about pills
         * three lines before naming them.
         */
        internal fun searchWords(query: String): List<SearchWord> {
            val words = ArrayList<String>()
            val current = StringBuilder()
            normalizedForSearch(query).codePoints().forEach { codePoint ->
                if (isSearchWhitespace(codePoint)) {
                    if (current.isNotEmpty()) words.add(current.toString())
                    current.setLength(0)
                } else {
                    current.appendCodePoint(codePoint)
                }
            }
            if (current.isNotEmpty()) words.add(current.toString())
            return words.map { SearchWord(it) }
        }

        /**
         * The lines of a conversation that a search for [query] found, in
         * order, by the words of the line and the name of who said it: those
         * holding every word, or when no line does, those holding any.
         * Opening a search result jumps to these.
         */
        fun matchingSegmentIDs(record: TranscriptSessionRecord, query: String): List<UUID> {
            val words = searchWords(query)
            if (words.isEmpty()) return emptyList()
            val lines = record.segments.map { segment ->
                segment.id to (normalizedForSearch(segment.text) + "\n" + (segment.speakerName?.let { normalizedForSearch(it) } ?: ""))
            }
            val holdingAll = lines.filter { line -> words.all { it.found(line.second) } }
            if (holdingAll.isNotEmpty()) return holdingAll.map { it.first }
            return lines.filter { line -> words.any { it.found(line.second) } }.map { it.first }
        }

        /**
         * The first prepared search line (see [searchableText]) that holds
         * every word of [query] - or, when none does, the first that holds
         * any, matching the same fallback [search] itself uses - truncated
         * like a normal preview. [text] is already normalized, so this reads
         * slightly differently than the original line (case folded, niqqud
         * gone); that trade-off is what keeps a search fast, never reading a
         * conversation back off disk just to build its preview.
         */
        internal fun matchingSnippet(query: String, text: String): String? {
            val words = searchWords(query)
            if (words.isEmpty()) return null
            val lines = text.split("\n").filter { it.isNotEmpty() }
            lines.firstOrNull { line -> words.all { it.found(line) } }?.let { return TranscriptSessionSummary.truncatedForPreview(it) }
            lines.firstOrNull { line -> words.any { it.found(line) } }?.let { return TranscriptSessionSummary.truncatedForPreview(it) }
            return null
        }

        /**
         * A plain-text rendering for sharing or reviewing a session outside
         * the app (e.g. sent as a .txt file). The clock time is computed
         * by hand from the raw seconds plus a caller-supplied UTC offset
         * rather than through a date formatter, which is locale-sensitive and
         * would otherwise make this render differently on a test machine than
         * on the phone. The app passes the phone's offset at each timestamp;
         * tests pass 0.
         *
         * Opens with the conversation's name, if it has one, and its date:
         * pasted into a chat or a note, the lines alone never say which day
         * the doctor said it.
         */
        fun exportText(record: TranscriptSessionRecord, utcOffsetSeconds: Int = 0): String =
            exportText(record, utcOffsetAt = { utcOffsetSeconds })

        /**
         * As above, with the offset looked up per timestamp: a summer
         * conversation shared in winter keeps the clock times it was said at.
         * With [marksUncertain], a line the engine was unsure of says so.
         */
        fun exportText(record: TranscriptSessionRecord, utcOffsetAt: (Double) -> Int, marksUncertain: Boolean = false): String {
            val formatted = record.segments.map { segment ->
                val time = formattedClockTime(segment.startTimestamp, utcOffsetAt(segment.startTimestamp))
                val star = if (segment.isStarred) "★ " else ""
                // As on the caption screen: pasted into a chat, "050 123
                // 4567" in a Hebrew line would read "4567 123 050".
                val said = CaptionLayout.isolatingNumbers(segment.text)
                // The question mark the screen showed, in words: whoever
                // reads "two pills at ten thirty" in a chat should know too.
                val unsure = marksUncertain && CaptionConfidence.isUncertain(segment, record.engine, record.modelVariant)
                val warning = if (unsure) tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ") else ""
                // "Unknown speaker:" on every unrecognised line says nothing;
                // numbered voices ("Speaker 2") still tell turns apart.
                val name = segment.speakerName
                val line = if (name != null && name.isNotEmpty() && !TranscriptSessionSummary.isUnknownSpeakerLabel(name)) {
                    "$star[$time] $warning$name: $said"
                } else {
                    "$star[$time] $warning$said"
                }
                // Pasted into a chat, a line opening with an English word
                // would be laid out left to right and read out of order.
                if (CaptionLayout.opensLeftToRight(line)) CaptionLayout.RIGHT_TO_LEFT_MARK + line else line
            }
            val date = formattedDate(record.startedAt, utcOffsetAt(record.startedAt))
            val heading = namedHeading(record.title, date) ?: tr("שיחה מתאריך %1", "Conversation from %1", listOf(date))
            if (formatted.isEmpty()) return heading
            val transcript = formatted.joinToString("\n")
            val numbered = if (record.segments.size < NUMBERS_BLOCK_MINIMUM_LINES) {
                emptyList()
            } else {
                record.segments.zip(formatted)
                    .filter { NumberEmphasis.hasListableNumber(it.first.text) }
                    .take(NUMBERS_BLOCK_LIMIT)
                    .map { it.second }
            }
            if (numbered.isEmpty()) return "$heading\n\n$transcript"
            return tr(
                "%1\n\nמספרים שנאמרו:\n%2\n\nהשיחה:\n%3",
                "%1\n\nNumbers mentioned:\n%2\n\nThe conversation:\n%3",
                listOf(heading, numbered.joinToString("\n"), transcript),
            )
        }

        /**
         * A long conversation shared as text opens with the lines that had a
         * time, an amount or a phone number in them (see `NumberEmphasis`), so
         * whoever reads it in a chat finds what the doctor said without
         * scrolling through an hour of talk. A short one is read whole anyway.
         */
        internal const val NUMBERS_BLOCK_MINIMUM_LINES = 20
        internal const val NUMBERS_BLOCK_LIMIT = 12

        /**
         * Starred lines as plain text for sharing: one block per
         * conversation, headed by its date, then each line with its time and
         * who said it. Dates are computed by hand for the same reason as the
         * clock times: identical output on the phone and in tests. With
         * [marksUncertain], a line the engine was unsure of says so.
         */
        fun exportStarredText(lines: List<StarredLine>, utcOffsetSeconds: Int = 0): String =
            exportStarredText(lines, utcOffsetAt = { utcOffsetSeconds })

        fun exportStarredText(lines: List<StarredLine>, utcOffsetAt: (Double) -> Int, marksUncertain: Boolean = false): String {
            val blocks = ArrayList<String>()
            var currentSession: UUID? = null
            var block = ArrayList<String>()
            for (line in lines) {
                if (line.sessionID != currentSession) {
                    if (block.isNotEmpty()) blocks.add(block.joinToString("\n"))
                    // Two conversations on one day read apart by their names.
                    val date = formattedDate(line.sessionStartedAt, utcOffsetAt(line.sessionStartedAt))
                    block = arrayListOf(namedHeading(line.sessionTitle, date) ?: date)
                    currentSession = line.sessionID
                }
                val time = formattedClockTime(line.segment.startTimestamp, utcOffsetAt(line.segment.startTimestamp))
                val said = CaptionLayout.isolatingNumbers(line.segment.text)
                val warning = if (marksUncertain && line.isUncertain) tr("ייתכן שלא נשמע נכון. ", "May not have been heard correctly. ") else ""
                val name = line.segment.speakerName
                val text = if (name != null && name.isNotEmpty() && !TranscriptSessionSummary.isGenericLabel(name)) {
                    "[$time] $warning$name: $said"
                } else {
                    "[$time] $warning$said"
                }
                // As in `exportText`: read in order when pasted into a chat.
                block.add(if (CaptionLayout.opensLeftToRight(text)) CaptionLayout.RIGHT_TO_LEFT_MARK + text else text)
            }
            if (block.isNotEmpty()) blocks.add(block.joinToString("\n"))
            return blocks.joinToString("\n\n")
        }

        /**
         * "Name, date" for a named conversation, null otherwise. Like the lines
         * under it: "WhatsApp mehabank" pasted into a chat would be laid out
         * left to right, so a Hebrew name opening with a Latin word gets a
         * right-to-left mark; an all-English name stays as it is.
         */
        private fun namedHeading(title: String?, date: String): String? {
            if (title == null || title.isEmpty()) return null
            val hebrew = title.codePoints().anyMatch { it in 0x05D0..0x05EA }
            return (if (hebrew && CaptionLayout.opensLeftToRight(title)) CaptionLayout.RIGHT_TO_LEFT_MARK else "") + "$title, $date"
        }

        /** Day.month.year of a timestamp in the given UTC offset. */
        private fun formattedDate(timestamp: Double, utcOffsetSeconds: Int): String {
            val date = CivilDate.fromDaysSinceEpoch(CivilDate.localDay(timestamp, utcOffsetSeconds))
            return "${twoDigits(date.day)}.${twoDigits(date.month)}.${date.year}"
        }

        /** "14:02:07": the clock time of [timestamp] at the given offset from UTC. */
        fun formattedClockTime(timestamp: Double, utcOffsetSeconds: Int): String {
            val totalSeconds = Math.floor(timestamp).toLong() + utcOffsetSeconds
            // Wrap into a single day of seconds so a session that (in theory)
            // started with a huge or negative timestamp still prints a valid
            // 24-hour clock reading instead of garbage.
            val secondsOfDay = (((totalSeconds % 86_400) + 86_400) % 86_400).toInt()
            val hours = secondsOfDay / 3_600
            val minutes = (secondsOfDay % 3_600) / 60
            val seconds = secondsOfDay % 60
            return "${twoDigits(hours)}:${twoDigits(minutes)}:${twoDigits(seconds)}"
        }

        private fun twoDigits(value: Int): String = if (value < 10) "0$value" else "$value"
    }
}

private class HistoryDecodeFailure : Exception()

private inline fun <T> lenientHistory(block: () -> T?): T? = try {
    block()
} catch (_: HistoryDecodeFailure) {
    null
} catch (_: kotlinx.serialization.SerializationException) {
    null
} catch (_: IllegalArgumentException) {
    null
}

private fun strictUtf8(data: ByteArray): String {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    try {
        return decoder.decode(ByteBuffer.wrap(data)).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw HistoryDecodeFailure()
    }
}

private fun parseHistoryBytes(data: ByteArray): JsonElement = parseHistoryJson(strictUtf8(data))

private fun parseHistoryJson(json: String): JsonElement = Json.parseToJsonElement(json)

private fun historyUuidText(id: UUID): String = id.toString().uppercase(Locale.ROOT)

/** Whole numbers are written without a fraction, as Foundation's encoder does. */
private fun historyNumber(value: Double): JsonPrimitive =
    if (value == Math.rint(value) && Math.abs(value) < 1e15) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

private fun JsonElement.asHistoryObject(): JsonObject = this as? JsonObject ?: throw HistoryDecodeFailure()

private fun JsonElement.asHistoryArray(): JsonArray = this as? JsonArray ?: throw HistoryDecodeFailure()

private fun JsonObject.required(key: String): JsonElement = optional(key) ?: throw HistoryDecodeFailure()

private fun JsonObject.optional(key: String): JsonElement? = this[key]?.takeIf { it !is JsonNull }

private fun JsonElement.asHistoryString(): String {
    val primitive = this as? JsonPrimitive ?: throw HistoryDecodeFailure()
    if (!primitive.isString) throw HistoryDecodeFailure()
    return primitive.content
}

private fun JsonElement.asHistoryBoolean(): Boolean {
    val primitive = this as? JsonPrimitive ?: throw HistoryDecodeFailure()
    if (primitive.isString) throw HistoryDecodeFailure()
    return when (primitive.content) {
        "true" -> true
        "false" -> false
        else -> throw HistoryDecodeFailure()
    }
}

private val historyNumberPattern = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

private fun JsonElement.numberText(): String {
    val primitive = this as? JsonPrimitive ?: throw HistoryDecodeFailure()
    if (primitive.isString || !historyNumberPattern.matches(primitive.content)) throw HistoryDecodeFailure()
    return primitive.content
}

private fun JsonElement.asHistoryDouble(): Double {
    val value = numberText().toDouble()
    if (!value.isFinite()) throw HistoryDecodeFailure()
    return value
}

private fun JsonElement.asHistoryFloat(): Float {
    val value = numberText().toDouble().toFloat()
    if (!value.isFinite()) throw HistoryDecodeFailure()
    return value
}

private fun JsonElement.asHistoryInt(): Int {
    val text = numberText()
    text.toIntOrNull()?.let { return it }
    val value = text.toDouble()
    if (value != Math.rint(value) || value < Int.MIN_VALUE || value > Int.MAX_VALUE) throw HistoryDecodeFailure()
    return value.toInt()
}

private val historyUuidPattern = Regex("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}")

private fun JsonElement.asHistoryUuid(): UUID {
    val text = asHistoryString()
    if (!historyUuidPattern.matches(text)) throw HistoryDecodeFailure()
    return UUID.fromString(text)
}

private fun JsonElement.asHistoryEngine(): TranscriptionEngineKind =
    TranscriptionEngineKind.fromRawValue(asHistoryString()) ?: throw HistoryDecodeFailure()

private fun historyGraphemes(text: String): List<String> {
    val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
    iterator.setText(text)
    val result = ArrayList<String>()
    var start = iterator.first()
    var end = iterator.next()
    while (end != BreakIterator.DONE) {
        result.add(text.substring(start, end))
        start = end
        end = iterator.next()
    }
    return result
}

private fun historyIsSpaceOrNewline(codePoint: Int): Boolean =
    when (Character.getType(codePoint).toByte()) {
        Character.SPACE_SEPARATOR, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true
        else -> codePoint in 0x09..0x0D || codePoint == 0x85
    }

internal fun historyTrimmed(text: String): String {
    val scalars = text.codePoints().toArray()
    var from = 0
    var to = scalars.size
    while (from < to && historyIsSpaceOrNewline(scalars[from])) from++
    while (to > from && historyIsSpaceOrNewline(scalars[to - 1])) to--
    return String(scalars, from, to - from)
}
