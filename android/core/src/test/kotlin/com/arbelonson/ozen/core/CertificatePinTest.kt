package com.arbelonson.ozen.core

import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CertificatePinTest {
    private fun certificate(name: String): X509Certificate =
        javaClass.getResourceAsStream("/$name").use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    private val shared = certificate("lan-test-cert.pem")
    private val other = certificate("other-test-cert.pem")

    @Test
    fun `the pin of the shared test certificate is the value the computer and the iPhone expect`() {
        assertEquals("EqUZTsqNgJ8Ol0fkhpLbMwToY-7X9n7_oEzJ2hZACBk", CertificatePin.of(shared))
    }

    @Test
    fun `a chain whose first certificate has the pin is accepted, another certificate or an empty chain is not`() {
        val pin = CertificatePin.of(shared)
        assertTrue(CertificatePin.accepts(arrayOf(shared), pin))
        assertFalse(CertificatePin.accepts(arrayOf(other), pin))
        assertFalse(CertificatePin.accepts(arrayOf(other, shared), pin))
        assertFalse(CertificatePin.accepts(emptyArray(), pin))
        assertFalse(CertificatePin.accepts(null, pin))
    }

    @Test
    fun `the trust manager passes the matching certificate and refuses the rest saying the certificate did not match`() {
        val trust = PinnedTrustManager(CertificatePin.of(shared))
        trust.checkServerTrusted(arrayOf(shared), "ECDHE_ECDSA")
        for (chain in listOf(arrayOf(other), emptyArray<X509Certificate>())) {
            val refused = assertFailsWith<CertificateException> { trust.checkServerTrusted(chain, "ECDHE_ECDSA") }
            assertEquals(PinnedTrustManager.MISMATCH, refused.message)
        }
        assertEquals("certificate did not match", PinnedTrustManager.MISMATCH)
        assertFailsWith<CertificateException> { trust.checkClientTrusted(arrayOf(shared), "ECDHE_ECDSA") }
        assertEquals(0, trust.acceptedIssuers.size)
    }
}
