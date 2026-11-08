package com.arbelonson.ozen

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.arbelonson.ozen.core.AppLanguage
import com.arbelonson.ozen.core.AppSettings
import com.arbelonson.ozen.core.Localization
import com.arbelonson.ozen.core.UILanguage
import org.junit.After
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ListeningCutDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as OzenApplication
    private val records = PreferencesListeningStore(app)
    private val before: AppSettings = app.settings.current.value.copy()
    private val aliveAt = System.currentTimeMillis() / 1_000.0 - 3 * 3_600

    @Before
    fun leaveARecordAsAKilledProcessWould() {
        app.settings.change {
            it.appLanguage = AppLanguage.English
            it.hasCompletedOnboarding = true
        }
        val version = PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
        records.write(ListeningCut.encode(ListeningCut.Record(startedAt = aliveAt - 5 * 3_600, aliveAt = aliveAt, version = version)))
        app.captions.launchHandled = true
        app.captions.checkListeningRecord()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { Localization.language == UILanguage.English }
    }

    @After
    fun restore() {
        records.clear()
        app.captions.dismissListeningCut()
        app.settings.change {
            it.appLanguage = before.appLanguage
            it.hasCompletedOnboarding = before.hasCompletedOnboarding
        }
    }

    @Test
    fun aRecordOfListeningThatWasNotEndedShowsTheCardOnceAndTheNextLaunchShowsNothing() {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening-cut").fetchSemanticsNodes().isNotEmpty() }
        val shown = compose.onNodeWithTag("listening-cut-text").fetchSemanticsNode().config.toString()
        assertTrue(shown, clockTime(aliveAt) in shown && "3 hours" in shown)
        assertNull("the record is read once", records.read())
        compose.onNodeWithTag("listening-cut-dismiss").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening-cut").fetchSemanticsNodes().isEmpty() }
        app.captions.checkListeningRecord()
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { Localization.language == UILanguage.English }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithTag("listening-cut").fetchSemanticsNodes().isEmpty())
    }
}
