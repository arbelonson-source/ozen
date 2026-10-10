package com.arbelonson.ozen

import android.app.Application
import com.arbelonson.ozen.core.SettingsStore
import java.io.File

class OzenApplication : Application() {
    val settings by lazy { SettingsHolder(SettingsStore(File(filesDir, "ozen-settings.json"))) }
    val homeServerCode by lazy { HomeServerCodeStore(File(noBackupFilesDir, "home-server-code"), KeystoreCodeCipher()) }
    val cloudKeys by lazy { CloudKeyStore(File(noBackupFilesDir, "cloud-keys"), KeystoreCodeCipher("ozen-cloud-keys")) }
    val pairing by lazy { PairingRequests(settings, homeServerCode::save).also { it.onPaired = { captions.settingsChanged() } } }
    val captions by lazy { CaptionSession(this, settings, homeServerCode, cloudKeys) }
}
