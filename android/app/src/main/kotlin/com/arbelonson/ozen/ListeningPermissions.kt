package com.arbelonson.ozen

import android.Manifest
import android.os.Build

object ListeningPermissions {
    fun wanted(sdk: Int): List<String> = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (sdk >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }
}
