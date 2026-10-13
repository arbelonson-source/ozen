package com.arbelonson.ozen

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import com.arbelonson.ozen.core.ReleaseFileFetching
import java.io.File
import java.io.IOException
import java.net.URI

class OzenTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        CaptionSession.newModelFetcher = { NoDownloads }
        super.callApplicationOnCreate(app)
        (app as OzenApplication).settings.change { it.hasCompletedOnboarding = true }
        app.captions.launchHandled = true
        app.captions.modelFetcher = NoDownloads
    }
}

private object NoDownloads : ReleaseFileFetching {
    override fun fetch(url: URI, from: Long, appendingTo: File, received: (Long) -> Unit) = throw IOException("device tests never download a model")

    override fun sha256(file: File): String = ""
}
