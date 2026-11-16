package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest

private suspend fun FakeAudioCapturer.feed(seconds: Int, level: Float) {
    repeat(seconds * 2) {
        push(FloatArray(8_000) { level })
        delay(500.milliseconds)
    }
}

class CaptionPipelineFallbackSilentMicrophoneTest {
    private suspend fun kotlinx.coroutines.test.TestScope.fallbackPipeline(): Pair<CaptionPipeline, FakeAudioCapturer> {
        val audio = FakeAudioCapturer()
        val engine = FakeEngine()
        engine.pendingDownload = 626
        val pipeline = captionPipeline(
            audio = audio,
            engineFactory = { engine },
            embedder = FakeEmbedder(),
            soundDetector = FakeSoundDetector(),
            recovery = AutoRecoveryPolicy.disabled(),
            network = FakeNetworkMonitor(NetworkConditions.cellular),
        )
        pipeline.networkFirstReportWaitSeconds = 0.3
        pipeline.start(AppSettings.default.also { it.allowCellularModelDownload = false })
        return pipeline to audio
    }

    @Test
    fun `sound listening kept while captions wait warns when the microphone sends only zeros and clears with sound`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        assertTrue(pipeline.isListeningForSoundsOnly)
        assertFalse(pipeline.soundsOnlyChosen)

        audio.feed(20, 0f)
        assertFalse(pipeline.soundAlertsNotWorking, "warned too early")
        audio.feed(20, 0f)
        assertTrue(pipeline.soundAlertsNotWorking)
        assertTrue(pipeline.soundAlertsMicrophoneSilent)
        assertTrue(pipeline.isListeningForSoundsOnly)

        audio.feed(4, 0.01f)
        assertFalse(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `a quiet room on the fallback path never warns`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        audio.feed(5 * 60, 1e-5f)
        assertFalse(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `the warning goes when the fallback listening ends`() = runTest {
        val (pipeline, audio) = fallbackPipeline()
        audio.feed(40, 0f)
        assertTrue(pipeline.soundAlertsNotWorking)
        pipeline.setSoundAlertPreferences(SoundAlertPreferences(isEnabled = false))
        assertFalse(pipeline.soundAlertsNotWorking)
    }

    @Test
    fun `a delivered generic notice is revised quietly when the reason becomes a silent microphone`() {
        val notice = StoppedCaptionsNotice()
        val generic = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        val silent = StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent
        assertIs<StoppedCaptionsNotice.Update.Schedule>(notice.update(generic, appIsActive = false, isEnabled = true, now = 0.0))
        assertNull(notice.update(generic, appIsActive = false, isEnabled = true, now = 30.0))
        val revised = notice.update(silent, appIsActive = false, isEnabled = true, now = 100.0)
        assertEquals(StoppedCaptionsNotice.Update.Revise(StoppedCaptionsNotice.content(silent)), revised)
        assertNull(notice.update(silent, appIsActive = false, isEnabled = true, now = 110.0))
    }

    @Test
    fun `a generic notice not yet delivered is still rescheduled with the new reason`() {
        val notice = StoppedCaptionsNotice()
        val generic = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        val silent = StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent
        notice.update(generic, appIsActive = false, isEnabled = true, now = 0.0)
        assertIs<StoppedCaptionsNotice.Update.Replace>(notice.update(silent, appIsActive = false, isEnabled = true, now = 10.0))
    }

    @Test
    fun `a delivered notice kept through a recovery is revised to the silent reason when it fails again`() {
        val notice = StoppedCaptionsNotice()
        val generic = StoppedCaptionsNotice.Cause.SoundAlertsNotWorking
        val silent = StoppedCaptionsNotice.Cause.SoundAlertsMicrophoneSilent
        notice.update(generic, appIsActive = false, isEnabled = true, now = 0.0)
        assertNull(notice.update(null, appIsActive = false, isEnabled = true, now = 100.0))
        val revised = notice.update(silent, appIsActive = false, isEnabled = true, now = 120.0)
        assertEquals(StoppedCaptionsNotice.Update.Revise(StoppedCaptionsNotice.content(silent)), revised)
    }
}
