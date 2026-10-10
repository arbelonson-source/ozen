package com.arbelonson.ozen.core

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.TimeZone
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun <T> withHistoryDirectory(prefix: String, body: (File) -> T): T {
    val directory = File(System.getProperty("java.io.tmpdir"), "$prefix-${UUID.randomUUID()}")
    try {
        return body(directory)
    } finally {
        val summaries = File(directory, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME)
        if (summaries.exists()) Files.setPosixFilePermissions(summaries.toPath(), PosixFilePermissions.fromString("rwx------"))
        directory.deleteRecursively()
    }
}

private fun uuidString(id: UUID): String = id.toString().uppercase()

private fun setModified(file: File, seconds: Long) {
    Files.setLastModifiedTime(file.toPath(), FileTime.fromMillis(seconds * 1_000))
}

class TranscriptHistoryTest {
    private fun segment(
        id: UUID = UUID.randomUUID(),
        text: String,
        speakerName: String? = null,
        speakerClusterID: Int? = null,
        startTimestamp: Double = 0.0,
        isCommitted: Boolean = true,
    ) = SavedSegment(
        id = id,
        text = text,
        speakerName = speakerName,
        speakerClusterID = speakerClusterID,
        startTimestamp = startTimestamp,
        isCommitted = isCommitted,
    )

    private fun record(
        id: UUID = UUID.randomUUID(),
        startedAt: Double,
        endedAt: Double? = null,
        segments: List<SavedSegment>,
    ) = TranscriptSessionRecord(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = "small",
        inputName = "iPhone Microphone",
        segments = segments,
    )

    @Test
    fun `saving then loading a session returns exactly what was saved`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val original = record(startedAt = 100.0, endedAt = 200.0, segments = listOf(segment(text = "שלום")))
            val saved = store.save(original)
            assertEquals(true, saved)

