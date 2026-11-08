package com.zerosynk.app.send

import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.zerosynk.app.R

class SendClipboardTile : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Отправить буфер"
            icon = Icon.createWithResource(this@SendClipboardTile, R.drawable.ic_stat_sync)
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, SendClipboardActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        @Suppress("DEPRECATION")
        startActivityAndCollapse(intent)
    }
}
