package com.arbelonson.ozen

import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceRestartTest {
    @Test
    fun aStartFromThePersonOrTheAppAlwaysListens() {
        assertEquals(true, ServiceRestart.shouldListen(systemRestart = false, mayStartCaptions = true))
        assertEquals(true, ServiceRestart.shouldListen(systemRestart = false, mayStartCaptions = false))
    }

    @Test
    fun aRestartByThePhoneListensOnlyWhenTheCrashGuardAllowsIt() {
        assertEquals(true, ServiceRestart.shouldListen(systemRestart = true, mayStartCaptions = true))
        assertEquals(false, ServiceRestart.shouldListen(systemRestart = true, mayStartCaptions = false))
    }

    @Test
    fun aRestartByThePhoneBringsBackSoundsOnlyAsSoundsOnlyEvenWhenTheCrashGuardRefusesCaptions() {
        assertEquals(true, ServiceRestart.shouldListen(systemRestart = true, mayStartCaptions = false, soundsOnly = true))
        assertEquals(true, ServiceRestart.soundsOnlyAfterRestart(systemRestart = true, chosenBeforeKill = true))
        assertEquals(false, ServiceRestart.soundsOnlyAfterRestart(systemRestart = true, chosenBeforeKill = false))
        assertEquals(false, ServiceRestart.soundsOnlyAfterRestart(systemRestart = false, chosenBeforeKill = true))
    }

    @Test
    fun aRefusedStartWithTheMicrophoneAllowedIsNotCoveredByTheScreenSoTheNoticeStillComes() {
        assertEquals(false, ServiceRestart.screenSaysCaptionsAreOff(appIsActive = true, restartRefused = true, microphoneGranted = true))
    }

    @Test
    fun theScreenCoversTheNoticeWhenTheMicrophoneIsGoneOrNothingWasRefused() {
        assertEquals(true, ServiceRestart.screenSaysCaptionsAreOff(appIsActive = true, restartRefused = true, microphoneGranted = false))
        assertEquals(true, ServiceRestart.screenSaysCaptionsAreOff(appIsActive = true, restartRefused = false, microphoneGranted = true))
        assertEquals(false, ServiceRestart.screenSaysCaptionsAreOff(appIsActive = false, restartRefused = true, microphoneGranted = true))
    }
}