            val loaded = store.load(original.id)
            assertEquals(original, loaded)
        }
    }

    @Test
    fun `a conversation keeps the engine, model and microphone of its first save, whatever is in use when it is saved again`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            val hello = segment(text = "שלום")
            store.save(TranscriptSessionRecord(id = id, startedAt = 100.0, endedAt = null, engine = TranscriptionEngineKind.AppleSpeech, modelVariant = null, inputName = "AirPods", segments = listOf(hello)))
            store.save(
                TranscriptSessionRecord(
                    id = id, startedAt = 100.0, endedAt = 300.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = "small",
                    inputName = "iPhone Microphone", segments = listOf(hello, segment(text = "מה נשמע")),
                ),
            )

            val loaded = assertNotNull(store.load(id))
            assertEquals(TranscriptionEngineKind.AppleSpeech, loaded.engine)
            assertNull(loaded.modelVariant)
            assertEquals("AirPods", loaded.inputName)
            assertTrue(loaded.segments.size == 2 && loaded.endedAt == 300.0)
            assertEquals(TranscriptionEngineKind.AppleSpeech, store.listSummaries().firstOrNull()?.engine)
        }
    }

    @Test
    fun `a line whose confidence is not a number is saved without it, instead of the whole conversation failing to save`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val odd = SavedSegment(id = UUID.randomUUID(), text = "הכדור בבוקר", speakerName = null, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true, confidence = Float.NaN)
            val changed = segment(text = "ובערב").copy(confidence = Float.POSITIVE_INFINITY)
            val original = record(startedAt = 100.0, endedAt = 200.0, segments = listOf(odd, changed, segment(text = "שלום")))
            assertTrue(store.save(original))

            val loaded = assertNotNull(store.load(original.id))
            assertEquals(listOf("הכדור בבוקר", "ובערב", "שלום"), loaded.segments.map { it.text })
            assertTrue(loaded.segments.all { it.confidence == null })
        }
    }

    @Test
    fun `renaming a voice rewrites every saved conversation that used the old name, and search finds the new one`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val old = record(
                startedAt = 100.0,
                segments = listOf(
                    segment(text = "שלום", speakerName = "רותיי", startTimestamp = 100.0),
                    segment(text = "מה נשמע", speakerName = "דני", startTimestamp = 105.0),
                ),
            )
            val other = record(startedAt = 200.0, segments = listOf(segment(text = "בוקר טוב", speakerName = "דני", startTimestamp = 200.0)))
            store.save(old)
            store.save(other)

            assertEquals(1, store.renameSpeaker(from = "רותיי", to = "רותי"))
            assertEquals(listOf<String?>("רותי", "דני"), store.load(old.id)?.segments?.map { it.speakerName })
            assertEquals(other, store.load(other.id))
            assertEquals(listOf(old.id), store.search("רותי").map { it.id })
            assertTrue(store.search("רותיי").isEmpty())
            assertEquals(0, store.renameSpeaker(from = "רותיי", to = "רותי"))
        }
    }

    @Test
    fun `a session with no segments is not written and reports it wasn't saved`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val empty = record(startedAt = 100.0, segments = emptyList())
            val saved = store.save(empty)
            assertEquals(false, saved)
            assertNull(store.load(empty.id))
            assertTrue(store.listSummaries().isEmpty())
            assertFalse(dir.exists())
        }
    }

    @Test
    fun `saving the same session id again overwrites rather than duplicating it`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val id = UUID.randomUUID()
            store.save(record(id = id, startedAt = 100.0, segments = listOf(segment(text = "גרסה ראשונה"))))
            store.save(record(id = id, startedAt = 100.0, segments = listOf(segment(text = "גרסה שנייה"))))

            val summaries = store.listSummaries()
            assertEquals(1, summaries.size)
            assertEquals("גרסה שנייה", store.load(id)?.segments?.firstOrNull()?.text)
        }
    }

    @Test
    fun `listing summaries orders sessions newest-started first`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val oldest = record(startedAt = 100.0, segments = listOf(segment(text = "ישן")))
            val middle = record(startedAt = 200.0, segments = listOf(segment(text = "אמצע")))
            val newest = record(startedAt = 300.0, segments = listOf(segment(text = "חדש")))
            store.save(middle)
            store.save(oldest)
            store.save(newest)

            val order = store.listSummaries().map { it.startedAt }
            assertEquals(listOf(300.0, 200.0, 100.0), order)
        }
    }

    @Test
    fun `a corrupt file is skipped while other sessions still list correctly`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val good = record(startedAt = 100.0, segments = listOf(segment(text = "תקין")))
            store.save(good)
            File(dir, "garbage.json").writeText("not valid json")

            val summaries = store.listSummaries()
            assertEquals(1, summaries.size)
            assertEquals(good.id, summaries.firstOrNull()?.id)
        }
    }

    @Test
    fun `a preview longer than 80 characters is truncated with an ellipsis`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val longText = "א".repeat(120)
            store.save(record(startedAt = 100.0, segments = listOf(segment(text = longText))))

            val preview = store.listSummaries().firstOrNull()?.preview
            assertEquals(81, preview?.length)
            assertEquals(true, preview?.endsWith("…"))
            assertEquals(longText.take(80) + "…", preview)
        }
    }

    @Test
    fun `a preview of 80 characters or fewer is not truncated`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val shortText = "ב".repeat(80)
            store.save(record(startedAt = 100.0, segments = listOf(segment(text = shortText))))

            val preview = store.listSummaries().firstOrNull()?.preview
            assertEquals(shortText, preview)
        }
    }

    @Test
    fun `search doesn't find a conversation by its numbered or unknown speaker labels, and a delete leaves no older search file`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(
                startedAt = 100.0,
                segments = listOf(
                    segment(text = "שלום", speakerName = "דובר 2", startTimestamp = 100.0),
                    segment(text = "מה נשמע", speakerName = "Unknown speaker", startTimestamp = 105.0),
                    segment(text = "כן", speakerName = "רותי", startTimestamp = 110.0),
                ),
            )
            store.save(saved)
            assertTrue(store.search("2").isEmpty())
            assertTrue(store.search("דובר").isEmpty())
            assertTrue(store.search("unknown").isEmpty())
            assertEquals(listOf(saved.id), store.search("רותי").map { it.id })

            val older = listOf("v1", "v2").map {
                File(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME), "${uuidString(saved.id)}.search-$it.txt")
            }
            for (file in older) file.writeText("שלום")
            store.delete(saved.id)
            assertTrue(older.all { !it.exists() })
        }
    }

    @Test
    fun `search is case-insensitive over segment text`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "Hello Grandma"))))

            assertEquals(1, store.search("hello").size)
            assertEquals(1, store.search("GRANDMA").size)
            assertTrue(store.search("nonexistent").isEmpty())
        }
    }

    @Test
    fun `a search of several words finds a conversation holding all of them, in any order, on any line or in a name`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "הרופא אמר"), segment(text = "שני כדורים ביום", speakerName = "דני"))))
            store.save(record(startedAt = 200.0, segments = listOf(segment(text = "כדורים של שוקולד"))))

            assertEquals(1, store.search("רופא כדורים").size)
            assertEquals(1, store.search("כדורים   רופא").size)
            assertEquals(1, store.search("דני רופא").size)
            assertEquals(2, store.search("כדורים").size)
            assertTrue(store.search("רופא שוקולד").isEmpty())
        }
    }

    @Test
    fun `a search word typed with the article also finds it with another prefix or none, but a short name is not cut down`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "צריך ללכת לרופא מחר"))))
            store.save(record(startedAt = 200.0, segments = listOf(segment(text = "רופא שיניים"))))
            store.save(record(startedAt = 300.0, segments = listOf(segment(text = "פרדס גדול"))))

            assertEquals(2, store.search("הרופא").size)
            assertEquals(1, store.search("הרופא מחר").size)
            assertTrue(store.search("הדס").isEmpty())

            val loaded = assertNotNull(store.load(store.search("מחר")[0].id))
            assertEquals(loaded.segments.map { it.id }, TranscriptHistoryStore.matchingSegmentIDs(loaded, query = "הרופא"))
        }
    }

    @Test
    fun `a query typed with an attached preposition or conjunction also finds the bare word`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "הרופא אמר"))))
            store.save(record(startedAt = 200.0, segments = listOf(segment(text = "רופא שיניים"))))
            store.save(record(startedAt = 300.0, segments = listOf(segment(text = "שני כדורים ביום"))))

            // "to the doctor" and "and the doctor" both find a bare "doctor",
            // whether or not the saved text itself carries the article.
            assertEquals(2, store.search("לרופא").size)
            assertEquals(2, store.search("והרופא").size)
            // "and pills" finds a bare "pills" with no prefix at all.
            assertEquals(1, store.search("וכדורים").size)
            // "for tomorrow" finds "tomorrow": a stem of three letters is kept.
            store.save(record(startedAt = 400.0, segments = listOf(segment(text = "נתראה מחר בבוקר"))))
            assertEquals(1, store.search("למחר").size)
        }
    }

    @Test
    fun `a word finds its plural and its 'of' form, and a plural finds the word, though the ending changes`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            // Lines as the home computer wrote them from broadcast speech.
            val medicines = record(startedAt = 100.0, segments = listOf(segment(text = "לקחת את התרופות שלך?")))
            val dinner = record(startedAt = 200.0, segments = listOf(segment(text = "אחרי ארוחת הערב")))
            val places = record(startedAt = 300.0, segments = listOf(segment(text = "יש הרבה מקומות כאלה")))
            val medicine = record(startedAt = 400.0, segments = listOf(segment(text = "הרופא נתן תרופה חדשה")))
            val child = record(startedAt = 500.0, segments = listOf(segment(text = "הילד חזר מבית הספר")))
            val time = record(startedAt = 600.0, segments = listOf(segment(text = "אין לי זמן עכשיו")))
            for (saved in listOf(medicines, dinner, places, medicine, child, time)) {
                store.save(saved)
            }

            assertEquals(setOf(medicines.id, medicine.id), store.search("תרופה").map { it.id }.toSet())
            assertEquals(setOf(medicines.id, medicine.id), store.search("תרופות").map { it.id }.toSet())
            assertEquals(listOf(dinner.id), store.search("ארוחה").map { it.id })
            assertEquals(listOf(places.id), store.search("מקום").map { it.id })
            assertEquals(listOf(child.id), store.search("ילדים").map { it.id })
            assertEquals(listOf(time.id), store.search("זמנים").map { it.id })
            assertEquals(2, store.search("התרופה").size)
            assertEquals(listOf("ילדים", "ילד"), TranscriptHistoryStore.SearchWord("ילדים").forms)

            val loaded = assertNotNull(store.load(medicines.id))
            assertEquals(loaded.segments.map { it.id }, TranscriptHistoryStore.matchingSegmentIDs(loaded, query = "תרופה"))
            // A word of two letters is left alone: "ben" (son) is inside too many others.
            store.save(record(startedAt = 700.0, segments = listOf(segment(text = "שלושה בנים"))))
            assertTrue(store.search("בן").isEmpty())
            // One of three is not: "money" finds "the funds".
            store.save(record(startedAt = 800.0, segments = listOf(segment(text = "הכספים הגיעו"))))
            assertEquals(1, store.search("כסף").size)
        }
    }

    @Test
    fun `dictation punctuation and Hebrew quotes around a search query don't stop it matching`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "לקחת כדורים בבוקר"))))
            store.save(record(startedAt = 200.0, segments = listOf(segment(text = "ביקור אצל הרופא"))))

            assertEquals(1, store.search("כדורים?").size)
            assertEquals(1, store.search("״רופא״").size)
        }
    }

    @Test
    fun `a number is found typed without the marks it was saved with - an amount, a phone number, a time`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "זה עולה 2,500 שקל"))))
            store.save(record(startedAt = 200.0, segments = listOf(segment(text = "תתקשרי אליו ל-050-1234567"))))
            store.save(record(startedAt = 300.0, segments = listOf(segment(text = "התור הוא בשעה 10:30"))))
            store.save(record(startedAt = 400.0, segments = listOf(segment(text = "5:30 בבוקר, לא לשכוח"))))

            assertEquals(1, store.search("530").size)
            assertEquals(1, store.search("2500").size)
            assertEquals(1, store.search("2,500").size)
            assertEquals(1, store.search("0501234567").size)
            assertEquals(1, store.search("050-1234567").size)
            assertEquals(1, store.search("10:30").size)
            assertEquals(1, store.search("1030").size)
            assertTrue(store.search("2501").isEmpty())
        }
    }

    @Test
    fun `a search result's preview shows the line that matched, not always the conversation's first line`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val segments = mutableListOf(segment(text = "בוקר טוב"))
            segments += (1..13).map { segment(text = "שורת מילוי מספר $it") }
            segments.add(segment(text = "לקחת כדור אחד בבוקר"))
            store.save(record(startedAt = 100.0, segments = segments))

            val results = store.search("כדור")
            assertEquals(1, results.size)
            assertEquals(true, results.firstOrNull()?.preview?.contains("לקחת כדור אחד בבוקר"))
            assertEquals(false, results.firstOrNull()?.preview?.contains("בוקר טוב"))

            // Listing without a query is unaffected: the cached line-1 preview
            // still reads the greeting.
            assertEquals("בוקר טוב", store.listSummaries().firstOrNull()?.preview)
        }
    }

    @Test
    fun `search matches on speaker name even when the text doesn't contain the query`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "מה שלומך", speakerName = "סבתא"))))

            assertEquals(1, store.search("סבתא").size)
            assertTrue(store.search("סבא").isEmpty())
        }
    }

    @Test
    fun `search ignores Hebrew niqqud on both sides of the comparison`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "שָׁלוֹם"))))

            assertEquals(1, store.search("שלום").size)
        }
    }

    @Test
    fun `a Maqaf-joined word is found whether the query types it with a space or a plain hyphen`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "ביקור בבית־חולים בתל־אביב"))))

            assertEquals(1, store.search("תל אביב").size)
            assertEquals(1, store.search("בית חולים").size)
            // The real regression: a query typed with a plain hyphen instead of
            // a space ("tel-aviv") used to fail, because the Maqaf in the saved
            // text was deleted outright rather than turned into a space, fusing
            // the two halves into one word the hyphenated query never matched.
            assertEquals(1, store.search("תל-אביב").size)
        }
    }

    @Test
    fun `an empty or whitespace-only search query returns every session`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "אחד"))))
            store.save(record(startedAt = 200.0, segments = listOf(segment(text = "שתיים"))))

            assertEquals(2, store.search("").size)
            assertEquals(2, store.search("   ").size)
        }
    }

    @Test
    fun `delete and deleteAll remove sessions, and deleting a missing id doesn't throw`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            val first = record(startedAt = 100.0, segments = listOf(segment(text = "אחד")))
            val second = record(startedAt = 200.0, segments = listOf(segment(text = "שתיים")))
            store.save(first)
            store.save(second)

            store.delete(first.id)
            assertNull(store.load(first.id))
            assertEquals(1, store.listSummaries().size)

            store.delete(UUID.randomUUID())

            store.deleteAll()
            assertTrue(store.listSummaries().isEmpty())
        }
    }

    @Test
    fun `delete all still deletes every conversation when the cache folder can't be removed`() {
        withHistoryDirectory("ozen-history") { dir ->
            val summaries = File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME)
            val store = TranscriptHistoryStore(dir)
            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "אחד"))))
            assertEquals(1, store.listSummaries().size)
            Files.setPosixFilePermissions(summaries.toPath(), PosixFilePermissions.fromString("r-x------"))

            store.deleteAll()
            assertTrue(store.listSummaries().isEmpty())
        }
    }

    @Test
    fun `a summary made from a version read before the conversation was saved again is not kept as fresh`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)
            var conversation = record(startedAt = 100.0, segments = listOf(segment(text = "אחד")))
            store.save(conversation)
            val file = File(dir, "${uuidString(conversation.id)}.json")
            val readBefore = Files.getLastModifiedTime(file.toPath())
            val oldSummary = TranscriptSessionSummary.summarizing(conversation)

            Thread.sleep(20)
            conversation = conversation.copy(segments = conversation.segments + segment(text = "שתיים"))
            store.save(conversation)
            store.writeSummary(oldSummary, file, readBefore)

            assertEquals(2, store.listSummaries().firstOrNull()?.segmentCount)
        }
    }

    @Test
    fun `exported text matches the exact expected line format`() {
        val withName = segment(text = "שלום", speakerName = "סבתא", startTimestamp = 3_661.0)
        val withoutName = segment(text = "מה נשמע", speakerName = null, startTimestamp = 3_665.0)
        val session = record(startedAt = 3_661.0, segments = listOf(withName, withoutName))

        val text = TranscriptHistoryStore.exportText(session)
        assertEquals("שיחה מתאריך 01.01.1970\n\n[01:01:01] סבתא: שלום\n[01:01:05] מה נשמע", text)
    }

    @Test
    fun `shared text warns on a line the screen marked unsure, unless the marks are turned off`() {
        val unsure = segment(text = "שני כדורים בעשר וחצי", speakerName = "רופא", startTimestamp = 3_661.0).copy(confidence = 0.7f)
        val sure = segment(text = "ולחזור בעוד חודש", speakerName = null, startTimestamp = 3_665.0).copy(confidence = 0.97f)
        val session = record(startedAt = 3_661.0, segments = listOf(unsure, sure))
        val offset: (Double) -> Int = { 0 }
        assertEquals(
            "שיחה מתאריך 01.01.1970\n\n[01:01:01] ייתכן שלא נשמע נכון. רופא: שני כדורים בעשר וחצי\n[01:01:05] ולחזור בעוד חודש",
            TranscriptHistoryStore.exportText(session, utcOffsetAt = offset, marksUncertain = true),
        )
        assertEquals(
            "שיחה מתאריך 01.01.1970\n\n[01:01:01] רופא: שני כדורים בעשר וחצי\n[01:01:05] ולחזור בעוד חודש",
            TranscriptHistoryStore.exportText(session, utcOffsetAt = offset, marksUncertain = false),
        )
    }

    @Test
    fun `shared text drops 'Unknown speaker' in any language but keeps real and numbered names`() {
        val session = record(
            startedAt = 3_661.0,
            segments = listOf(
                segment(text = "שלום", speakerName = "סבתא", startTimestamp = 3_661.0),
                segment(text = "מה נשמע", speakerName = "דובר לא ידוע", startTimestamp = 3_662.0),
                segment(text = "טוב", speakerName = "Unknown speaker", startTimestamp = 3_663.0),
                segment(text = "יופי", speakerName = "דובר 2", startTimestamp = 3_664.0),
            ),
        )
        assertEquals(
            "שיחה מתאריך 01.01.1970\n\n[01:01:01] סבתא: שלום\n[01:01:02] מה נשמע\n[01:01:03] טוב\n[01:01:04] דובר 2: יופי",
            TranscriptHistoryStore.exportText(session),
        )
    }

    @Test
    fun `shared text keeps each line's own clock time across daylight saving`() {
        val israel = TimeZone.getTimeZone("Asia/Jerusalem")
        val offset: (Double) -> Int = { israel.getOffset((it * 1_000).toLong()) / 1_000 }
        val summer = record(startedAt = 1_782_905_400.0, segments = listOf(segment(text = "בקיץ", startTimestamp = 1_782_905_400.0)))
        val winter = record(startedAt = 1_768_476_600.0, segments = listOf(segment(text = "בחורף", startTimestamp = 1_768_476_600.0)))

        assertTrue(TranscriptHistoryStore.exportText(summer, utcOffsetAt = offset).endsWith("[14:30:00] בקיץ"))
        assertTrue(TranscriptHistoryStore.exportText(winter, utcOffsetAt = offset).endsWith("[13:30:00] בחורף"))
    }

    @Test
    fun `the export heading and numbers block are in English when the app is`() {
        Localization.withLanguage(UILanguage.English) {
            val untitled = record(startedAt = 3_661.0, segments = listOf(segment(text = "שלום", startTimestamp = 3_661.0)))
            assertTrue(TranscriptHistoryStore.exportText(untitled).startsWith("Conversation from 01.01.1970"))

            val segments = (0 until 19).map { index -> segment(text = "line", startTimestamp = 3_600.0 + index) }.toMutableList()
            segments[3] = segment(text = "10:30", speakerName = "doctor", startTimestamp = 3_603.0)
            segments.add(segment(text = "thanks", startTimestamp = 3_619.0))
            val long = TranscriptHistoryStore.exportText(record(startedAt = 3_600.0, segments = segments))
            assertTrue(long.contains("Numbers mentioned:"))
            assertTrue(long.contains("The conversation:"))
        }
    }

    @Test
    fun `a long conversation shared as text opens with its lines that had numbers, a short one doesn't`() {
        val segments = (0 until 19).map { index ->
            segment(text = "משפט רגיל", speakerName = null, startTimestamp = 3_600.0 + index)
        }.toMutableList()
        segments[3] = segment(text = "התור ב-10:30", speakerName = "רופא", startTimestamp = 3_603.0)
        segments[7] = segment(text = "שלושה כדורים ביום", speakerName = null, startTimestamp = 3_607.0)
        segments[9] = segment(text = "רק פעם אחת", speakerName = null, startTimestamp = 3_609.0)

        val short = TranscriptHistoryStore.exportText(record(startedAt = 3_600.0, segments = segments.toList()))
        assertEquals(false, short.contains("מספרים שנאמרו"))

        segments.add(segment(text = "תודה", speakerName = null, startTimestamp = 3_619.0))
        val long = TranscriptHistoryStore.exportText(record(startedAt = 3_600.0, segments = segments.toList()))
        assertTrue(long.startsWith("שיחה מתאריך 01.01.1970\n\nמספרים שנאמרו:\n[01:00:03] רופא: התור ב-10:30\n[01:00:07] שלושה כדורים ביום\n\nהשיחה:\n[01:00:00] משפט רגיל\n"))
        assertTrue(long.endsWith("[01:00:19] תודה"))
    }

    @Test
    fun `make(from) drops empty-text segments and resolves speaker names`() {
        val keptID = UUID.randomUUID()
        val droppedID = UUID.randomUUID()
        val unnamedID = UUID.randomUUID()
        val live = listOf(
            TranscriptSegment(id = keptID, text = "שלום סבתא", isCommitted = true, speakerClusterID = 0, startTimestamp = 10.0, lastUpdateTimestamp = 10.0),
            TranscriptSegment(id = droppedID, text = "   ", isCommitted = false, speakerClusterID = null, startTimestamp = 20.0, lastUpdateTimestamp = 20.0),
            TranscriptSegment(id = unnamedID, text = "מי זה", isCommitted = false, speakerClusterID = 3, startTimestamp = 30.0, lastUpdateTimestamp = 30.0),
        )

        val record = TranscriptSessionRecord.make(
            from = live,
            speakerName = { segment ->
                when (segment.speakerClusterID) {
                    0 -> "סבתא"
                    else -> null
                }
            },
            id = UUID.randomUUID(),
            startedAt = 5.0,
            endedAt = null,
            engine = TranscriptionEngineKind.AppleSpeech,
            modelVariant = null,
            inputName = null,
        )

        assertEquals(listOf(keptID, unnamedID), record.segments.map { it.id })
        assertEquals("סבתא", record.segments.firstOrNull()?.speakerName)
        assertNull(record.segments.lastOrNull()?.speakerName)
        assertEquals(3, record.segments.lastOrNull()?.speakerClusterID)
    }

    @Test
    fun `a record missing segments, modelVariant, inputName, and endedAt still decodes with defaults`() {
        val json = """{"id":"1E2B4D2A-6C5F-4F1B-9C3E-000000000001","startedAt":100,"engine":"whisperKit"}"""
        val decoded = TranscriptSessionRecord.fromJson(json)

        assertEquals(100.0, decoded.startedAt)
        assertEquals(TranscriptionEngineKind.WhisperKit, decoded.engine)
        assertNull(decoded.endedAt)
        assertNull(decoded.modelVariant)
        assertNull(decoded.inputName)
        assertTrue(decoded.segments.isEmpty())
    }

    @Test
    fun `total size on disk is what the saved files take, not their folders, and zero once everything is deleted`() {
        withHistoryDirectory("ozen-history") { dir ->
            val store = TranscriptHistoryStore(dir)

            assertEquals(0L, store.totalSizeOnDisk())

            store.save(record(startedAt = 100.0, segments = listOf(segment(text = "בדיקה"))))
            assertTrue(store.totalSizeOnDisk() > 0)
            val fileBytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertEquals(fileBytes, store.totalSizeOnDisk())

            store.deleteAll()
            assertEquals(0L, store.totalSizeOnDisk())
        }
    }

    @Test
    fun `export applies the caller's UTC offset so times read as local clock time`() {
        val record = TranscriptSessionRecord(
            startedAt = 0.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
            segments = listOf(SavedSegment(id = UUID.randomUUID(), text = "בוקר", speakerName = null, speakerClusterID = null, startTimestamp = 3_600.0, isCommitted = true)),
        )
        assertEquals("שיחה מתאריך 01.01.1970\n\n[01:00:00] בוקר", TranscriptHistoryStore.exportText(record))
        assertEquals("שיחה מתאריך 01.01.1970\n\n[04:00:00] בוקר", TranscriptHistoryStore.exportText(record, utcOffsetSeconds = 3 * 3_600))
        // West of Greenwich, a conversation that started at midnight UTC
        // was still on the previous day.
        assertEquals("שיחה מתאריך 31.12.1969\n\n[23:00:00] בוקר", TranscriptHistoryStore.exportText(record, utcOffsetSeconds = -2 * 3_600))
    }

    @Test
    fun `the summary lists the real names that took part, once each, without generic labels`() {
        fun line(name: String?) = SavedSegment(id = UUID.randomUUID(), text = "שלום", speakerName = name, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true)
        val names = TranscriptSessionSummary.realNames(
            listOf(
                line("דובר 2"), line("רותי"), line(null), line("אבי"), line("רותי"),
                line("דובר לא ידוע"), line("Speaker 3"), line("Unknown speaker"), line("דובר חדש"),
            ),
        )
        assertEquals(listOf("רותי", "אבי", "דובר חדש"), names)
    }

    @Test
    fun `a placeholder saved while the app was in one language is still generic after switching to the other`() {
        // The speaker name is a permanent snapshot: a session recorded in
        // Hebrew keeps its placeholder even after the app's language later
        // changes to English, at which point
        // EmbeddingClusterer.unknownSpeakerName itself evaluates to
        // "Unknown speaker" instead.
        Localization.withLanguage(UILanguage.English) {
            assertTrue(TranscriptSessionSummary.isGenericLabel("דובר לא ידוע"))
        }
        Localization.withLanguage(UILanguage.Hebrew) {
            assertTrue(TranscriptSessionSummary.isGenericLabel("Unknown speaker"))
        }
    }

    @Test
    fun `placeholders saved in any of the app's languages stay generic, real names don't`() {
        Localization.withLanguage(UILanguage.English) {
            for (label in listOf("Unbekannter Sprecher", "未知说话人", "متحدث غير معروف", "Sprecher 3", "说话人 12", "Speaker 2")) {
                assertTrue(TranscriptSessionSummary.isGenericLabel(label), label)
            }
            for (name in listOf("Dana", "Sprecher", "Speaker two", "דנה")) {
                assertFalse(TranscriptSessionSummary.isGenericLabel(name), name)
            }
        }
        Localization.withLanguage(UILanguage.German) {
            assertTrue(TranscriptSessionSummary.isUnknownSpeakerLabel("דובר לא ידוע"))
            assertFalse(TranscriptSessionSummary.isUnknownSpeakerLabel("Sprecher 3"))
        }
    }

    private fun summary(names: List<String>) = TranscriptSessionSummary(
        id = UUID.randomUUID(), startedAt = 0.0, endedAt = null, segmentCount = 1, preview = "",
        engine = TranscriptionEngineKind.WhisperKit, speakerNames = names,
    )

    @Test
    fun `top speaker names rank by how many sessions they appeared in, ties broken alphabetically`() {
        val names = TranscriptSessionSummary.topSpeakerNames(
            listOf(
                summary(listOf("רותי", "אבי")),
                summary(listOf("רותי")),
                summary(listOf("רותי", "דנה")),
                summary(listOf("דנה")),
            ),
        )
        assertEquals(listOf("רותי", "דנה", "אבי"), names)
    }

    @Test
    fun `top speaker names counts a name once per session, however many times it repeats in speakerNames`() {
        // "רותי" repeats within one session but should still only count once
        // against that session, not three times.
        val names = TranscriptSessionSummary.topSpeakerNames(listOf(summary(listOf("רותי", "רותי", "אבי")), summary(listOf("אבי"))))
        assertEquals(listOf("אבי", "רותי"), names)
    }

    @Test
    fun `a chosen name stays through a search that finds none of their conversations, and goes once none is left at all`() {
        val othersOnly = listOf(summary(listOf("אבי")), summary(listOf("דנה")))
        assertEquals("רותי", TranscriptSessionSummary.speakerFilter("רותי", keptAfterLoading = othersOnly, everyConversation = false))
        assertNull(TranscriptSessionSummary.speakerFilter("רותי", keptAfterLoading = othersOnly, everyConversation = true))
        assertEquals("רותי", TranscriptSessionSummary.speakerFilter("רותי", keptAfterLoading = listOf(summary(listOf("רותי"))), everyConversation = true))
        assertNull(TranscriptSessionSummary.speakerFilter(null, keptAfterLoading = othersOnly, everyConversation = true))
    }
}

