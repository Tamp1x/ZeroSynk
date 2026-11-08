package com.zerosynk.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.service.SyncService

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs.get(context)
        if (prefs.paired && prefs.startOnBoot && prefs.syncEnabled) {
            SyncService.start(context)
        }
    }
}
