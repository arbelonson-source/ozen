package com.arbelonson.ozen

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner

class OzenTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        super.callApplicationOnCreate(app)
        (app as OzenApplication).settings.change { it.hasCompletedOnboarding = true }
    }
}
