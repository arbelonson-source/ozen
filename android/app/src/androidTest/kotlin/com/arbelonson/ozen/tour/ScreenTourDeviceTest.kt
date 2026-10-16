package com.arbelonson.ozen.tour

import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.toSize
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.arbelonson.ozen.CaptionSession
import com.arbelonson.ozen.CaptionState
import com.arbelonson.ozen.HeardWord
import com.arbelonson.ozen.MainActivity
import com.arbelonson.ozen.OzenApplication
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.AudioInputDescriptor
import com.arbelonson.ozen.core.AudioPortType
import com.arbelonson.ozen.core.BatteryWarning
import com.arbelonson.ozen.core.DisplayPreferences
import com.arbelonson.ozen.core.EnginePreparationProgress
import com.arbelonson.ozen.core.PipelineFailure
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.PipelinePhase
import com.arbelonson.ozen.core.SavedSegment
import com.arbelonson.ozen.core.SoundAlert
import com.arbelonson.ozen.core.SoundEventCatalog
import com.arbelonson.ozen.core.TranscriptHistoryStore
import com.arbelonson.ozen.core.TranscriptSegment
import com.arbelonson.ozen.core.TranscriptSessionRecord
import com.arbelonson.ozen.core.TranscriptionEngineKind
import com.arbelonson.ozen.core.UILanguage
import com.arbelonson.ozen.core.tr
import java.io.File
import java.util.UUID
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every screen, page by page, in several languages, looks, text sizes and
 * screen shapes: a picture of each, and a list of what on it is drawn over
 * something else, cut off, or under the phone's own bars. The pictures land
 * in the app's files on the phone (Android/data/com.arbelonson.ozen/files/tour)
 * for a person or an agent to look at.
 *
 * Runs only when asked, as it takes a quarter of an hour:
 * `am instrument -w -e tour 1 -e configs en-dark,he-light -e class com.arbelonson.ozen.tour.ScreenTourDeviceTest ...`
 */
