package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class CaptionPipelineSettingsTakenUpTest {
    private fun saved(): AppSettings = AppSettings.default.copy().apply {
        soundAlerts = SoundAlertPreferences(minimumImportance = SoundEvent.Importance.Low, mutedIdentifiers = setOf("door"), sensitiveIdentifiers = setOf("door_bell"))
        speakerProfiles = listOf(SpeakerProfile(name = "Dana", embedding = floatArrayOf(1f, 0f, 0f)))
    }

    @Test
    fun `saved settings that come back while captions are paused bring their sound alerts, the faint doorbell included, and their voices at once, and captions stay paused`() = runTest {
        val (pipeline, _, _) = makePipeline()
        pipeline.start(AppSettings.default)
        pipeline.pause()
        val restarts = pipeline.stats.engineRestarts
        val settings = saved()

        pipeline.settingsTakenUp(settings, AppSettings.default)
        assertEquals(settings.soundAlerts, pipeline.soundPolicy.preferences)
        assertTrue(pipeline.soundPolicy.preferences.isSensitive("door_bell"))
        assertTrue(pipeline.speakerClusters.any { it.name == "Dana" })
        assertEquals(PipelinePhase.Paused, pipeline.phase)
        assertEquals(restarts, pipeline.stats.engineRestarts)
    }

    @Test
    fun `saved settings that come back while captions are off bring their sound alerts without starting anything, and a voice already listened for is not added twice`() = runTest {
        val (pipeline, _, _) = makePipeline()
        val settings = saved()
        pipeline.enroll(settings.speakerProfiles[0])

        pipeline.settingsTakenUp(settings, settings.copy().apply { soundAlerts = SoundAlertPreferences() })
        assertEquals(settings.soundAlerts, pipeline.soundPolicy.preferences)
        assertEquals(1, pipeline.speakerClusters.count { it.name == "Dana" })
        assertEquals(PipelinePhase.Idle, pipeline.phase)
        assertFalse(pipeline.isListeningForSoundsOnly)
    }
}
