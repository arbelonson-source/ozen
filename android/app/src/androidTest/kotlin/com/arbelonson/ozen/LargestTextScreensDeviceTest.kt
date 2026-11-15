package com.arbelonson.ozen

import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.PhoneModelCatalog
import com.arbelonson.ozen.core.PhoneModelSwitch
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.StoppedCaptionsNotice
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.DisplayPreferences.Theme
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargestTextScreensDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val model = PhoneModelCatalog.all.first()
    private val lighter = PhoneModelCatalog.all.last()
    private val look = CaptionTheme.of(Theme.Dark, systemDark = true)

    private fun onASmallPhoneAtTheLargestText(scrolling: Boolean, content: @Composable () -> Unit) {
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                    Box(Modifier.requiredSize(360.dp, 640.dp)) {
                        if (scrolling) Column(Modifier.verticalScroll(rememberScrollState())) { content() } else content()
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes().decodeToString() }

    private fun everyNode(inDialog: Boolean): List<SemanticsNode> =
        compose.onAllNodes(if (inDialog) hasAnyAncestor(isDialog()) else SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes()

    private fun wholly(node: SemanticsNode) = node.boundsInRoot.height > 0f && node.size.height - node.boundsInRoot.height < 1f && node.size.width - node.boundsInRoot.width < 1f

    private fun wordsOf(node: SemanticsNode) = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

    private fun scrollsItself(node: SemanticsNode) = node.config.contains(SemanticsProperties.VerticalScrollAxisRange)

    private fun assertNothingCutOrOverlapping(inDialog: Boolean = false) {
        val texts = everyNode(inDialog).filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }
        assertTrue("no text on screen", texts.isNotEmpty())
        val problems = ArrayList<String>()
        for (node in texts) {
            val layouts = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: continue
            if (layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any { layout.isLineEllipsized(it) } || layout.size.width > node.size.width || (layout.size.height > node.size.height && !scrollsItself(node))) problems += "cut off: \"${wordsOf(node)}\" needs ${layout.size.width}x${layout.size.height} px, shows ${node.size.width}x${node.size.height}, ${layout.lineCount} lines"
        }
        for ((i, a) in texts.withIndex()) for (b in texts.drop(i + 1)) {
            val across = minOf(a.boundsInRoot.right, b.boundsInRoot.right) - maxOf(a.boundsInRoot.left, b.boundsInRoot.left)
            val down = minOf(a.boundsInRoot.bottom, b.boundsInRoot.bottom) - maxOf(a.boundsInRoot.top, b.boundsInRoot.top)
            if (across >= 2f && down >= 2f) problems += "overlap: \"${wordsOf(a)}\" and \"${wordsOf(b)}\""
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    private fun assertEveryControlIsAFingerBig(inDialog: Boolean = false) {
        val finger = compose.activity.resources.displayMetrics.density * 48
        val controls = everyNode(inDialog).filter { it.config.getOrNull(SemanticsActions.OnClick) != null }
        assertTrue("no control on screen", controls.isNotEmpty())
        for (control in controls) {
            assertTrue("a control is ${control.size.width} by ${control.size.height} px, a finger needs $finger: ${wordsOf(control)}", control.size.width >= finger - 1 && control.size.height >= finger - 1)
        }
    }

    private fun reach(text: String, substring: Boolean = false) {
        val node = compose.onNode(hasText(text, substring = substring))
        if (!wholly(node.fetchSemanticsNode())) node.performScrollTo()
        node.assertIsDisplayed()
        assertTrue("$text is only partly on screen", wholly(node.fetchSemanticsNode()))
    }

    @Test
    fun theNightChecklistCanBeReadAndReachedToTheEndAtTheLargestText() {
        onASmallPhoneAtTheLargestText(scrolling = false) { NightAlertsScreen(soundsOn = false, onSounds = {}, onClose = {}) }
        assertNothingCutOrOverlapping()
        assertEveryControlIsAFingerBig()
        reach("Silent mode and charging")
        reach("On silent, alerts", substring = true)
        reach("Test that an alert arrives when the phone is locked")
    }

    @Test
    fun starredLinesCanBeReadAndReachedAtTheLargestText() {
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val startedAt = System.currentTimeMillis() / 1_000.0 - 60
        val said = (1..12).map { "Starred line $it: take two pills after dinner and come back on Thursday" }
        val record = TranscriptSessionRecord(
            startedAt = startedAt,
            endedAt = startedAt + 40,
            engine = TranscriptionEngineKind.HomeServer,
            modelVariant = "home server",
            inputName = null,
            segments = said.mapIndexed { index, text -> SavedSegment(UUID.randomUUID(), text, null, null, startedAt + index, true, isStarred = true) },
        )
        store.save(record)
        try {
            onASmallPhoneAtTheLargestText(scrolling = false) {
                StarredLinesScreen(app.captions.savedConversations, marksUncertain = true, emphasizeNumbers = true, onClose = {}, languageCode = "en")
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasContentDescription("Starred line 1:", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertNothingCutOrOverlapping()
            assertEveryControlIsAFingerBig()
            val row = compose.onNode(hasContentDescription("Starred line 1:", substring = true)).fetchSemanticsNode()
            val threeLines = 3 * 16f * 2f * compose.activity.resources.displayMetrics.density
            assertTrue("a starred line is only ${row.size.height} px tall, its three lines need $threeLines", row.size.height >= threeLines)
            val last = hasContentDescription("Starred line 12:", substring = true)
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(last)
            assertTrue("the last line is only partly on screen", wholly(compose.onNode(last).fetchSemanticsNode()))
        } finally {
            store.delete(record.id)
        }
    }

    @Test
    fun diagnosticsCanBeReadAndReachedToTheEndAtTheLargestText() {
        onASmallPhoneAtTheLargestText(scrolling = false) { DiagnosticsScreen(app.captions, onClose = {}) }
        assertNothingCutOrOverlapping()
        assertEveryControlIsAFingerBig()
        reach("Send report")
        reach("Copy report")
        reach("Status")
    }

    @Test
    fun theOfferAndNoticeCardsCanBeReadAndReachedAtTheLargestText() {
        onASmallPhoneAtTheLargestText(scrolling = true) {
            ListeningCutCard("Listening stopped at 3:12 PM after about 3 hours, while the phone was closed.", onDismiss = {})
            LighterModelOffer(model, waiting = false, inUseBytes = 600_000_000L, onSwitch = {}, onDismiss = {})
            ModelLoadCrashCard(model, lighter, inUseBytes = null, switching = PhoneModelSwitch.Idle, onSwitch = {}, onRetry = {})
            NameAlertOffer(look, onOpen = {}, onDismiss = {})
            SoundAlertsNotWorkingBanner()
        }
        assertNothingCutOrOverlapping()
        assertEveryControlIsAFingerBig()
        for (tag in listOf("model-fit-switch", "model-load-crash-switch", "model-load-crash-retry", "listening-cut-dismiss")) {
            val node = compose.onNode(hasTestTag(tag))
            if (!wholly(node.fetchSemanticsNode())) node.performScrollTo()
            assertTrue("$tag is only partly on screen", wholly(node.fetchSemanticsNode()))
        }
        reach("Tap to write your name")
        reach(StoppedCaptionsNotice.soundAlertsNotWorkingDetail())
    }

    @Test
    fun theThreeChoicesOfTheMobileDataQuestionAreAllOnScreenAtTheLargestText() {
        val scale = shell("settings get system font_scale").trim().takeIf { it.toFloatOrNull() != null } ?: "1.0"
        try {
            shell("settings put system font_scale 2.0")
            shell("wm size 720x1280")
            shell("wm density 320")
            compose.waitUntil(10_000) {
                val configuration = compose.activity.resources.configuration
                configuration.fontScale == 2f && configuration.screenWidthDp == 360
            }
            compose.runOnUiThread {
                compose.activity.setContent { CellularDownloadDialog(megabytes = 574, builtInOffered = true, onBuiltIn = {}, onDownload = {}, onWait = {}) }
            }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText("Wait for Wi‑Fi") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().isNotEmpty() }
            assertNothingCutOrOverlapping(inDialog = true)
            assertEveryControlIsAFingerBig(inDialog = true)
            val body = everyNode(inDialog = true).first { "language model downloads once" in wordsOf(it) }
            val layouts = ArrayList<TextLayoutResult>()
            body.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val sixLines = layouts.first().getLineBottom(5)
            val window = everyNode(inDialog = true).filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }.maxOfOrNull { it.size.height } ?: body.size.height
            assertTrue("the question has a window of only $window px, six lines need ${sixLines.toInt()}", window >= sixLines)
            for (choice in listOf("Captions now, with the phone’s built-in recognition", "Download now over mobile data", "Wait for Wi‑Fi")) {
                val node = compose.onNode(hasText(choice) and hasAnyAncestor(isDialog()))
                if (!wholly(node.fetchSemanticsNode())) node.performScrollTo()
                node.assertIsDisplayed()
                assertTrue("$choice is only partly on screen", wholly(node.fetchSemanticsNode()))
            }
        } finally {
            shell("wm size reset")
            shell("wm density reset")
            shell("settings put system font_scale $scale")
        }
    }
}
