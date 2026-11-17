package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AwayAlertsNotice
import com.arbelonson.ozen.core.DisplayPreferences.Theme
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.TranscriptSessionSummary
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ScreenReaderCardsDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val readerBefore = ScreenReader.isOn
    private val announceBefore = ScreenReader.announce
    private val announced = CopyOnWriteArrayList<String>()
    private val look = CaptionTheme.of(Theme.Dark, systemDark = true)

    @Before
    fun aScreenReaderThatWritesDownWhatItIsTold() {
        ScreenReader.forgetSaid()
        ScreenReader.isOn = { true }
        ScreenReader.announce = { _, text -> announced += text }
    }

    @After
    fun putBack() {
        ScreenReader.isOn = readerBefore
        ScreenReader.announce = announceBefore
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.waitForIdle()
        announced.clear()
        ScreenReader.forgetSaid()
        compose.runOnUiThread { compose.activity.setContent { Column(Modifier) { content() } } }
    }

    // Thirty and a half minutes ago: the card's "minutes ago" wording stays the same for the half minute either side of the test.
    private fun summary(): TranscriptSessionSummary {
        val end = secondsNow() - 1_830.0
        return TranscriptSessionSummary(
            id = UUID.randomUUID(), startedAt = end - 60.0, endedAt = end, segmentCount = 3,
            preview = "See you on Tuesday", engine = TranscriptionEngineKind.WhisperKit,
        )
    }

    @Test
    fun aLighterModelOfferIsSaidOnceWhenItAppearsAndNotWhenItComesBack() {
        val model = PhoneModelCatalog.all.first()
        var shown by mutableStateOf(true)
        show { if (shown) LighterModelOffer(model, waiting = false, inUseBytes = null, onSwitch = {}, onDismiss = {}) }
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(announced.toString(), 1, announced.size)
        assertTrue(announced.single(), announced.single().startsWith("Captions ran late on this phone"))
        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        compose.runOnUiThread { shown = true }
        compose.waitForIdle()
        assertEquals(announced.toString(), 1, announced.size)
    }

    @Test
    fun theNameOfferIsSaidOnceWhenItAppears() {
        show { NameAlertOffer(look, onOpen = {}, onDismiss = {}) }
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(listOf("Have the phone buzz when someone calls your name? Tap to write your name"), announced.toList())
    }

    @Test
    fun theRecentConversationCardIsSaidOnceWhenItAppears() {
        val recent = summary()
        var shown by mutableStateOf(true)
        show { if (shown) RecentConversationCard(recent, look, "en", onOpen = {}, onDismiss = {}) }
        compose.waitUntil(5_000) { announced.isNotEmpty() }
        compose.waitForIdle()
        assertEquals(announced.toString(), 1, announced.size)
        assertTrue(announced.single(), announced.single().startsWith("The conversation from "))
        compose.runOnUiThread { shown = false }
        compose.waitForIdle()
        compose.runOnUiThread { shown = true }
        compose.waitForIdle()
        assertEquals(announced.toString(), 1, announced.size)
    }

    @Test
    fun aLighterModelOfferTheUserPutAwayIsSaidAgainWhenItReturns() {
        val model = PhoneModelCatalog.all.first()
        var shown by mutableStateOf(true)
        show { if (shown) LighterModelOffer(model, waiting = false, inUseBytes = null, onSwitch = {}, onDismiss = { shown = false }) }
        compose.waitUntil(5_000) { announced.size == 1 }
        compose.onNodeWithContentDescription("Not now").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { shown = true }
        compose.waitUntil(5_000) { announced.size == 2 }
        assertEquals(announced.toString(), announced[0], announced[1])
    }

    @Test
    fun theNameOfferTheUserPutAwayIsSaidAgainWhenItReturns() {
        var shown by mutableStateOf(true)
        show { if (shown) NameAlertOffer(look, onOpen = {}, onDismiss = { shown = false }) }
        compose.waitUntil(5_000) { announced.size == 1 }
        compose.onNodeWithContentDescription("Not now").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { shown = true }
        compose.waitUntil(5_000) { announced.size == 2 }
        assertEquals(announced.toString(), announced[0], announced[1])
    }

    @Test
    fun theRecentConversationCardTheUserClosedIsSaidAgainWhenItReturns() {
        val recent = summary()
        var shown by mutableStateOf(true)
        show { if (shown) RecentConversationCard(recent, look, "en", onOpen = {}, onDismiss = { shown = false }) }
        compose.waitUntil(5_000) { announced.size == 1 }
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        compose.runOnUiThread { shown = true }
        compose.waitUntil(5_000) { announced.size == 2 }
        assertEquals(announced.toString(), announced[0], announced[1])
    }

    @Test
    fun theSoundAlertsNotWorkingBannerWaitsForTheSoundBeingReadInsteadOfCuttingItOff() {
        show { SoundAlertsNotWorkingBanner() }
        compose.onNodeWithText("Sound alerts aren’t working")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun theSaidPillIsAButtonToTalkBack() {
        val now = System.currentTimeMillis() / 1_000.0
        compose.runOnUiThread { CaptionState.clear(); CaptionState.heard(HeardWord(UUID.randomUUID(), "Doctor", now)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Said: Doctor").fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNodeWithText("Said: Doctor").fetchSemanticsNode()
        assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
    }

    @Test
    fun aModelBeingDownloadedShowsItsPercentInTheRowTalkBackReads() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
        val before = app.settings.current.value.copy()
        app.settings.change { it.languageCode = "he"; it.phoneModelFile = null }
        try {
            val models = FakePhoneModels(app.settings)
            show {
                val settings by app.settings.current.collectAsState()
                Column(Modifier.verticalScroll(rememberScrollState())) { PhoneModelPicker(settings, models, chooseBestAfterTest = false) }
            }
            val file = PhoneModelCatalog.offered(UILanguage.Hebrew)[0].file
            compose.runOnUiThread { models.switching.value = PhoneModelSwitch.Downloading(file, 0.5) }
            compose.waitUntil(5_000) {
                compose.onNodeWithTag("phone-model-$file").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.any { "50%" in it.text } == true
            }
        } finally {
            app.settings.change { it.languageCode = before.languageCode; it.phoneModelFile = before.phoneModelFile }
        }
    }

    private fun tapLabelOf(banner: String): String? {
        compose.waitForIdle()
        val node = compose.onNode(androidx.compose.ui.test.hasText(banner, substring = true)).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsActions.OnClick)?.label
    }

    @Test
    fun theEmergencyAlertsBannerTellsATalkBackUserWhereATapGoes() {
        val warning = EmergencyChannelNotice.Warning.Off
        show { EmergencyChannelBanner(warning, onOpenSettings = {}) }
        assertEquals("Open the Emergency settings", tapLabelOf(EmergencyChannelNotice.title(warning)))
    }

    @Test
    fun theAwayAlertsBannerTellsATalkBackUserWhereATapGoes() {
        show { AwayAlertsBlockedBanner(onOpenSettings = {}) }
        assertEquals("Open the phone’s settings", tapLabelOf(AwayAlertsNotice.title()))
    }
}
