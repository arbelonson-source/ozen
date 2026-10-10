package com.arbelonson.ozen

import com.arbelonson.ozen.core.HomeServerPairing
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

class PairingRequests(private val settings: SettingsHolder, private val saveCode: (String) -> Boolean) {
    val pending = MutableStateFlow<HomeServerPairing?>(null)
    val linkBroken = MutableStateFlow(false)
    val saveFailed = MutableStateFlow(false)

    fun open(link: String) {
        if (pending.value != null) return
        val url = try {
            URI(link)
        } catch (_: URISyntaxException) {
            null
        }
        val pairing = url?.let { HomeServerPairing.from(it) }
        pending.value = pairing
        linkBroken.value = pairing == null && if (url != null) HomeServerPairing.isPairingLink(url) else looksLikePairing(link)
    }

    fun acceptPending(): Boolean = pending.value?.let(::accept) ?: false

    fun accept(pairing: HomeServerPairing): Boolean {
        pending.value = null
        if (!saveCode(pairing.code)) {
            saveFailed.value = true
            return false
        }
        settings.change {
            it.homeServerAddress = pairing.address
            it.engine = TranscriptionEngineKind.HomeServer
        }
        return true
    }

    private fun looksLikePairing(link: String): Boolean {
        val lowered = link.trim().lowercase(Locale.ROOT)
        return lowered.startsWith("${HomeServerPairing.SCHEME}://pair") || lowered.startsWith("${HomeServerPairing.SCHEME}:pair")
    }
}