class TranscriptHistorySummaryCacheTest {
    private fun record(id: UUID = UUID.randomUUID(), startedAt: Double, texts: List<String>) = TranscriptSessionRecord(
        id = id,
        startedAt = startedAt,
        endedAt = null,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = "small",
        inputName = null,
        segments = texts.map {
            SavedSegment(id = UUID.randomUUID(), text = it, speakerName = null, speakerClusterID = null, startTimestamp = startedAt, isCommitted = true)
        },
    )

    private fun recordFile(dir: File, id: UUID) = File(dir, "${uuidString(id)}.json")

    private fun summaryFile(dir: File, id: UUID) = File(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME), "${uuidString(id)}.json")

    @Test
    fun `the list comes from the summary file, without reading the full conversation`() {
        withHistoryDirectory("ozen-history-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(startedAt = 100.0, texts = listOf("שלום סבתא", "מה שלומך"))
            store.save(saved)
            assertTrue(summaryFile(dir, saved.id).exists())

            // Unreadable conversation, older than its summary: only a list that
            // trusts the summary can still show it.
            recordFile(dir, saved.id).writeText("not json")
            setModified(recordFile(dir, saved.id), 1_000)

            val listed = store.listSummaries()
            assertEquals(1, listed.size)
            assertEquals("שלום סבתא", listed.firstOrNull()?.preview)
            assertEquals(2, listed.firstOrNull()?.segmentCount)
        }
    }

    @Test
    fun `a conversation saved by an older build, with no summary, is listed and gets one`() {
        withHistoryDirectory("ozen-history-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val old = record(startedAt = 50.0, texts = listOf("ישן"))
            dir.mkdirs()
            recordFile(dir, old.id).writeText(old.toJson())

            assertEquals(listOf(old.id), store.listSummaries().map { it.id })
            assertTrue(summaryFile(dir, old.id).exists())
        }
    }

    @Test
    fun `a save that skips the search caches writes no summary file, but the conversation still lists correctly`() {
        withHistoryDirectory("ozen-history-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(startedAt = 100.0, texts = listOf("שלום סבתא", "מה שלומך"))
            store.save(saved, updateSearchCaches = false)

            assertFalse(summaryFile(dir, saved.id).exists())
            val listed = store.listSummaries()
            assertEquals(1, listed.size)
            assertEquals("שלום סבתא", listed.firstOrNull()?.preview)
            assertEquals(2, listed.firstOrNull()?.segmentCount)
            // Listing rebuilds it, the way it does for any other missing cache.
            assertTrue(summaryFile(dir, saved.id).exists())
        }
    }

    @Test
    fun `a summary older than its conversation is rebuilt, not trusted`() {
        withHistoryDirectory("ozen-history-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id = id, startedAt = 10.0, texts = listOf("אחת")))

            // The conversation changed after its summary was written.
            recordFile(dir, id).writeText(record(id = id, startedAt = 10.0, texts = listOf("אחת", "שתיים", "שלוש")).toJson())
            setModified(summaryFile(dir, id), 1_000)
            setModified(recordFile(dir, id), 2_000)

            assertEquals(3, store.listSummaries().firstOrNull()?.segmentCount)
        }
    }

    @Test
    fun `an unreadable summary is rebuilt from the conversation`() {
        withHistoryDirectory("ozen-history-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(startedAt = 10.0, texts = listOf("טקסט"))
            store.save(saved)
            summaryFile(dir, saved.id).writeText("{")

            assertEquals("טקסט", store.listSummaries().firstOrNull()?.preview)
        }
    }

    @Test
    fun `deleting a conversation removes its summary, so it can't reappear in the list`() {
        withHistoryDirectory("ozen-history-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val first = record(startedAt = 10.0, texts = listOf("א"))
            val second = record(startedAt = 20.0, texts = listOf("ב"))
            store.save(first)
            store.save(second)

            store.delete(first.id)
            assertFalse(summaryFile(dir, first.id).exists())
            assertEquals(listOf(second.id), store.listSummaries().map { it.id })

            store.deleteAll()
            assertTrue(store.listSummaries().isEmpty())
            assertFalse(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME).exists())
            assertEquals(0L, store.totalSizeOnDisk())
        }
    }
}

