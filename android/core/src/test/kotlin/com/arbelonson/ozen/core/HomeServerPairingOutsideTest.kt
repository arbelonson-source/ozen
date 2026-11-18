package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeServerPairingOutsideTest {
    private fun pairing(address: String) = HomeServerPairing(address, "example-code-123")

    private val home = listOf(
        "wss://192.168.1.20:8766", "ws://192.168.1.20:8765", "wss://10.1.2.3:8766", "wss://172.16.0.5:8766",
        "wss://172.31.255.1:8766", "wss://desktop.local:8766", "wss://desktop.home.arpa", "wss://169.254.3.4:8766",
        "wss://100.64.0.1:8766", "wss://100.64.0.200:8766", "wss://desktop.tail1234.ts.net",
        "wss://[fd7a:115c:a1e0::1]:8766", "ws://desktop:8765",
    )

    private val outside = listOf(
        "wss://203.0.113.9:8766", "wss://8.8.8.8", "wss://172.32.0.1:8766", "wss://172.15.0.1:8766",
        "wss://100.128.0.1:8766", "wss://100.63.0.1:8766", "wss://192.169.1.1:8766", "wss://example.com",
        "wss://captions.example.org:8766/path", "wss://desktop.local.example.com", "wss://010.010.010.010",
        "wss://3405803785", "wss://0xcb007109", "wss://[2001:db8::1]:8766",
    )

    private val refused = listOf("", "   ", "not an address", "ws://example.com", "ws://8.8.8.8:8765", "http://example.com", "wss://")

    @Test
    fun `home and tailscale addresses are not outside the home network`() {
        for (address in home) assertFalse(assertNotNull(pairing(address), address).isOutsideHome, address)
    }

    @Test
    fun `public addresses are outside the home network`() {
        for (address in outside) assertTrue(assertNotNull(pairing(address), address).isOutsideHome, address)
    }

    @Test
    fun `malformed or plain public addresses are refused`() {
        for (address in refused) assertNull(pairing(address), address)
    }

    @Test
    fun `a public link carries its full address in the warning`() {
        val text = assertNotNull(pairing("wss://captions.example.org:8766/path")).confirmationText(UILanguage.English)
        assertTrue(text.contains("wss://captions.example.org:8766/path"))
        assertTrue(text.startsWith("This computer is not on your home network"))
    }

    @Test
    fun `the warning is in Hebrew too`() {
        val text = assertNotNull(pairing("wss://captions.example.org:8766")).confirmationText(UILanguage.Hebrew)
        assertTrue(text.startsWith("המחשב הזה לא ברשת הביתית שלכם"))
        assertTrue(text.contains("wss://captions.example.org:8766"))
    }

    @Test
    fun `a home link keeps the usual text`() {
        val text = assertNotNull(pairing("wss://192.168.1.20:8766")).confirmationText(UILanguage.English)
        assertTrue(text.contains("192.168.1.20"))
        assertEquals(false, text.contains("home network"))
    }
}
