package com.arbelonson.ozen

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.result.ActivityResultLauncher

internal object NotificationAsking {
    var answered: (Activity) -> Boolean? = { activity ->
        when {
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> true
            activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) -> false
            else -> null
        }
    }

    var ask: (ActivityResultLauncher<String>) -> Unit = { it.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
