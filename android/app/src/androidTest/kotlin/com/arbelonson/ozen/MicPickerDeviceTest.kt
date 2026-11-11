package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.PhoneSound
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.RoutedElsewhere
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MicPickerDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val bottom = AudioInputDescriptor("BuiltInMic:bottom", "SM-X", AudioPortType.BuiltInMic)
    private val back = AudioInputDescriptor("BuiltInMic:back", "SM-X", AudioPortType.BuiltInMic)

    @After
    fun tidy() {
        compose.runOnUiThread {
            CaptionState.phoneSoundStopped(false)
            CaptionState.clear()
        }
    }

    private val lapel = AudioInputDescriptor("usb:lapel", "Lapel mic", AudioPortType.Usb)
    private val refusal = "The phone did not switch to “Lapel mic”. Try reconnecting it. The marked microphone is still the one in use."

    private fun show(inputs: List<AudioInputDescriptor>, selected: AudioInputDescriptor?, listening: Boolean) {
        compose.runOnUiThread {
            compose.activity.setContent {
                MicPickerScreen(
                    inputs = inputs,
                    selected = selected,
                    listening = listening,
                    phoneSoundStopped = false,
                    level = { 0f },
                    onChoose = { true },
                    onRefresh = {},
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun thePhonesSoundStoppingIsSaidInThePickerWhereSheIsWhenItHappens() {
        compose.onNodeWithContentDescription("Choose microphone").performClick()
        compose.onNodeWithText("Available microphones").assertExists()
        compose.runOnUiThread { CaptionState.phoneSoundStopped(true) }
        compose.onNodeWithText("Stopped listening to the phone’s sound. Back to the microphone.").performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun withThePhonesSoundChosenTheMeterNoteAsksForAVideoAndNotForSpeaking() {
        show(listOf(PhoneSound.entry), PhoneSound.entry, listening = true)
        compose.onAllNodes(hasText("Speak and watch the bar move", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("Play a video or a song on the phone", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withCaptionsOffNothingSaysAMicrophoneIsRecordingNow() {
        show(listOf(bottom), bottom, listening = false)
        compose.onAllNodes(hasText("recording now", substring = true)).assertCountEquals(0)
        compose.onNodeWithText("The marked microphone is the one captions will use.", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun thePhonesTwoMicrophonesShowAsOneRowNamedForThePhone() {
        show(listOf(bottom, back), back, listening = true)
        compose.onAllNodes(isSelectable()).assertCountEquals(1)
        compose.onNode(isSelectable()).assert(hasText("This phone’s microphone")).assertIsSelected()
        compose.onAllNodes(hasText("SM-X")).assertCountEquals(0)
    }

    @Test
    fun aRefusalGoesOnceThePhoneSwitchesToTheTappedMicrophoneAfterAll() {
        val marked = mutableStateOf(bottom)
        compose.runOnUiThread {
            compose.activity.setContent {
                MicPickerScreen(
                    inputs = listOf(bottom, lapel),
                    selected = marked.value,
                    listening = true,
                    phoneSoundStopped = false,
                    level = { 0f },
                    onChoose = { false },
                    onRefresh = {},
                    onClose = {},
                )
            }
        }
        compose.onNode(hasText("Lapel mic") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithText(refusal).performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { marked.value = lapel }
        compose.waitForIdle()
        compose.onNode(hasText("Lapel mic") and isSelectable()).assertIsSelected()
        compose.onAllNodesWithText(refusal).assertCountEquals(0)
    }

    @Test
    fun aSwitchThePhoneTookBackWhileCaptionsRunIsSaidInThePickerOverTheCaptionScreen() {
        compose.runOnUiThread {
            CaptionState.show(PipelinePhase.Listening, engine = null, interruptedBySystem = false, scheduledRetry = null, segments = emptyList())
        }
        compose.onNodeWithContentDescription("Choose microphone").performClick()
        compose.onNodeWithText("Available microphones").assertExists()
        compose.runOnUiThread { CaptionState.inputs(listOf(bottom, lapel), bottom.uid, inUse = bottom, routedElsewhere = RoutedElsewhere(lapel, bottom)) }
        compose.onNodeWithText("Lapel mic isn’t recording").performScrollTo().assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithText("Captions come from SM-X instead.").performScrollTo().assertIsDisplayed()
    }
}
