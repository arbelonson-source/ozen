package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoppedCaptionsNoticeAddressTest {
    private val homeAddress = "192.168.1.20"
    private val farAddress = "wss://grandma-pc.example.net"

    private fun unreachable() = PipelineFailure(
        PipelineFailure.Kind.EngineUnavailable, "", EngineUnavailability(EngineUnavailability.Kind.HomeServerUnreachable, ""),
    )

    private fun cause(address: String?) = StoppedCaptionsNotice.cause(
        phase = PipelinePhase.Failed(unreachable()),
        retryScheduled = false,
        systemInterrupted = false,
        callEndedDuringInterruption = false,
        homeServerAddress = address,
    )

    private fun body(address: String?) = StoppedCaptionsNotice.content(cause(address)!!).body

    @Test
    fun `a computer that does not answer is told the way the screen tells it, by its address`() = Localization.withLanguage(UILanguage.English) {
        assertEquals(HomeServer.unreachableText(homeAddress), body(homeAddress))
        assertEquals(HomeServer.unreachableText(farAddress), body(farAddress))
        assertTrue(body(homeAddress).contains("works only on the home Wi‑Fi"))
        assertTrue(!body(farAddress).contains("works only on the home Wi‑Fi"))
    }

    @Test
    fun `the address is kept only for a computer that did not answer`() {
        val glitch = PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "stream ended")
        val other = StoppedCaptionsNotice.cause(
            phase = PipelinePhase.Failed(glitch),
            retryScheduled = false,
            systemInterrupted = false,
            callEndedDuringInterruption = false,
            homeServerAddress = homeAddress,
        )
        assertEquals(StoppedCaptionsNotice.Cause.Failed(glitch), other)
    }

    @Test
    fun `the same failure at the same address is told once, another address brings the words up to date`() {
        val notice = StoppedCaptionsNotice()
        val posted = notice.update(cause(homeAddress), appIsActive = false, isEnabled = true)
        assertIs<StoppedCaptionsNotice.Update.Post>(posted)
        assertNull(notice.update(cause(homeAddress), appIsActive = false, isEnabled = true))
        val moved = notice.update(cause(farAddress), appIsActive = false, isEnabled = true)
        assertIs<StoppedCaptionsNotice.Update.Revise>(moved)
        assertNotEquals(posted.content.body, moved.content.body)
        assertNull(notice.update(cause(farAddress), appIsActive = false, isEnabled = true))
    }
}
