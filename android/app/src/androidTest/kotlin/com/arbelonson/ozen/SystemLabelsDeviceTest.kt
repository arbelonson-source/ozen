package com.arbelonson.ozen

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemLabelsDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun theSwitchNamesComeFromThePhonesOwnSettingsAppInThePhonesLanguage() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM)
        assertEquals("Override Do Not Disturb", SystemLabels.fromSettings(context, SystemLabels.OVERRIDE_DND))
        assertEquals("Allow background usage", SystemLabels.fromSettings(context, SystemLabels.ALLOW_BACKGROUND))
        assertEquals("Unrestricted", SystemLabels.fromSettings(context, SystemLabels.UNRESTRICTED))
        assertEquals("All apps", SystemLabels.fromSettings(context, SystemLabels.ALL_APPS))
    }

    @Test
    fun aNameTheSettingsAppDoesNotHaveGivesNothing() {
        assertNull(SystemLabels.fromSettings(context, "no_such_label_in_settings"))
    }
}