class TranscriptHistorySearchCacheTest {
    private fun record(id: UUID = UUID.randomUUID(), startedAt: Double = 10.0, lines: List<Pair<String, String?>>) = TranscriptSessionRecord(
        id = id,
        startedAt = startedAt,
        endedAt = null,
        engine = TranscriptionEngineKind.WhisperKit,
        modelVariant = null,
        inputName = null,
        segments = lines.map {
            SavedSegment(id = UUID.randomUUID(), text = it.first, speakerName = it.second, speakerClusterID = null, startTimestamp = startedAt, isCommitted = true)
        },
    )

    private fun recordFile(dir: File, id: UUID) = File(dir, "${uuidString(id)}.json")

    private fun searchFile(dir: File, id: UUID) = File(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME), "${uuidString(id)}.search-v3.txt")

    @Test
    fun `search answers from the prepared text, without reading the conversation`() {
        withHistoryDirectory("ozen-history-search") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(lines = listOf("הלכנו לשוק" to "שרה"))
            store.save(saved)
            assertTrue(searchFile(dir, saved.id).exists())

            recordFile(dir, saved.id).writeText("not json")
            setModified(recordFile(dir, saved.id), 1_000)

            assertEquals(listOf(saved.id), store.search("שוק").map { it.id })
            assertEquals(listOf(saved.id), store.search("שרה").map { it.id })
            assertTrue(store.search("ים").isEmpty())
        }
    }

    @Test
    fun `a save that skips the search caches writes no search-text file, but the conversation is still found`() {
        withHistoryDirectory("ozen-history-search") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(lines = listOf("הלכנו לשוק" to "שרה"))
            store.save(saved, updateSearchCaches = false)

            assertFalse(searchFile(dir, saved.id).exists())
            assertEquals(listOf(saved.id), store.search("שוק").map { it.id })
            // The slow path it fell back to writes the file for next time.
            assertTrue(searchFile(dir, saved.id).exists())
        }
    }

    @Test
    fun `a conversation saved by an older build is searched in full and gets its text file`() {
        withHistoryDirectory("ozen-history-search") { dir ->
            val store = TranscriptHistoryStore(dir)
            val old = record(lines = listOf("שָׁלוֹם לכולם" to null))
            dir.mkdirs()
            recordFile(dir, old.id).writeText(old.toJson())

            assertEquals(listOf(old.id), store.search("לכולם שלום").map { it.id })
            assertTrue(searchFile(dir, old.id).exists())
            // And the file it wrote answers the next search the same way.
            assertEquals(listOf(old.id), store.search("שלום").map { it.id })
            assertTrue(store.search("להתראות").isEmpty())
        }
    }

    @Test
    fun `a text file older than its conversation is not trusted`() {
        withHistoryDirectory("ozen-history-search") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id = id, lines = listOf("בוקר" to null)))

            recordFile(dir, id).writeText(record(id = id, lines = listOf("בוקר" to null, "ערב" to null)).toJson())
            setModified(searchFile(dir, id), 1_000)
            setModified(recordFile(dir, id), 2_000)

            assertEquals(listOf(id), store.search("ערב").map { it.id })
        }
    }

    @Test
    fun `a word split across two caption lines does not count as found, two words on two lines do`() {
        withHistoryDirectory("ozen-history-search") { dir ->
            val store = TranscriptHistoryStore(dir)
            store.save(record(lines = listOf("אבא" to null, "בית" to null)))

            assertTrue(store.search("אבית").isEmpty())
            assertTrue(store.search("אבאבית").isEmpty())
            assertEquals(1, store.search("אבא\nבית").size)
        }
    }

    @Test
    fun `deleting a conversation removes its text file`() {
        withHistoryDirectory("ozen-history-search") { dir ->
            val store = TranscriptHistoryStore(dir)
            val saved = record(lines = listOf("משהו" to null))
            store.save(saved)
            store.delete(saved.id)
            assertFalse(searchFile(dir, saved.id).exists())
        }
    }
}

