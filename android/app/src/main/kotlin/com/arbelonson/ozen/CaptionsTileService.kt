package com.arbelonson.ozen

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.arbelonson.ozen.core.tr

class CaptionsTileService : TileService() {
    override fun onStartListening() {
        val tile = qsTile ?: return
        StartCaptionsRequest.applyAppLanguage(application as OzenApplication)
        tile.label = tr("כתוביות אוזן", "Ozen captions")
        tile.state = if (CaptionState.screen.value.captionsRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(StartCaptionsRequest.launchPendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(StartCaptionsRequest.launchIntent(this))
        }
    }
}
