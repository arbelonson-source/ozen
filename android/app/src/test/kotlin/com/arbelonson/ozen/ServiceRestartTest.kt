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
}
