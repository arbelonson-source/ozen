package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbelonson.ozen.core.NetworkConditions
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.TranscriptionEngineKind
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MobileDataNoteDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @After
    fun clear() {
        compose.runOnUiThread { CaptionState.clear() }
    }

    private fun listeningOn(engine: TranscriptionEngineKind, network: NetworkConditions) {
        compose.runOnUiThread {
            CaptionState.show(
                PipelinePhase.Listening, engine = engine, interruptedBySystem = false, scheduledRetry = null,
                segments = emptyList(), network = network,
            )
        }
    }

    private fun waitForStatus(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theStatusLineSaysOverMobileDataForTheHomeComputerOnAMeteredNetworkAndDropsItOnWifi() {
        listeningOn(TranscriptionEngineKind.HomeServer, NetworkConditions.cellular)
        waitForStatus("Over mobile data")
        compose.onNodeWithText("Over mobile data", substring = true).assertExists()
        listeningOn(TranscriptionEngineKind.HomeServer, NetworkConditions.wifi)
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Over mobile data", substring = true).fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Pause captions").assertExists()
    }

    @Test
    fun thePhonesOwnModelOnAMeteredNetworkDoesNotMentionMobileData() {
        listeningOn(TranscriptionEngineKind.WhisperKit, NetworkConditions.cellular)
        waitForStatus("Pause captions")
        compose.onNodeWithText("Over mobile data", substring = true).assertDoesNotExist()
    }
}
