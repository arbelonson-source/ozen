package com.arbelonson.ozen

import androidx.compose.ui.graphics.Color
import com.arbelonson.ozen.core.SpeakerColorSlots

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

    fun of(clusterID: Int?, speakerName: String?, light: Boolean, slot: Int? = null): Color {
        val colours = if (light) deepPalette else palette
        val speaker = SpeakerColorSlots.speaker(clusterID, speakerName)
            ?: return if (light) Color(0.4f, 0.4f, 0.4f) else Color(0.56f, 0.56f, 0.56f)
        return colours[(slot ?: SpeakerColorSlots.aloneSlot(speaker)) % colours.size]
    }
}
