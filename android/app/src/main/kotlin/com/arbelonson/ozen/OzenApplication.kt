package com.arbelonson.ozen

import android.app.Application
import android.os.Debug
import com.arbelonson.ozen.core.CloudHTTP
import com.arbelonson.ozen.core.PhoneModelPartialCleanup
import com.arbelonson.ozen.core.SettingsStore
import com.arbelonson.ozen.core.UrlConnectionCloudHTTP
import java.io.File
import kotlinx.coroutines.MainScope

class OzenApplication : Application() {
    val settings by lazy { SettingsHolder(SettingsStore(File(filesDir, "ozen-settings.json"))) }
    val homeServerCode by lazy { HomeServerCodeStore(File(noBackupFilesDir, "home-server-code"), KeystoreCodeCipher()) }
    val cloudKeys by lazy { CloudKeyStore(File(noBackupFilesDir, "cloud-keys"), KeystoreCodeCipher("ozen-cloud-keys")) }
    val pairing by lazy { PairingRequests(settings, homeServerCode::save).also { it.onPaired = { engineSettings.pairedWithAComputer() } } }
    @Volatile
    var cloudKeyCheckHTTP: CloudHTTP = UrlConnectionCloudHTTP()
    val engineSettings by lazy {
        EngineSettings(settings, cloudKeys, homeServerCode, { captions.checkHomeComputer() }, MainScope(), http = { cloudKeyCheckHTTP.send(it) }) { captions.settingsChanged() }
    }
    private val session = lazy { CaptionSession(this, settings, homeServerCode, cloudKeys) }
    val captions by session
    val transcriber by lazy { FileTranscriber(this, settings, captions) }

    override fun onCreate() {
        super.onCreate()
        Thread({ PhoneModelPartialCleanup.removeStale(filesDir, System.currentTimeMillis()) }, "ozen-part-cleanup").start()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryPressure.isWarning(level) && session.isInitialized()) {
            captions.memoryShort(footprintBytes = Debug.getPss() * 1_024)
        }
    }
}
