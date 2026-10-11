package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class YamnetSoundsTest {
    @Test
    fun `every sound Ozen alerts for has labels of its own in the model`() {
        assertEquals(SoundEventCatalog.identifiers, YamnetSounds.labels.keys)
        val names = YamnetSounds.labels.values.flatten()
        assertEquals(names.size, names.toSet().size, "a label stands for two sounds")
        assertTrue(YamnetSounds.labels.values.all { it.isNotEmpty() })
    }

    @Test
    fun `an explosion is not taken for artillery fire, since thunder and opening cans were heard as one`() {
        assertFalse("Explosion" in YamnetSounds.labels.values.flatten())
        assertEquals(listOf("Artillery fire"), YamnetSounds.labels["artillery_fire"])
    }

    @Test
    fun `a sound is as likely as the strongest of its labels, and sounds Ozen ignores are left out`() {
        val names = listOf("Speech", "Smoke detector, smoke alarm", "Fire alarm", "Doorbell")
        val indices = YamnetSounds.indices(names)
        val candidates = YamnetSounds.candidates(floatArrayOf(0.95f, 0.2f, 0.7f, 0.1f), indices).toMap()
        assertEquals(setOf("smoke_detector", "door_bell"), candidates.keys)
        assertEquals(0.7, candidates.getValue("smoke_detector"), 1e-6)
        assertEquals(0.1, candidates.getValue("door_bell"), 1e-6)
    }

    @Test
    fun `a label the model lacks is skipped, and a sound with none of its labels is not known`() {
        val indices = YamnetSounds.indices(listOf("Music", "Fire alarm", "Bark"))
        assertEquals(listOf(1), indices["smoke_detector"])
        assertEquals(listOf(2), indices["dog_bark"])
        assertFalse("door_bell" in indices)
    }

    @Test
    fun `windows are the model's length and start half a window apart, across the edges of the chunks`() {
        val windows = SoundWindows()
        val heard = mutableListOf<FloatArray>()
        val total = 40_000
        for (start in 0 until total step 1_000) {
            heard += windows.add(FloatArray(minOf(1_000, total - start)) { (start + it).toFloat() })
        }
        assertEquals(listOf(0f, 7_800f, 15_600f, 23_400f), heard.map { it.first() })
        for (window in heard) {
            assertEquals(YamnetSounds.SAMPLES_PER_WINDOW, window.size)
            assertEquals(window.first() + window.size - 1, window.last())
        }
    }

    @Test
    fun `less than a window of sound gives none yet`() {
        val windows = SoundWindows()
        assertTrue(windows.add(FloatArray(YamnetSounds.SAMPLES_PER_WINDOW - 1)).isEmpty())
        assertEquals(1, windows.add(FloatArray(1)).size)
        assertTrue(windows.add(FloatArray(YamnetSounds.SAMPLES_BETWEEN_WINDOWS - 1)).isEmpty())
        assertEquals(1, windows.add(FloatArray(1)).size)
    }
}