@RunWith(AndroidJUnit4::class)
class ScreenTourDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private class Setup(
        val name: String,
        val language: AppLanguage,
        val theme: DisplayPreferences.Theme,
        val fontScale: Float,
        val screen: String? = null,
        val sideways: Boolean = false,
    )

    private val setups = listOf(
        Setup("en-dark", AppLanguage.English, DisplayPreferences.Theme.Dark, 1f),
        Setup("he-light", AppLanguage.Hebrew, DisplayPreferences.Theme.Light, 1f),
        Setup("en-light-big", AppLanguage.English, DisplayPreferences.Theme.Light, 2f),
        Setup("he-dark-big", AppLanguage.Hebrew, DisplayPreferences.Theme.Dark, 2f),
        Setup("de-contrast", AppLanguage.German, DisplayPreferences.Theme.HighContrast, 1.3f),
        Setup("ar-dark", AppLanguage.Arabic, DisplayPreferences.Theme.Dark, 1f),
        Setup("ru-small", AppLanguage.Russian, DisplayPreferences.Theme.Dark, 1.15f, screen = "720x1280:320"),
        Setup("en-small-big", AppLanguage.English, DisplayPreferences.Theme.Dark, 1.5f, screen = "720x1280:320"),
        Setup("en-sideways", AppLanguage.English, DisplayPreferences.Theme.Dark, 1f, sideways = true),
        Setup("he-tablet", AppLanguage.Hebrew, DisplayPreferences.Theme.Light, 1f, screen = "1600x2560:320"),
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as OzenApplication
    private val folder = File(app.getExternalFilesDir(null), "tour").apply { mkdirs() }
    private val report = File(folder, "overlaps.txt")
    private var prefix = ""

    @Test
    fun everyScreenInEverySetup() {
        assumeTrue("only when asked: -e tour 1", InstrumentationRegistry.getArguments().getString("tour") == "1")
        val wanted = InstrumentationRegistry.getArguments().getString("configs")?.split(',')?.toSet()
        val before = app.settings.current.value.copy()
        val store = TranscriptHistoryStore(File(app.filesDir, CaptionSession.HISTORY_FOLDER))
        val saved = savedConversations()
        saved.forEach(store::save)
        try {
            for (setup in setups.filter { wanted == null || it.name in wanted }) {
                prefix = setup.name
                apply(setup)
                tour()
            }
        } finally {
            shell("settings put system font_scale 1.0")
            shell("wm size reset")
            shell("wm density reset")
            shell("settings put system user_rotation 0")
            app.settings.change {
                it.appLanguage = before.appLanguage
                it.display = before.display
                it.keywordAlerts = before.keywordAlerts
                it.nameAlertOfferDismissed = before.nameAlertOfferDismissed
                it.hasCompletedOnboarding = true
            }
            saved.forEach { store.delete(it.id) }
            compose.runOnUiThread { CaptionState.clear() }
        }
    }

    private fun apply(setup: Setup) {
        app.settings.change {
            it.appLanguage = setup.language
            it.display = it.display.copy(theme = setup.theme, autoHideControls = false)
            it.keywordAlerts = emptyList()
            it.nameAlertOfferDismissed = false
            it.hasCompletedOnboarding = true
        }
        shell("settings put system font_scale ${setup.fontScale}")
        val size = setup.screen?.split(':')
        shell(if (size != null) "wm size ${size[0]}" else "wm size reset")
        shell(if (size != null) "wm density ${size[1]}" else "wm density reset")
        shell("settings put system accelerometer_rotation 0")
        shell("settings put system user_rotation ${if (setup.sideways) 1 else 0}")
        Thread.sleep(1_500)
        val current = activity()
        instrumentation.runOnMainSync { current.recreate() }
        compose.waitUntil(10_000) { activity().resources.configuration.fontScale == setup.fontScale }
        compose.waitForIdle()
        backToCaptions()
    }

    private fun tour() {
        step("captions-empty") {
            compose.runOnUiThread { CaptionState.clear() }
            shot("captions-empty")
        }
        step("name-alert") {
            click(tr("הקישו כדי לכתוב את השם שלך", "Tap to write your name"), substring = true)
            pages("name-alert")
            backToCaptions()
        }
        step("captions-listening") {
            compose.runOnUiThread {
                CaptionState.show(PipelinePhase.Listening, engine = TranscriptionEngineKind.WhisperKit, interruptedBySystem = false, scheduledRetry = null, segments = emptyList(), listeningStartedAt = now())
            }
            shot("captions-listening")
        }
        for ((name, phase) in listOf(
            "model-download" to PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.DownloadingModel, fraction = 0.42)),
            "model-loading" to PipelinePhase.PreparingEngine(EnginePreparationProgress(EnginePreparationProgress.Stage.LoadingModel, isFirstTime = true)),
            "model-failed" to PipelinePhase.Failed(PipelineFailure(PipelineFailure.Kind.TranscriptionStopped, "tour")),
        )) {
            step(name) {
                compose.runOnUiThread {
                    CaptionState.show(phase, engine = TranscriptionEngineKind.WhisperKit, interruptedBySystem = false, scheduledRetry = null, segments = emptyList())
                }
                shot(name)
            }
        }
        step("captions-lines") {
            showLines()
            shot("captions-lines")
        }
        step("captions-banners") {
            compose.runOnUiThread {
                CaptionState.sound(SoundAlert(event = SoundEventCatalog.event("smoke_detector")!!, confidence = 0.9, timestamp = now()))
                CaptionState.battery(BatteryWarning.Low(19))
                CaptionState.savingTrouble(true)
                CaptionState.heard(HeardWord(UUID.randomUUID(), phrases().first(), now()))
            }
            shot("captions-banners")
            val builtIn = AudioInputDescriptor("built-in", "Phone microphone", AudioPortType.BuiltInMic)
            val headset = AudioInputDescriptor("bt", "Bluetooth headset", AudioPortType.Bluetooth)
            compose.runOnUiThread {
                CaptionState.sound(null)
                CaptionState.battery(null)
                CaptionState.savingTrouble(false)
                CaptionState.inputs(listOf(builtIn, headset), builtIn.uid, dropped = headset)
            }
            shot("captions-microphone-dropped")
            compose.runOnUiThread { CaptionState.inputs(listOf(builtIn, headset), headset.uid) }
            shot("captions-headset")
        }
        step("line-menu") {
            showLines()
            val lines = phrases()
            val centreOnScreen = { text: String ->
                compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().firstOrNull()?.let { node ->
                    val centre = node.positionInRoot + androidx.compose.ui.geometry.Offset(node.size.width / 2f, node.size.height / 2f)
                    node.boundsInRoot.contains(centre)
                } == true
            }
            val shown = (0..4).reversed().firstOrNull { centreOnScreen(lines[it]) }
            assertTrue("none of the caption lines is on screen", shown != null)
            compose.onAllNodes(hasText(lines[shown!!], substring = true)).onFirst().performTouchInput { longClick() }
            compose.waitUntil(5_000) { compose.onAllNodes(hasText(tr("העתקה", "Copy"))).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            shot("line-menu")
            pressBack()
            backToCaptions()
        }
        step("say") {
            click(tr("להגיד משהו בקול", "Say something out loud"))
            shot("say")
            compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(phrases().last())
            shot("say-typed")
            click(tr("מסך מלא באותיות גדולות", "Full screen, big letters"))
            shot("big-letters")
            backToCaptions()
        }
        step("microphones") {
            click(tr("בחירת מיקרופון", "Choose microphone"))
            pages("microphones")
            backToCaptions()
        }
        step("history") {
            click(tr("היסטוריה", "History"))
            pages("history")
            click(TITLE, substring = true)
            pages("conversation")
            backToCaptions()
        }
        step("settings") {
            click(tr("הגדרות", "Settings"))
            pages("settings", maxPages = 40)
            for ((name, label) in listOf(
                "important-words" to tr("מילים חשובות", "Important words"),
                "sounds" to tr("צלילים בבית", "Sounds at home"),
                "words" to tr("שמות ומילים מיוחדות", "Names and special words"),
                "add-speaker" to tr("הוספת דובר", "Add speaker"),
                "diagnostics" to tr("אבחון", "Diagnostics"),
                "support" to tr("תמיכה באוזן (רק אם תרצו)", "Support Ozen (only if you want to)"),
            )) {
                step(name) {
                    click(label)
                    pages(name, maxPages = 30)
                    pressBack()
                }
            }
            backToCaptions()
        }
        step("walkthrough") {
            app.settings.change { it.hasCompletedOnboarding = false }
            compose.waitForIdle()
            val next = tr("הבא", "Next")
            for (page in 1..10) {
                pages("walkthrough-$page", maxPages = 7)
                if (compose.onAllNodes(hasText(next)).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()) break
                click(next)
            }
        }
        app.settings.change { it.hasCompletedOnboarding = true }
        compose.waitForIdle()
        backToCaptions()
    }

    /** One stop of the tour: a stop that fails is written down with a picture, and the tour goes on. */
    private fun step(name: String, body: () -> Unit) {
        try {
            body()
        } catch (failure: Throwable) {
            write("$prefix-$name\tstep failed\t${failure.toString().lineSequence().first().take(300)}")
            runCatching { shot("$name-failed") }
            runCatching { backToCaptions() }
        }
    }

    private fun showLines() {
        val start = now() - 120
        val lines = phrases().mapIndexed { index, text ->
            TranscriptSegment(UUID.randomUUID(), text, isCommitted = index < 5, startTimestamp = start + index * 8, lastUpdateTimestamp = start + index * 8 + 6)
        }
        compose.runOnUiThread {
            CaptionState.clear()
            CaptionState.show(PipelinePhase.Listening, engine = TranscriptionEngineKind.WhisperKit, interruptedBySystem = false, scheduledRetry = null, segments = lines, listeningStartedAt = start, languageCode = if (Localization.language.isRightToLeft) "he" else "en")
        }
    }

    /** Sentences in the language on screen, from the app's own quick phrases. */
    private fun phrases(): List<String> = AppSettings.defaultQuickPhrases(Localization.language).take(6)

    private fun click(label: String, substring: Boolean = false) {
        val node = compose.onAllNodes(hasText(label, substring = substring) or hasContentDescription(label, substring = substring)).onFirst()
        runCatching { node.performScrollTo() }
        node.performClick()
        compose.waitForIdle()
    }

    private fun onCaptionScreen() =
        compose.onAllNodes(hasContentDescription(tr("בחירת מיקרופון", "Choose microphone"))).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun backToCaptions() {
        repeat(6) {
            if (onCaptionScreen()) return
            pressBack()
        }
    }

    /**
     * A dialog or menu is its own window and closes on the phone's Back key;
     * a screen of the app closes on the activity's own back. The caption
     * screen is never backed out of: that closes the app.
     */
    private fun pressBack() {
        if (compose.onAllNodes(isRoot()).fetchSemanticsNodes(atLeastOneRootRequired = false).size > 1) {
            shell("input keyevent 4")
        } else if (!onCaptionScreen()) {
            val current = activity()
            instrumentation.runOnMainSync { current.onBackPressedDispatcher.onBackPressed() }
        }
        compose.waitForIdle()
    }

    /** The app's screen in front now: a step that closed it by mistake opens it again, and the tour goes on. */
    private fun activity(): MainActivity {
        var resumed: MainActivity? = null
        instrumentation.runOnMainSync {
            resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
        }
        resumed?.let { return it }
        val started = instrumentation.startActivitySync(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        compose.waitForIdle()
        return started
    }

    /** A picture of the screen, then of each further screenful as far as it scrolls. */
    private fun pages(name: String, maxPages: Int = 12) {
        shot(name)
        val scrolls = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        for (page in 2..maxPages) {
            val scrollers = compose.onAllNodes(scrolls).fetchSemanticsNodes(atLeastOneRootRequired = false)
            val index = scrollers.indices.maxByOrNull { scrollers[it].size.width * scrollers[it].size.height } ?: return
            val scroller = scrollers[index]
            val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
            if (range.value() >= range.maxValue() - 1f) return
            val before = range.value()
            compose.onAllNodes(scrolls)[index].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, scroller.size.height * 0.8f) }
            compose.waitForIdle()
            val after = compose.onAllNodes(scrolls).fetchSemanticsNodes(atLeastOneRootRequired = false).getOrNull(index)?.config?.get(SemanticsProperties.VerticalScrollAxisRange)?.value()
            if (after == null || after <= before) return
            shot("$name-$page")
        }
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(500)
        val full = "$prefix-$name"
        val picture = instrumentation.uiAutomation.takeScreenshot() ?: error("no screenshot for $full")
        File(folder, "$full.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        picture.recycle()
        val found = findings(full)
        write(if (found.isEmpty()) "$full\tclean" else found.joinToString("\n"))
    }

    private class Shown(val label: String, val visible: Rect, val whole: Rect, val clipped: Rect, val scrolls: Boolean, val node: SemanticsNode) {
        override fun toString() = "'${label.replace('\n', ' ').take(50)}' ${visible.left.toInt()},${visible.top.toInt()} ${visible.width.toInt()}x${visible.height.toInt()}"
    }

    /**
     * Texts, buttons and pictures drawn over each other, cut off by what
     * holds them, running off the side or under the phone's own bars. Each
     * window (the screen, a dialog, a menu) is checked on its own: a dialog
     * over the screen is meant to be.
     */
    private fun findings(name: String): List<String> {
        val found = mutableListOf<String>()
        var slack = 0f
        var width = 0f
        var height = 0f
        var bars = emptyList<Pair<String, Rect>>()
        val shownActivity = activity()
        compose.runOnIdle {
            slack = 2 * shownActivity.resources.displayMetrics.density
            val decor = shownActivity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            width = decor.width.toFloat()
            height = decor.height.toFloat()
            bars = insets?.let {
                listOf(
                    "status bar" to Rect(0f, 0f, width, it.top.toFloat()),
                    "navigation bar" to Rect(0f, height - it.bottom, width, height),
                    "side bar" to Rect(0f, 0f, it.left.toFloat(), height),
                    "side bar" to Rect(width - it.right, 0f, width, height),
                ).filter { (_, area) -> area.width > 0f && area.height > 0f }
            } ?: emptyList()
        }
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        run {
            for ((rootIndex, root) in roots.withIndex()) {
                val shown = mutableListOf<Shown>()
                collect(root, clip = Rect(0f, 0f, width, height), scrolls = false, into = shown)
                val onScreen = shown.filter { it.visible.width >= 1f && it.visible.height >= 1f }
                for ((index, first) in onScreen.withIndex()) {
                    if (!first.scrolls && (first.clipped.height < first.whole.height - slack || first.clipped.width < first.whole.width - slack)) {
                        found += "$name\tcut off\t$first\twhole ${first.whole.width.toInt()}x${first.whole.height.toInt()}"
                    }
                    if (first.whole.left < -slack || first.whole.right > width + slack) {
                        found += "$name\toff the side\t$first"
                    }
                    if (rootIndex == 0) {
                        for ((bar, area) in bars) {
                            val under = first.visible.intersect(area)
                            if (under.width > slack && under.height > slack) found += "$name\tunder the $bar\t$first"
                        }
                    }
                    textCut(first)?.let { found += "$name\ttext cut\t$first\t$it" }
                    for (second in onScreen.drop(index + 1)) {
                        val shared = first.visible.intersect(second.visible)
                        if (shared.width > slack && shared.height > slack) {
                            found += "$name\toverlap\t$first\t$second"
                        }
                    }
                }
            }
        }
        return found
    }

    /**
     * The innermost texts, buttons, fields and pictures: a button's own label
     * is its child, not a second thing over it. What shows of each is worked
     * out here, cut to every list or pager holding it: Compose's own clipped
     * bounds put a line scrolled out above a list at the list's top edge,
     * full size, and a pager's next page over the page shown.
     */
    private fun collect(node: SemanticsNode, clip: Rect, scrolls: Boolean, into: MutableList<Shown>): Boolean {
        if (!node.layoutInfo.isPlaced) return false
        val config = node.config
        val container = config.contains(SemanticsProperties.VerticalScrollAxisRange) || config.contains(SemanticsProperties.HorizontalScrollAxisRange)
        val whole = Rect(node.positionInWindow, node.size.toSize())
        val inner = if (container) clip.intersect(whole) else clip
        var shownChild = false
        for (child in node.children) {
            if (collect(child, inner, scrolls || container, into)) shownChild = true
        }
        val label = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }
            ?: config.getOrNull(SemanticsProperties.EditableText)?.text
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")
            ?: if (config.contains(SemanticsActions.OnClick)) "(tap target)" else null
        if (label == null) return shownChild
        if (!shownChild) {
            into += Shown(label, whole.intersect(clip), whole, node.boundsInWindow, scrolls, node)
        }
        return true
    }

    private fun textCut(shown: Shown): String? {
        val action = shown.node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val layouts = mutableListOf<TextLayoutResult>()
        var laidOut = false
        compose.runOnUiThread { laidOut = runCatching { action(layouts) }.getOrNull() == true }
        if (!laidOut) return null
        val layout = layouts.firstOrNull() ?: return null
        return "ends in an ellipsis".takeIf { (0 until layout.lineCount).any { layout.isLineEllipsized(it) } }
    }

    private fun write(line: String) {
        report.appendText(line + "\n")
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun now() = System.currentTimeMillis() / 1_000.0

    private fun savedConversations(): List<TranscriptSessionRecord> {
        val start = now() - 3 * 86_400
        val english = AppSettings.defaultQuickPhrases(UILanguage.English)
        return (0 until 3).map { day ->
            val at = start + day * 86_400
            TranscriptSessionRecord(
                startedAt = at,
                endedAt = at + 1_800,
                engine = TranscriptionEngineKind.WhisperKit,
                modelVariant = "ivrit-ai turbo",
                inputName = "Phone microphone",
                segments = english.mapIndexed { index, text -> SavedSegment(UUID.randomUUID(), text, if (index % 2 == 0) "Dana" else null, null, at + index * 30, true) },
                title = if (day == 0) TITLE else null,
            )
        }
    }

    private companion object {
        const val TITLE = "Visit to the doctor about the new pills and the follow-up appointment"
    }
}
