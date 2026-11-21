package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OnboardingPlanTest {
    private val withoutModelPage = OnboardingPage.entries - OnboardingPage.Model

    @Test
    fun theModelPageIsOnlyForTheEngineOnThePhone() {
        assertEquals(OnboardingPage.entries, OnboardingPlan.pages(TranscriptionEngineKind.WhisperKit))
        for (engine in listOf(TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.AppleSpeech, TranscriptionEngineKind.Cloud)) {
            assertEquals(withoutModelPage, OnboardingPlan.pages(engine), "pages for $engine")
        }
    }

    @Test
    fun theLanguageAndNamePagesKeepTheirPlaceBeforeAndAfterTheModelPage() {
        val phone = OnboardingPlan.pages(TranscriptionEngineKind.WhisperKit)
        assertEquals(2, phone.indexOf(OnboardingPage.Language))
        assertEquals(5, phone.indexOf(OnboardingPage.Name))
        val home = OnboardingPlan.pages(TranscriptionEngineKind.HomeServer)
        assertEquals(2, home.indexOf(OnboardingPage.Language))
        assertEquals(4, home.indexOf(OnboardingPage.Name))
        assertEquals(6, home.size)
    }

    @Test
    fun thePhoneSpeedTestRunsOnlyWhenAModelOnThePhoneIsUsed() {
        assertTrue(OnboardingPlan.runsPhoneTest(TranscriptionEngineKind.WhisperKit))
        for (engine in listOf(TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.AppleSpeech, TranscriptionEngineKind.Cloud)) {
            assertFalse(OnboardingPlan.runsPhoneTest(engine), "test for $engine")
        }
    }

    @Test
    fun theDownloadNoteIsTrueOnlyWhenTheModelWillBeDownloaded() {
        val file = "ggml-small.bin"
        assertTrue(OnboardingPlan.showsDownloadNote(TranscriptionEngineKind.WhisperKit, file, emptySet()))
        assertFalse(OnboardingPlan.showsDownloadNote(TranscriptionEngineKind.WhisperKit, file, setOf(file)))
        assertTrue(OnboardingPlan.showsDownloadNote(TranscriptionEngineKind.WhisperKit, file, setOf("other.bin")))
        assertFalse(OnboardingPlan.showsDownloadNote(TranscriptionEngineKind.HomeServer, file, emptySet()))
    }
}