class TranscriptHistoryMatchingLinesTest {
    private fun line(text: String, name: String? = null) =
        SavedSegment(id = UUID.randomUUID(), text = text, speakerName = name, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true)

    private fun record(segments: List<SavedSegment>) =
        TranscriptSessionRecord(id = UUID.randomUUID(), startedAt = 0.0, endedAt = null, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null, segments = segments)

    @Test
    fun `the lines a search found, in order, by words or by who said them`() {
        val lines = listOf(
            line("הרופא אמר לקחת את התְּרוּפָה בבוקר", "דני"),
            line("טוב", "שרה"),
            line("ואת התרופה השנייה בערב", "דני"),
            line("Aspirin?", "רותי"),
        )
        val conversation = record(lines)
        assertEquals(listOf(lines[0].id, lines[2].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = " תרופה "))
        assertEquals(listOf(lines[1].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "שרה"))
        assertEquals(listOf(lines[3].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "ASPIRIN"))
    }

    @Test
    fun `with several words, the lines holding all of them, when no line does, the lines holding any`() {
        val lines = listOf(
            line("הרופא אמר לקחת את התרופה בבוקר", "דני"),
            line("טוב", "שרה"),
            line("ואת התרופה השנייה בערב", "דני"),
        )
        val conversation = record(lines)
        assertEquals(listOf(lines[0].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "בבוקר תרופה"))
        assertEquals(listOf(lines[2].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "דני ערב"))
        assertEquals(listOf(lines[0].id, lines[2].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "רופא ערב"))
        assertEquals(listOf(lines[0].id), TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "רופא ים"))
    }

    @Test
    fun `an empty search, or one that matches nothing, finds no lines`() {
        val conversation = record(listOf(line("שלום")))
        assertTrue(TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "  ").isEmpty())
        assertTrue(TranscriptHistoryStore.matchingSegmentIDs(conversation, query = "להתראות").isEmpty())
    }
}

