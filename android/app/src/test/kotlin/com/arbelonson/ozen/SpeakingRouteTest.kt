package com.arbelonson.ozen

import android.media.AudioDeviceInfo
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.SpeakingOutput
import com.arbelonson.ozen.core.SpeakingRoute
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpeakingRouteTest {
    private fun out(kind: SpeakingOutput.Kind, name: String = "") = SpeakingOutput(kind, name)

    @Test
    fun `the phone's own speaker, an earpiece, or no output says nothing`() = Localization.withLanguage(UILanguage.English) {
        assertNull(SpeakingRoute.notice(listOf(out(SpeakingOutput.Kind.PhoneSpeaker, "Speaker"))))
        assertNull(SpeakingRoute.notice(listOf(out(SpeakingOutput.Kind.Earpiece))))
        assertNull(SpeakingRoute.notice(emptyList()))
    }

    @Test
    fun `a headset, hearing aid, wired, car or other device is named`() = Localization.withLanguage(UILanguage.English) {
        listOf(SpeakingOutput.Kind.Bluetooth, SpeakingOutput.Kind.HearingAid, SpeakingOutput.Kind.Wired, SpeakingOutput.Kind.Car, SpeakingOutput.Kind.Other).forEach {
            assertEquals("Replies are playing through Sam’s buds, so the other person may not hear them.", SpeakingRoute.notice(listOf(out(it, "Sam’s buds"))))
        }
    }

    @Test
    fun `with no name the notice says headphones`() = Localization.withLanguage(UILanguage.English) {
        val headphones = "Replies are playing through headphones, so the other person may not hear them."
        assertEquals(headphones, SpeakingRoute.notice(listOf(out(SpeakingOutput.Kind.Bluetooth, "  "))))
        assertEquals(headphones, SpeakingRoute.notice(listOf(out(SpeakingOutput.Kind.Wired))))
    }

    @Test
    fun `when the phone's speaker plays too nothing is said`() = Localization.withLanguage(UILanguage.English) {
        assertNull(SpeakingRoute.notice(listOf(out(SpeakingOutput.Kind.Bluetooth, "Buds"), out(SpeakingOutput.Kind.PhoneSpeaker))))
    }

    @Test
    fun `the button says it uses the phone's speaker`() = Localization.withLanguage(UILanguage.English) {
        assertEquals("Use the phone’s speaker", SpeakingRoute.speakerButton())
    }

    @Test
    fun `connected devices decide where media goes, a hearing aid by its name`() = Localization.withLanguage(UILanguage.English) {
        val speakerOnly = SpeakerChoice.outputsFor(emptyList())
        assertNull(SpeakingRoute.notice(speakerOnly))
        val aid = SpeakerChoice.outputsFor(listOf(SpeakerChoice.Device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Phonak Audéo")))
        assertEquals(listOf(SpeakingOutput.Kind.HearingAid), aid.map { it.kind })
        val wired = SpeakerChoice.outputsFor(listOf(SpeakerChoice.Device(AudioDeviceInfo.TYPE_WIRED_HEADSET, "")))
        assertEquals(listOf(SpeakingOutput.Kind.Wired), wired.map { it.kind })
        val dock = SpeakerChoice.outputsFor(listOf(SpeakerChoice.Device(AudioDeviceInfo.TYPE_DOCK, "Desk dock")))
        assertEquals(listOf(SpeakingOutput.Kind.Other), dock.map { it.kind })
        val internal = SpeakerChoice.outputsFor(listOf(SpeakerChoice.Device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, ""), SpeakerChoice.Device(AudioDeviceInfo.TYPE_FM, "FM"), SpeakerChoice.Device(AudioDeviceInfo.TYPE_REMOTE_SUBMIX, ""), SpeakerChoice.Device(9999, "")))
        assertNull(SpeakingRoute.notice(internal))
        val aux = SpeakerChoice.outputsFor(listOf(SpeakerChoice.Device(AudioDeviceInfo.TYPE_AUX_LINE, "Aux")))
        assertEquals(listOf(SpeakingOutput.Kind.Other), aux.map { it.kind })
        val earpieceOnly = SpeakerChoice.outputsFor(listOf(SpeakerChoice.Device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, "")))
        assertNull(SpeakingRoute.notice(earpieceOnly))
    }

    @Test
    fun `a device coming or going ends the speaker choice, and the same devices keep it`() {
        SpeakerChoice.chosen = false
        SpeakerChoice.observe(setOf(1, 2))
        SpeakerChoice.chosen = true
        SpeakerChoice.observe(setOf(1, 2))
        assertEquals(true, SpeakerChoice.chosen)
        SpeakerChoice.observe(setOf(1, 2, 3))
        assertEquals(false, SpeakerChoice.chosen)
    }

    @Test
    fun `devices read before a newer look cannot end a speaker choice made after it`() {
        SpeakerChoice.chosen = false
        SpeakerChoice.observe(setOf(1, 2))
        val older = SpeakerChoice.sample()
        SpeakerChoice.observe(setOf(1))
        SpeakerChoice.chosen = true
        SpeakerChoice.observe(setOf(1, 2), older)
        assertEquals(true, SpeakerChoice.chosen)
        SpeakerChoice.observe(setOf(1, 2))
        assertEquals(false, SpeakerChoice.chosen)
    }
}
