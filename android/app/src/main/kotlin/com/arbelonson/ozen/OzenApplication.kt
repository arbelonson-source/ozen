package com.arbelonson.ozen

import android.app.Application
import android.os.Debug
import com.arbelonson.ozen.core.SettingsStore
import java.io.File

class OzenApplication : Application() {
    val settings by lazy { SettingsHolder(SettingsStore(File(filesDir, "ozen-settings.json"))) }
    val homeServerCode by lazy { HomeServerCodeStore(File(noBackupFilesDir, "home-server-code"), KeystoreCodeCipher()) }
    val cloudKeys by lazy { CloudKeyStore(File(noBackupFilesDir, "cloud-keys"), KeystoreCodeCipher("ozen-cloud-keys")) }
    val pairing by lazy { PairingRequests(settings, homeServerCode::save).also { it.onPaired = { captions.settingsChanged() } } }
    val engineSettings by lazy { EngineSettings(settings, cloudKeys, homeServerCode) { captions.settingsChanged() } }
    private val session = lazy { CaptionSession(this, settings, homeServerCode, cloudKeys) }
    val captions by session

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryPressure.isWarning(level) && session.isInitialized()) {
            captions.pipeline.handleMemoryWarning(footprintBytes = Debug.getPss() * 1_024)
        }
    }
}
