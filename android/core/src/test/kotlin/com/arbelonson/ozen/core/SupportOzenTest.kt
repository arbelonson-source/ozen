package com.arbelonson.ozen.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"

private fun bech32Valid(address: String): Boolean {
    val parts = address.split("1", limit = 2).filter { it.isNotEmpty() }
    if (parts.size != 2) return false
    val values = parts[0].map { it.code shr 5 }.toMutableList()
    values.add(0)
    values += parts[0].map { it.code and 31 }
    for (character in parts[1]) {
        val index = BECH32_CHARSET.indexOf(character)
        if (index < 0) return false
        values.add(index)
    }
    val generator = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    var check = 1
    for (value in values) {
        val top = check shr 25
        check = ((check and 0x1ffffff) shl 5) xor value
        for (bit in 0 until 5) {
            if ((top shr bit) and 1 == 1) check = check xor generator[bit]
        }
    }
    return check == 1
}

class SupportOzenTest {
    @Test
    fun `the Bitcoin address passes its own checksum`() {
        assertTrue(bech32Valid(SupportOzen.bitcoin.address))
        assertTrue(SupportOzen.bitcoin.address.startsWith("bc1q"))
    }

    @Test
    fun `the Ethereum address is 20 bytes of hex`() {
        val address = SupportOzen.ethereum.address
        assertTrue(address.startsWith("0x"))
        assertEquals(42, address.length)
        assertTrue(address.drop(2).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
    }

    @Test
    fun `the Monero address is a 95-character standard address`() {
        val address = SupportOzen.monero.address
        val alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toSet()
        assertEquals(95, address.length)
        assertTrue(address.startsWith("4"))
        assertTrue(address.all { it in alphabet })
    }

    @Test
    fun `QR codes carry the wallet link with the exact address`() {
        assertEquals("bitcoin:bc1qk5aym0mch042200s2wrc366r3hsxxmgc9nu7tm", SupportOzen.bitcoin.paymentURI)
        assertEquals(listOf("bitcoin", "ethereum", "monero"), SupportOzen.addresses.map { it.scheme })
        assertEquals("ethereum:0x0Ea2210fcB0BbF2C3202d9663dB762F1f51b1BBC@1", SupportOzen.ethereum.paymentURI)
        assertEquals(3, SupportOzen.addresses.map { it.id }.toSet().size)
    }
}
