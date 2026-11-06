package com.arbelonson.ozen.core

import java.security.MessageDigest
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.X509TrustManager

object CertificatePin {
    val shape = Regex("[A-Za-z0-9_-]{43}")

    fun of(der: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(der))

    fun of(certificate: Certificate): String = of(certificate.encoded)

    fun accepts(chain: Array<out Certificate>?, pin: String): Boolean {
        val leaf = chain?.firstOrNull() ?: return false
        return MessageDigest.isEqual(of(leaf).toByteArray(Charsets.US_ASCII), pin.toByteArray(Charsets.US_ASCII))
    }
}

class PinnedTrustManager(private val pin: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (!CertificatePin.accepts(chain, pin)) throw CertificateException(MISMATCH)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("this connection takes no client certificates")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        const val MISMATCH = "certificate did not match"
    }
}
