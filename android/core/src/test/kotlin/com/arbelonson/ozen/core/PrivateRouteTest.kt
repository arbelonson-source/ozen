package com.arbelonson.ozen.core

import com.arbelonson.ozen.core.PrivateRoute.Transport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PrivateRouteTest {
    private val wifi = "wifi" to setOf(Transport.Wifi)
    private val cellular = "cell" to setOf(Transport.Cellular)

    @Test
    fun `home Wi-Fi is used for a computer on the home network while mobile data is the default`() {
        assertEquals("wifi", PrivateRoute.choose("192.168.1.20", setOf(Transport.Cellular), listOf(cellular, wifi)))
    }

    @Test
    fun `nothing changes when Wi-Fi or Ethernet is already the default`() {
        assertNull(PrivateRoute.choose("192.168.1.20", setOf(Transport.Wifi), listOf(cellular, wifi)))
        assertNull(PrivateRoute.choose("192.168.1.20", setOf(Transport.Ethernet), listOf(cellular, wifi)))
    }

    @Test
    fun `a VPN default or a VPN Wi-Fi network is left alone`() {
        assertNull(PrivateRoute.choose("192.168.1.20", setOf(Transport.Vpn, Transport.Cellular), listOf(cellular, wifi)))
        assertNull(PrivateRoute.choose("192.168.1.20", setOf(Transport.Cellular), listOf(cellular, "vpn" to setOf(Transport.Wifi, Transport.Vpn))))
    }

    @Test
    fun `public hosts and a missing Wi-Fi use the default`() {
        assertNull(PrivateRoute.choose("pc.example.com", setOf(Transport.Cellular), listOf(cellular, wifi)))
        assertNull(PrivateRoute.choose("192.168.1.20", setOf(Transport.Cellular), listOf(cellular)))
    }

    @Test
    fun `a tailnet host keeps its own route when Wi-Fi is available`() {
        val cell = setOf(Transport.Cellular)
        assertNull(PrivateRoute.choose("pc.tailnet.ts.net", cell, listOf(cellular, wifi)))
        assertNull(PrivateRoute.choose("[PC.Tailnet.TS.NET]", cell, listOf(cellular, wifi)))
        assertNull(PrivateRoute.choose("100.64.0.5", cell, listOf(cellular, wifi)))
        assertNull(PrivateRoute.choose("fd7a:115c:a1e0::1", cell, listOf(cellular, wifi)))
        assertEquals("wifi", PrivateRoute.choose("172.20.0.1", cell, listOf(cellular, wifi)))
    }
}
