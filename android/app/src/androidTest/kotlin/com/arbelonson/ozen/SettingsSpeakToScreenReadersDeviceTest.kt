package com.arbelonson.ozen

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import com.arbelonson.ozen.core.UILanguage
import org.junit.Rule
import org.junit.Test

class SettingsSpeakToScreenReadersDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { Column { content() } } }
    }

    @Test
    fun aWarningIsAPoliteLiveRegionSoAScreenReaderSaysItWhenItAppears() {
        show { Warning("The key wasn’t saved. Try again.") }
        compose.onNodeWithText("The key wasn’t saved. Try again.")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test
    fun theWordFieldKeepsItsNameAfterSomethingIsTyped() {
        show { ImportantWordsScreen(emptyList(), emptyList(), "en", UILanguage.English, emptyList(), onChange = {}, onClose = {}) }
        compose.onNodeWithText("A word or name, e.g., grandma").performTextInput("medicine")
        compose.onNodeWithText("A word or name, e.g., grandma").assertExists()
    }
}
