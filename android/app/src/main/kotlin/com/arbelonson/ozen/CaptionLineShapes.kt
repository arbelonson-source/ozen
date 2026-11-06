package com.arbelonson.ozen

import com.arbelonson.ozen.core.CaptionConfidence
import com.arbelonson.ozen.core.CaptionLayout
import com.arbelonson.ozen.core.PhoneNumbers
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.util.UUID

internal class CaptionLineShapes {
    data class Shape(
        val text: String,
        val copied: String,
        val phones: List<PhoneNumbers.Match>,
        val unsure: Boolean,
        val rightToLeft: Boolean,
    )

    private data class Key(
        val segment: TranscriptSegment,
        val languageCode: String,
        val scoringEngine: TranscriptionEngineKind,
        val scoringModel: String?,
    )

    private var kept = HashMap<UUID, Pair<Key, Shape>>()

    var made = 0
        private set

    fun shape(
        segments: List<TranscriptSegment>,
        languageCode: String,
        scoringEngine: TranscriptionEngineKind,
        scoringModel: String?,
    ): List<Shape> {
        val next = HashMap<UUID, Pair<Key, Shape>>(segments.size * 2)
        val shapes = segments.map { segment ->
            val key = Key(segment, languageCode, scoringEngine, scoringModel)
            val entry = kept[segment.id]?.takeIf { it.first == key } ?: (key to make(key))
            next[segment.id] = entry
            entry.second
        }
        kept = next
        return shapes
    }

    fun clear() {
        kept = HashMap()
    }

    private fun make(key: Key): Shape {
        made++
        val text = key.segment.text
        return Shape(
            text = CaptionLayout.displayText(text, key.languageCode),
            copied = CaptionLayout.copiedText(text),
            phones = if (key.segment.isSettled) PhoneNumbers.matches(text) else emptyList(),
            unsure = CaptionConfidence.isUncertain(key.segment, key.scoringEngine, key.scoringModel),
            rightToLeft = CaptionLayout.readsRightToLeft(text, key.languageCode),
        )
    }
}