class TranscriptHistoryStarredTest {
    private fun live(text: String) =
        TranscriptSegment(id = UUID.randomUUID(), text = text, isCommitted = true, speakerClusterID = null, startTimestamp = 3_600.0, lastUpdateTimestamp = 3_600.0)

    @Test
    fun `starred lines are saved as starred, counted in the summary, and marked in shared text`() {
        val lines = listOf(live("שלום"), live("לקחת כדור אחד בבוקר"), live("ביי"))
        val record = TranscriptSessionRecord.make(
            from = lines,
            speakerName = { null },
            id = UUID.randomUUID(),
            startedAt = 3_600.0,
            endedAt = null,
            engine = TranscriptionEngineKind.WhisperKit,
            modelVariant = null,
            inputName = null,
            starred = setOf(lines[1].id),
        )
        assertEquals(listOf(false, true, false), record.segments.map { it.isStarred })

        withHistoryDirectory("ozen-stars") { dir ->
            val store = TranscriptHistoryStore(dir)
            store.save(record)
            assertEquals(listOf(false, true, false), store.load(record.id)?.segments?.map { it.isStarred })
            assertEquals(1, store.listSummaries().firstOrNull()?.starredCount)

            val text = TranscriptHistoryStore.exportText(record)
            assertEquals("שיחה מתאריך 01.01.1970\n\n[01:00:00] שלום\n★ [01:00:00] לקחת כדור אחד בבוקר\n[01:00:00] ביי", text)
        }
    }

    @Test
    fun `a line saved before stars existed loads as not starred`() {
        val json = """{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","text":"ישן","startTimestamp":1,"isCommitted":true}"""
        val line = SavedSegment.fromJson(json)
        assertEquals(false, line.isStarred)
        assertNull(line.speakerName)
    }

    @Test
    fun `a summary file from before stars were counted is rebuilt`() {
        withHistoryDirectory("ozen-stars-cache") { dir ->
            val store = TranscriptHistoryStore(dir)
            val record = TranscriptSessionRecord(
                startedAt = 1.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
                segments = listOf(SavedSegment(id = UUID.randomUUID(), text = "חשוב", speakerName = null, speakerClusterID = null, startTimestamp = 1.0, isCommitted = true, isStarred = true)),
            )
            store.save(record)
            // What a build with format 1 wrote: no starredCount at all.
            val summaryFile = File(File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME), "${uuidString(record.id)}.json")
            val old = """{"format":1,"summary":{"id":"${uuidString(record.id)}","startedAt":1,"segmentCount":1,"preview":"חשוב","engine":"whisperKit","speakerNames":[]}}"""
            summaryFile.writeText(old)

            assertEquals(1, store.listSummaries().firstOrNull()?.starredCount)
        }
    }
}

class TranscriptHistoryAllStarredTest {
    private fun line(text: String, starred: Boolean) =
        SavedSegment(id = UUID.randomUUID(), text = text, speakerName = null, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true, isStarred = starred)

    @Test
    fun `starred lines come newest conversation first, in spoken order, and unstarred conversations are left out`() {
        withHistoryDirectory("ozen-all-stars") { dir ->
            val store = TranscriptHistoryStore(dir)
            val morning = TranscriptSessionRecord(
                startedAt = 100.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
                segments = listOf(line("כדור בבוקר", starred = true), line("טוב", starred = false), line("ובערב שניים", starred = true)),
            )
            val evening = TranscriptSessionRecord(
                startedAt = 900.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
                segments = listOf(line("התור ביום שלישי", starred = true)),
            )
            val chat = TranscriptSessionRecord(
                startedAt = 500.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
                segments = listOf(line("מה נשמע", starred = false)),
            )
            for (record in listOf(morning, evening, chat)) store.save(record)

            val starred = store.starredLines()
            assertEquals(listOf("התור ביום שלישי", "כדור בבוקר", "ובערב שניים"), starred.map { it.segment.text })
            assertEquals(listOf(evening.id, morning.id, morning.id), starred.map { it.sessionID })
            assertEquals(900.0, starred.firstOrNull()?.sessionStartedAt)
        }
    }

    @Test
    fun `a starred line is judged unsure by the engine its conversation was recorded with`() {
        withHistoryDirectory("ozen-unsure-stars") { dir ->
            val store = TranscriptHistoryStore(dir)
            val doubtful = line("שני כדורים בעשר וחצי", starred = true).copy(confidence = 0.7f)
            val clear = line("ולחזור בעוד חודש", starred = true).copy(confidence = 0.97f)
            store.save(TranscriptSessionRecord(startedAt = 900.0, engine = TranscriptionEngineKind.HomeServer, modelVariant = null, inputName = null, segments = listOf(doubtful, clear)))
            store.save(TranscriptSessionRecord(startedAt = 100.0, engine = TranscriptionEngineKind.AppleSpeech, modelVariant = null, inputName = null, segments = listOf(doubtful)))

            val starred = store.starredLines()
            assertEquals(
                listOf(TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.AppleSpeech),
                starred.map { it.engine },
            )
            assertEquals(listOf(true, false, false), starred.map { it.isUncertain })
        }
    }

    @Test
    fun `a starred line and shared text are judged by the model their conversation was recorded with`() {
        withHistoryDirectory("ozen-unsure-model") { dir ->
            val store = TranscriptHistoryStore(dir)
            val noiseTrained = line("שני כדורים בעשר וחצי", starred = true).copy(confidence = 0.82f)
            val ivrit = line("שני כדורים בעשר וחצי", starred = true).copy(confidence = 0.82f)
            val a3Record = TranscriptSessionRecord(
                startedAt = 900.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = "ozen-turbo-hebrew-a3-8bit",
                inputName = null, segments = listOf(noiseTrained),
            )
            store.save(a3Record)
            store.save(
                TranscriptSessionRecord(
                    startedAt = 100.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = "ivrit-large-v3-turbo-8bit",
                    inputName = null, segments = listOf(ivrit),
                ),
            )

            assertEquals(listOf(true, false), store.starredLines().map { it.isUncertain })
            assertTrue(TranscriptHistoryStore.exportText(a3Record, utcOffsetAt = { 0 }, marksUncertain = true).contains("ייתכן שלא נשמע נכון."))
        }
    }

