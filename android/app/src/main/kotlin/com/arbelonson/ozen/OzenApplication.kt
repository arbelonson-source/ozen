package com.arbelonson.ozen

import android.app.Application
import com.arbelonson.ozen.core.SettingsStore
import java.io.File

class OzenApplication : Application() {
    val settings by lazy { SettingsHolder(SettingsStore(File(filesDir, "ozen-settings.json"))) }
    val homeServerCode by lazy { HomeServerCodeStore(File(noBackupFilesDir, "home-server-code"), KeystoreCodeCipher()) }
    val pairing by lazy { PairingRequests(settings, homeServerCode::save) }
}
