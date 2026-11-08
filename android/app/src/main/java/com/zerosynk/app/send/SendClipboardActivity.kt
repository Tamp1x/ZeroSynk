package com.zerosynk.app.send

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.zerosynk.app.clipboard.ClipboardSync
import com.zerosynk.app.data.LoopGuard
import com.zerosynk.app.service.SyncService
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.ui.localize

/**
 * Invisible trampoline launched from the notification action "Отправить буфер".
 * When started from a user tap on a notification this activity gains window focus,
 * which lets us legally read the clipboard (Android 10+ blocks background reads).
 * Pattern used by KDE Connect. Finishes immediately after reading.
 */
class SendClipboardActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendAndFinish()
    }

    private fun sendAndFinish() {
        val msg = ClipboardSync.readClipboardMessage(this)
        if (msg == null) {
            toast("Буфер пуст")
            finish()
            return
        }
        LoopGuard.lastSentHash = msg.hash
        if (!SyncService.isConnected()) SyncService.start(this)
        var attempts = 0
        fun deliver() {
            if (SyncService.isConnected()) {
                val sent = SyncService.sendJson(msg)
                toast(if (sent) {
                    if (msg.kind == "image") "Изображение отправлено на ПК" else "Текст отправлен на ПК"
                } else "Не удалось отправить буфер")
                finish()
            } else if (++attempts < 24) {
                handler.postDelayed({ deliver() }, 250)
            } else {
                toast("Нет подключения к ПК — попробуйте ещё раз")
                finish()
            }
        }
        handler.postDelayed({ deliver() }, 250)
    }

    private fun toast(msg: String) {
        Toast.makeText(this, localize(msg, Prefs.get(this).language == "en"), Toast.LENGTH_SHORT).show()
    }
}