    @Test
    fun `a conversation that switched models judges each saved line by the model that wrote it, an older line by the conversation's`() {
        withHistoryDirectory("ozen-unsure-line") { dir ->
            val store = TranscriptHistoryStore(dir)
            val a3 = "ozen-turbo-hebrew-a3-8bit"
            val before = line("שני כדורים בעשר וחצי", starred = true).copy(
                confidence = 0.82f,
                scoredBy = CaptionConfidence.Scorer(engine = TranscriptionEngineKind.WhisperKit, model = "ivrit-large-v3-turbo-8bit"),
            )
            val unstamped = line("שני כדורים בעשר וחצי", starred = true).copy(confidence = 0.82f)
            val record = TranscriptSessionRecord(
                startedAt = 900.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = a3, inputName = null,
                segments = listOf(before, unstamped),
            )
            store.save(record)

            val loaded = assertNotNull(store.load(record.id))
            assertEquals(listOf(before.scoredBy, null), loaded.segments.map { it.scoredBy })
            assertEquals(listOf(false, true), store.starredLines().map { it.isUncertain })
            val exported = TranscriptHistoryStore.exportText(loaded, utcOffsetAt = { 0 }, marksUncertain = true)
            assertEquals(2, exported.split("ייתכן שלא נשמע נכון.").size)
        }
    }

    @Test
    fun `no stars anywhere gives an empty list`() {
        withHistoryDirectory("ozen-no-stars") { dir ->
            val store = TranscriptHistoryStore(dir)
            store.save(
                TranscriptSessionRecord(
                    startedAt = 1.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
                    segments = listOf(line("שלום", starred = false)),
                ),
            )
            assertTrue(store.starredLines().isEmpty())
        }
    }
}

class TranscriptHistoryStarredExportTest {
    @Test
    fun `starred lines share as dated blocks with times and real names only`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        // 2026-09-14 09:05:00 UTC and 2025-02-28 23:30:00 UTC.
        val september = 1_789_376_700.0
        val february = 1_740_785_400.0
        val lines = listOf(
            StarredLine(first, september, SavedSegment(id = UUID.randomUUID(), text = "כדור בבוקר", speakerName = "ד״ר כהן", speakerClusterID = 0, startTimestamp = september, isCommitted = true, isStarred = true)),
            StarredLine(first, september, SavedSegment(id = UUID.randomUUID(), text = "ושניים בערב", speakerName = "דובר 2", speakerClusterID = 1, startTimestamp = september + 65, isCommitted = true, isStarred = true)),
            StarredLine(second, february, SavedSegment(id = UUID.randomUUID(), text = "התור ביום שלישי", speakerName = null, speakerClusterID = null, startTimestamp = february, isCommitted = true, isStarred = true)),
        )
        val text = TranscriptHistoryStore.exportStarredText(lines)
        assertEquals("14.09.2026\n[09:05:00] ד״ר כהן: כדור בבוקר\n[09:06:05] ושניים בערב\n\n28.02.2025\n[23:30:00] התור ביום שלישי", text)
    }

    @Test
    fun `a starred line the screen marked unsure says so when shared, unless the marks are turned off`() {
        // 2026-09-14 09:05:00 UTC.
        val september = 1_789_376_700.0
        val doubtful = SavedSegment(
            id = UUID.randomUUID(), text = "שני כדורים בעשר וחצי", speakerName = "ד״ר כהן", speakerClusterID = 0,
            startTimestamp = september, isCommitted = true, isStarred = true,
        ).copy(confidence = 0.7f)
        val lines = listOf(StarredLine(UUID.randomUUID(), september, doubtful, engine = TranscriptionEngineKind.WhisperKit))
        val offset: (Double) -> Int = { 0 }
        assertEquals(
            "14.09.2026\n[09:05:00] ייתכן שלא נשמע נכון. ד״ר כהן: שני כדורים בעשר וחצי",
            TranscriptHistoryStore.exportStarredText(lines, utcOffsetAt = offset, marksUncertain = true),
        )
        assertEquals(
            "14.09.2026\n[09:05:00] ד״ר כהן: שני כדורים בעשר וחצי",
            TranscriptHistoryStore.exportStarredText(lines, utcOffsetAt = offset, marksUncertain = false),
        )
    }

    @Test
    fun `two named conversations on one day are headed by their names`() {
        val morning = 1_789_376_700.0
        val line = { session: UUID, title: String?, text: String, at: Double ->
            StarredLine(
                session, at,
                SavedSegment(id = UUID.randomUUID(), text = text, speakerName = null, speakerClusterID = null, startTimestamp = at, isCommitted = true, isStarred = true),
                sessionTitle = title,
            )
        }
        val text = TranscriptHistoryStore.exportStarredText(
            listOf(
                line(UUID.randomUUID(), "אצל הרופא", "כדור בבוקר", morning),
                line(UUID.randomUUID(), "עורך הדין", "לחתום עד חמישי", morning + 3_600),
                line(UUID.randomUUID(), null, "להתקשר לבנק", morning + 7_200),
            ),
        )
        assertEquals(
            "אצל הרופא, 14.09.2026\n[09:05:00] כדור בבוקר\n\nעורך הדין, 14.09.2026\n[10:05:00] לחתום עד חמישי\n\n14.09.2026\n[11:05:00] להתקשר לבנק",
            text,
        )
    }

    @Test
    fun `the date follows the phone's time zone across midnight`() {
        val lateUTC = 1_740_785_400.0 // 2025-02-28 23:30 UTC
        val line = StarredLine(
            UUID.randomUUID(), lateUTC,
            SavedSegment(id = UUID.randomUUID(), text = "א", speakerName = null, speakerClusterID = null, startTimestamp = lateUTC, isCommitted = true, isStarred = true),
        )
        assertEquals("01.03.2025\n[01:30:00] א", TranscriptHistoryStore.exportStarredText(listOf(line), utcOffsetSeconds = 2 * 3_600))
        assertEquals("", TranscriptHistoryStore.exportStarredText(emptyList()))
        val leapDay = 1_709_208_000.0 // 2024-02-29 12:00 UTC
        val leap = StarredLine(
            UUID.randomUUID(), leapDay,
            SavedSegment(id = UUID.randomUUID(), text = "ב", speakerName = null, speakerClusterID = null, startTimestamp = leapDay, isCommitted = true, isStarred = true),
        )
        assertEquals("29.02.2024\n[12:00:00] ב", TranscriptHistoryStore.exportStarredText(listOf(leap)))
    }

    @Test
    fun `a starred line opening with an English word gets a right-to-left mark when shared`() {
        val line = StarredLine(
            UUID.randomUUID(), 0.0,
            SavedSegment(id = UUID.randomUUID(), text = "OK, מחר", speakerName = null, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true, isStarred = true),
        )
        assertEquals("01.01.1970\n‏[00:00:00] OK, מחר", TranscriptHistoryStore.exportStarredText(listOf(line)))
    }

    @Test
    fun `a phone number or star code in shared text stays left to right in a Hebrew line`() {
        val text = "תתקשרי ל 050 123 4567 או *2700"
        val isolated = "תתקשרי ל ⁦050 123 4567⁩ או ⁦*2700⁩"
        val saved = SavedSegment(id = UUID.randomUUID(), text = text, speakerName = null, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true, isStarred = true)
        val session = TranscriptSessionRecord(id = UUID.randomUUID(), startedAt = 0.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null, segments = listOf(saved))
        assertEquals("שיחה מתאריך 01.01.1970\n\n★ [00:00:00] $isolated", TranscriptHistoryStore.exportText(session))

        val line = StarredLine(session.id, 0.0, saved)
        assertEquals("01.01.1970\n[00:00:00] $isolated", TranscriptHistoryStore.exportStarredText(listOf(line)))
    }
}

class TranscriptHistoryTitleTest {
    private fun record(id: UUID, lines: List<String>) = TranscriptSessionRecord(
        id = id, startedAt = 10.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null,
        segments = lines.map { SavedSegment(id = UUID.randomUUID(), text = it, speakerName = null, speakerClusterID = null, startTimestamp = 10.0, isCommitted = true) },
    )

