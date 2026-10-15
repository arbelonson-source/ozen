package com.arbelonson.ozen

import com.arbelonson.ozen.core.HomeServerPairing
import com.arbelonson.ozen.core.TranscriptionEngineKind
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PairingRequests(
    private val settings: SettingsHolder,
    private val saveCode: (String) -> Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    val pending = MutableStateFlow<HomeServerPairing?>(null)
    val linkBroken = MutableStateFlow(false)
    val saveFailed = MutableStateFlow(false)
    var onPaired: (() -> Unit)? = null

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

    suspend fun acceptPending(): Boolean = pending.value?.let { accept(it) } ?: false

    private val oneAtATime = Mutex()

    suspend fun accept(pairing: HomeServerPairing): Boolean {
        pending.value = null
        return withContext(NonCancellable) { oneAtATime.withLock { save(pairing) } }
    }

    private suspend fun save(pairing: HomeServerPairing): Boolean {
        if (!withContext(io) { saveCode(pairing.code) }) {
            saveFailed.value = true
            return false
        }
        settings.change {
            it.homeServerAddress = pairing.address
            it.engine = TranscriptionEngineKind.HomeServer
        }
        onPaired?.invoke()
        return true
    }

    private fun looksLikePairing(link: String): Boolean {
        val lowered = link.trim().lowercase(Locale.ROOT)
        return lowered.startsWith("${HomeServerPairing.SCHEME}://pair") || lowered.startsWith("${HomeServerPairing.SCHEME}:pair")
    }
}
