package com.arbelonson.ozen

import com.arbelonson.ozen.core.EngineUnavailability
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.NetworkMonitoring
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import kotlin.test.Test
import kotlin.test.assertEquals

class MobileDataNoteTest {
    private class FakeNetwork(override var current: NetworkConditions?) : NetworkMonitoring {
        override var onChange: ((NetworkConditions) -> Unit)? = null
    }

    private val plain = "Tap to pause"
    private val metered = "Over mobile data · Tap to pause"

    private fun detail(
        engine: TranscriptionEngineKind,
        network: NetworkMonitoring,
        covering: Boolean = false,
        covered: TranscriptionEngineKind? = null,
    ): String? {
        CaptionState.show(
            PipelinePhase.Listening, engine = engine, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(),
            coveringForCloud = covering, coveredEngine = covered,
            coverReason = if (covering) EngineUnavailability.Kind.HomeServerUnreachable else null,
            network = network.current,
        )
        return CaptionState.screen.value.status.detail
    }

    @Test
    fun `the home computer and the cloud say so over mobile data and follow the network both ways`() = Localization.withLanguage(UILanguage.English) {
        for (engine in listOf(TranscriptionEngineKind.HomeServer, TranscriptionEngineKind.Cloud)) {
            CaptionState.clear()
            val network = FakeNetwork(NetworkConditions.wifi)
            assertEquals(plain, detail(engine, network), "$engine on Wi-Fi")
            network.current = NetworkConditions.cellular
            assertEquals(metered, detail(engine, network), "$engine on mobile data")
            network.current = NetworkConditions.wifi
            assertEquals(plain, detail(engine, network), "$engine back on Wi-Fi")
        }
        CaptionState.clear()
    }

    @Test
    fun `the phone's own model never mentions mobile data`() = Localization.withLanguage(UILanguage.English) {
        CaptionState.clear()
        val network = FakeNetwork(NetworkConditions.cellular)
        assertEquals(plain, detail(TranscriptionEngineKind.WhisperKit, network))
        CaptionState.clear()
    }

    @Test
    fun `the phone model covering for the home computer keeps its own line on mobile data`() = Localization.withLanguage(UILanguage.English) {
        CaptionState.clear()
        val network = FakeNetwork(NetworkConditions.cellular)
        val shown = detail(TranscriptionEngineKind.WhisperKit, network, covering = true, covered = TranscriptionEngineKind.HomeServer)
        assertEquals("Can’t reach the home computer, so the phone carries on by itself · Tap to pause", shown)
        CaptionState.clear()
    }

    @Test
    fun `an unknown network counts as not metered`() = Localization.withLanguage(UILanguage.English) {
        CaptionState.clear()
        assertEquals(plain, detail(TranscriptionEngineKind.HomeServer, FakeNetwork(null)))
        CaptionState.clear()
    }
}