    @Test
    fun `a named conversation shows its name in the list and is found by it`() {
        withHistoryDirectory("ozen-titles") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id, listOf("שלום")))

            store.rename(id, title = "  ביקור אצל הרופא ")
            assertEquals("ביקור אצל הרופא", store.load(id)?.title)
            assertEquals("ביקור אצל הרופא", store.listSummaries().firstOrNull()?.title)
            assertEquals(listOf(id), store.search("רופא").map { it.id })
        }
    }

    @Test
    fun `without the prepared search files (a copy restored from a backup) a conversation is still found by its name, and they are made again`() {
        withHistoryDirectory("ozen-titles") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id, listOf("שלום")))
            store.rename(id, title = "ביקור אצל הרופא")
            val prepared = File(dir, TranscriptHistoryStore.SUMMARIES_FOLDER_NAME)
            prepared.deleteRecursively()

            assertEquals(listOf(id), store.search("רופא").map { it.id })
            assertTrue(store.search("סבתא").isEmpty())
            val remade = prepared.list()?.toList() ?: emptyList()
            assertTrue(remade.any { it.endsWith(".search-v3.txt") })
            assertEquals(listOf(id), store.search("רופא").map { it.id })
        }
    }

    @Test
    fun `autosaving a live conversation keeps the name given to it meanwhile`() {
        withHistoryDirectory("ozen-titles") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id, listOf("שלום")))
            store.rename(id, title = "ארוחת שישי")

            // The live transcript has grown and knows nothing about the name.
            store.save(record(id, listOf("שלום", "מה נשמע")))

            assertEquals("ארוחת שישי", store.load(id)?.title)
            assertEquals(2, store.load(id)?.segments?.size)
            assertEquals("ארוחת שישי", store.listSummaries().firstOrNull()?.title)
            assertEquals(listOf(id), store.search("שישי").map { it.id })
        }
    }

    @Test
    fun `two quick saves in a row, each skipping the caches, keep the name`() {
        withHistoryDirectory("ozen-titles") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id, listOf("שלום")))
            store.rename(id, title = "ביקור אצל הרופא")

            // Leaving the screen, then going to the background: each is a
            // save that waits for the disk and leaves the summary behind.
            store.save(record(id, listOf("שלום", "מה נשמע")), updateSearchCaches = false)
            store.save(record(id, listOf("שלום", "מה נשמע", "בסדר")), updateSearchCaches = false)

            assertEquals("ביקור אצל הרופא", store.load(id)?.title)
            assertEquals(3, store.load(id)?.segments?.size)
            assertEquals("ביקור אצל הרופא", store.listSummaries().firstOrNull()?.title)
        }
    }

    @Test
    fun `an empty name removes it, and a later autosave doesn't bring it back`() {
        withHistoryDirectory("ozen-titles") { dir ->
            val store = TranscriptHistoryStore(dir)
            val id = UUID.randomUUID()
            store.save(record(id, listOf("שלום")))
            store.rename(id, title = "זמני")
            store.rename(id, title = "   ")
            store.save(record(id, listOf("שלום", "עוד")))

            assertNull(store.load(id)?.title)
            assertNull(store.listSummaries().firstOrNull()?.title)
            assertTrue(store.search("זמני").isEmpty())
        }
    }

    @Test
    fun `a conversation saved before names existed loads without one`() {
        val json = """{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","startedAt":1,"engine":"whisperKit","segments":[]}"""
        assertNull(TranscriptSessionRecord.fromJson(json).title)
    }
}

class TranscriptHistoryTitledExportTest {
    @Test
    fun `shared text starts with the conversation's name, if any, and its date`() {
        // 14 September 2026, 07:30 in Israel (UTC+3).
        val startedAt = 1_789_360_200.0
        val line = SavedSegment(id = UUID.randomUUID(), text = "כדור בבוקר", speakerName = null, speakerClusterID = null, startTimestamp = startedAt, isCommitted = true)
        var record = TranscriptSessionRecord(startedAt = startedAt, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null, segments = listOf(line))
        assertEquals("שיחה מתאריך 14.09.2026\n\n[07:30:00] כדור בבוקר", TranscriptHistoryStore.exportText(record, utcOffsetSeconds = 3 * 3_600))
        record = record.copy(title = "ביקור אצל הרופא")
        assertEquals("ביקור אצל הרופא, 14.09.2026\n\n[07:30:00] כדור בבוקר", TranscriptHistoryStore.exportText(record, utcOffsetSeconds = 3 * 3_600))
        record = record.copy(title = "")
        assertEquals("שיחה מתאריך 14.09.2026\n\n[07:30:00] כדור בבוקר", TranscriptHistoryStore.exportText(record, utcOffsetSeconds = 3 * 3_600))
        record = record.copy(segments = emptyList())
        assertEquals("שיחה מתאריך 14.09.2026", TranscriptHistoryStore.exportText(record, utcOffsetSeconds = 3 * 3_600))
    }

    @Test
    fun `a shared line opening with an English word gets a right-to-left mark, Hebrew lines don't`() {
        val english = SavedSegment(id = UUID.randomUUID(), text = "OK, נתראה מחר", speakerName = null, speakerClusterID = null, startTimestamp = 0.0, isCommitted = true)
        val named = SavedSegment(id = UUID.randomUUID(), text = "OK, נתראה מחר", speakerName = "דנה", speakerClusterID = null, startTimestamp = 0.0, isCommitted = true)
        val record = TranscriptSessionRecord(startedAt = 0.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null, segments = listOf(english, named))
        assertEquals("שיחה מתאריך 01.01.1970\n\n‏[00:00:00] OK, נתראה מחר\n[00:00:00] דנה: OK, נתראה מחר", TranscriptHistoryStore.exportText(record))
    }

    @Test
    fun `a Hebrew name opening with an English word gets the mark too, an all-English name doesn't`() {
        var record = TranscriptSessionRecord(startedAt = 0.0, engine = TranscriptionEngineKind.WhisperKit, modelVariant = null, inputName = null, segments = emptyList())
        record = record.copy(title = "WhatsApp מהבנק")
        assertEquals("‏WhatsApp מהבנק, 01.01.1970", TranscriptHistoryStore.exportText(record))
        record = record.copy(title = "Doctor")
        assertEquals("Doctor, 01.01.1970", TranscriptHistoryStore.exportText(record))
    }
}

class ConversationLengthTest {
    @Test
    fun `a conversation cut off when the app closed is as long as its last line, not unknown`() {
        val summary = TranscriptSessionSummary(id = UUID.randomUUID(), startedAt = 1_000.0, endedAt = null, segmentCount = 3, preview = "", engine = TranscriptionEngineKind.WhisperKit, lastLineAt = 1_600.0)
        assertEquals(600.0, summary.durationSeconds)
    }

    @Test
    fun `a closed conversation still ends where it was closed`() {
        val summary = TranscriptSessionSummary(id = UUID.randomUUID(), startedAt = 1_000.0, endedAt = 1_900.0, segmentCount = 3, preview = "", engine = TranscriptionEngineKind.WhisperKit, lastLineAt = 1_600.0)
        assertEquals(900.0, summary.durationSeconds)
    }

    @Test
    fun `with no lines and no end there is still nothing to show`() {
        val summary = TranscriptSessionSummary(id = UUID.randomUUID(), startedAt = 1_000.0, endedAt = null, segmentCount = 0, preview = "", engine = TranscriptionEngineKind.WhisperKit)
        assertNull(summary.durationSeconds)
    }
}

class TranscriptSourceLineTest {
    private fun record(engine: TranscriptionEngineKind, model: String?, input: String? = "iPhone Microphone") =
        TranscriptSessionRecord(startedAt = 0.0, engine = engine, modelVariant = model, inputName = input, segments = emptyList())

    @Test
    fun `names the model as the model screen does, not by its download id`() {
        assertEquals(
            "‏Whisper (במכשיר) · Turbo Hebrew (ivrit.ai) · iPhone Microphone",
            record(TranscriptionEngineKind.WhisperKit, model = "ivrit-large-v3-turbo-8bit").sourceLine(UILanguage.Hebrew),
        )
        assertEquals(
            "Whisper (on device) · some-future-model · iPhone Microphone",
            record(TranscriptionEngineKind.WhisperKit, model = "some-future-model").sourceLine(UILanguage.English),
        )
    }

    @Test
    fun `the home computer's placeholder model, which only repeated the engine in English, is left out`() {
        assertEquals("המחשב בבית · iPhone Microphone", record(TranscriptionEngineKind.HomeServer, model = "home server").sourceLine(UILanguage.Hebrew))
    }
}
