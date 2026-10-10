package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class AudioRoutePolicyTest {
    private val builtIn = AudioInputDescriptor("builtin", "iPhone Microphone", AudioPortType.BuiltInMic)
    private val airpods = AudioInputDescriptor("airpods-123", "Arbel's AirPods", AudioPortType.Bluetooth)
    private val lavalier = AudioInputDescriptor("usb-lav-1", "USB-C Lavalier", AudioPortType.Usb)

    @Test
    fun `the user's preferred input is selected when it's present`() {
        val selection = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, airpods, lavalier),
            preferredUID = airpods.uid,
            currentUID = builtIn.uid,
        )
        assertEquals(airpods.uid, selection)
    }

    @Test
    fun `a preferred input that reconnects is picked back up automatically (e g AirPods coming back in range)`() {
        val disappeared = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn),
            preferredUID = airpods.uid,
            currentUID = builtIn.uid,
        )
        assertEquals(builtIn.uid, disappeared)

        val reappeared = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, airpods),
            preferredUID = airpods.uid,
            currentUID = builtIn.uid,
        )
        assertEquals(airpods.uid, reappeared)
    }

    @Test
    fun `with no preference, the currently active input is kept rather than switched arbitrarily`() {
        val selection = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, lavalier),
            preferredUID = null,
            currentUID = lavalier.uid,
        )
        assertEquals(lavalier.uid, selection)
    }

    @Test
    fun `a Bluetooth headset nobody picked doesn't take the recording over from the phone's own microphone`() {
        val connected = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, airpods),
            preferredUID = null,
            currentUID = airpods.uid,
        )
        assertEquals(builtIn.uid, connected)

        val besideWiredMic = AudioRoutePolicy.resolveSelection(
            available = listOf(airpods, lavalier),
            preferredUID = null,
            currentUID = airpods.uid,
        )
        assertEquals(lavalier.uid, besideWiredMic)
    }

    @Test
    fun `a headset or hearing aid nobody picked, taking over from a microphone near the talker, gives the recording back to that microphone`() {
        val roger = AudioInputDescriptor("roger-1", "Roger On", AudioPortType.RemoteMic)
        val hearingAid = AudioInputDescriptor("hearing-aid", "Phonak Audéo", AudioPortType.HearingAid)
        val fromLavalier = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, airpods, lavalier),
            preferredUID = null,
            currentUID = airpods.uid,
            previousUID = lavalier.uid,
        )
        assertEquals(lavalier.uid, fromLavalier)
        val fromRoger = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, roger, hearingAid),
            preferredUID = null,
            currentUID = hearingAid.uid,
            previousUID = roger.uid,
        )
        assertEquals(roger.uid, fromRoger)
        assertEquals(
            builtIn.uid,
            AudioRoutePolicy.resolveSelection(listOf(builtIn, airpods), preferredUID = null, currentUID = airpods.uid, previousUID = lavalier.uid),
        )
        assertEquals(
            builtIn.uid,
            AudioRoutePolicy.resolveSelection(listOf(builtIn, airpods, hearingAid), preferredUID = null, currentUID = airpods.uid, previousUID = hearingAid.uid),
        )
    }

    @Test
    fun `a hearing aid nobody chose gives way to the phone's microphone, like a headset, chosen, it stays`() {
        val hearingAid = AudioInputDescriptor("hearing-aid", "Phonak Audéo", AudioPortType.HearingAid)
        val unchosen = AudioRoutePolicy.resolveSelection(listOf(builtIn, hearingAid), preferredUID = null, currentUID = hearingAid.uid)
        assertEquals(builtIn.uid, unchosen)
        val chosen = AudioRoutePolicy.resolveSelection(listOf(builtIn, hearingAid), preferredUID = hearingAid.uid, currentUID = builtIn.uid)
        assertEquals(hearingAid.uid, chosen)
        val alone = AudioRoutePolicy.resolveSelection(listOf(hearingAid), preferredUID = null, currentUID = hearingAid.uid)
        assertEquals(hearingAid.uid, alone)
    }

    @Test
    fun `a headset that is the only microphone here is still used`() {
        val selection = AudioRoutePolicy.resolveSelection(
            available = listOf(airpods),
            preferredUID = null,
            currentUID = airpods.uid,
        )
        assertEquals(airpods.uid, selection)
    }

    @Test
    fun `with no preference and no valid current input, falls back to the first available input`() {
        val selection = AudioRoutePolicy.resolveSelection(
            available = listOf(lavalier, airpods),
            preferredUID = null,
            currentUID = null,
        )
        assertEquals(lavalier.uid, selection)
    }

    @Test
    fun `with nothing available at all, resolves to nil instead of a fake selection`() {
        val selection = AudioRoutePolicy.resolveSelection(
            available = emptyList(),
            preferredUID = airpods.uid,
            currentUID = airpods.uid,
        )
        assertNull(selection)
    }

    @Test
    fun `a stale current input that's no longer available falls through to first available`() {
        val selection = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn),
            preferredUID = null,
            currentUID = lavalier.uid,
        )
        assertEquals(builtIn.uid, selection)
    }

    @Test
    fun `a remote assistive microphone (Phonak Roger) is not an ear-worn device, and isn't given way to like one`() {
        assertFalse(AudioPortType.RemoteMic.isOnTheListenersEar)

        val rogerMic = AudioInputDescriptor("roger-1", "Roger Table Mic", AudioPortType.RemoteMic)
        val chosenBySystem = AudioRoutePolicy.resolveSelection(
            available = listOf(builtIn, rogerMic),
            preferredUID = null,
            currentUID = rogerMic.uid,
        )
        assertEquals(rogerMic.uid, chosenBySystem)
    }

    @Test
    fun `port types keep the raw names the saved settings use`() {
        assertEquals(
            listOf("builtInMic", "bluetooth", "wired", "usb", "hearingAid", "remoteMic", "other"),
            AudioPortType.entries.map { it.rawValue },
        )
        assertEquals(AudioPortType.HearingAid, AudioPortType.fromRawValue("hearingAid"))
        assertNull(AudioPortType.fromRawValue("nope"))
    }
}
