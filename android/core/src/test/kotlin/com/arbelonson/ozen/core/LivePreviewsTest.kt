package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LivePreviewsTest {
    private val nobodyLooking = PreviewDemand(appInFront = false, lockScreenCaptions = false, alertWordsSet = false)

    @Test
    fun the_phone_model_skips_previews_only_when_nobody_can_see_or_hear_the_live_line() {
        assertFalse(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, nobodyLooking))
    }

    @Test
    fun the_app_in_front_keeps_previews() {
        assertTrue(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, nobodyLooking.copy(appInFront = true)))
    }

    @Test
    fun captions_on_the_lock_screen_keep_previews() {
        assertTrue(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, nobodyLooking.copy(lockScreenCaptions = true)))
    }

    @Test
    fun an_alert_word_keeps_previews() {
        assertTrue(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, nobodyLooking.copy(alertWordsSet = true)))
    }

    @Test
    fun every_other_engine_keeps_previews_whatever_the_phone_is_doing() {
        TranscriptionEngineKind.entries.filter { it != TranscriptionEngineKind.WhisperKit }.forEach {
            assertTrue(LivePreviews.wanted(it, nobodyLooking), "$it")
        }
    }

    @Test
    fun the_default_demand_is_the_app_in_front() {
        assertTrue(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, PreviewDemand()))
    }

    @Test
    fun captions_on_the_lock_screen_do_not_keep_previews_while_the_screen_is_off() {
        val demand = nobodyLooking.copy(lockScreenCaptions = true, screenOn = false)
        assertFalse(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, demand))
    }

    @Test
    fun captions_on_the_lock_screen_keep_previews_while_the_screen_is_on() {
        val demand = nobodyLooking.copy(lockScreenCaptions = true, screenOn = true)
        assertTrue(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, demand))
    }

    @Test
    fun an_alert_word_keeps_previews_with_the_screen_off() {
        val demand = nobodyLooking.copy(alertWordsSet = true, screenOn = false)
        assertTrue(LivePreviews.wanted(TranscriptionEngineKind.WhisperKit, demand))
    }
}
