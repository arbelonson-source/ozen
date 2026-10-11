package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import com.arbelonson.ozen.core.TranscriptSessionSummary

internal object SpeakerColour {
    private val palette = listOf(
        Color(0.98f, 0.75f, 0.18f),
        Color(0.45f, 0.78f, 0.98f),
        Color(0.95f, 0.55f, 0.60f),
        Color(0.55f, 0.85f, 0.55f),
        Color(0.80f, 0.65f, 0.98f),
    )

    private val deepPalette = listOf(
        Color(0.55f, 0.36f, 0.0f),
        Color(0.0f, 0.36f, 0.62f),
        Color(0.70f, 0.15f, 0.25f),
        Color(0.10f, 0.45f, 0.15f),
        Color(0.42f, 0.25f, 0.70f),
    )

    fun of(clusterID: Int?, speakerName: String?, light: Boolean): Color {
        val colours = if (light) deepPalette else palette
        if (speakerName != null && !TranscriptSessionSummary.isGenericLabel(speakerName)) return colours[stableIndex(speakerName, colours.size)]
        if (clusterID == null) return if (light) Color(0.4f, 0.4f, 0.4f) else Color(0.56f, 0.56f, 0.56f)
        return colours[clusterID % colours.size]
    }

    private fun stableIndex(name: String, count: Int): Int {
        var hash = -3_750_763_034_362_895_579L
        for (byte in name.toByteArray(Charsets.UTF_8)) {
            hash = (hash xor (byte.toLong() and 0xFF)) * 1_099_511_628_211L
        }
        return java.lang.Long.remainderUnsigned(hash, count.toLong()).toInt()
    }
}
