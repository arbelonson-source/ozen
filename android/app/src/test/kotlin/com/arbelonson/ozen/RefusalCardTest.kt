package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class RefusalCardTest {
    @Test
    fun aRefusalWhileOzenIsInFrontShowsTheCardAndItStaysUntilCaptionsStartEvenIfSheLeavesAndComesBack() {
        val card = RefusalCard()
        card.refused(appIsActive = true, microphoneGranted = true)
        assertEquals(true, card.shown)
        card.captionsStarted()
        assertEquals(false, card.shown)
    }

    @Test
    fun aRefusalWhileAwayOrWithTheMicrophoneGoneShowsNoCard() {
        val away = RefusalCard()
        away.refused(appIsActive = false, microphoneGranted = true)
        assertEquals(false, away.shown)
        val silent = RefusalCard()
        silent.refused(appIsActive = true, microphoneGranted = false)
        assertEquals(false, silent.shown)
    }
}
