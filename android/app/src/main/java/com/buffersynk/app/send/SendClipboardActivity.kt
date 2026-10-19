package com.buffersynk.app.send

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.buffersynk.app.clipboard.ClipboardSync
import com.buffersynk.app.data.LoopGuard
import com.buffersynk.app.service.SyncService

/**
 * Invisible trampoline launched from the notification action "Отправить буфер".
 * When started from a user tap on a notification this activity gains window focus,
 * which lets us legally read the clipboard (Android 10+ blocks background reads).
 * Pattern used by KDE Connect. Finishes immediately after reading.
 */
class SendClipboardActivity : Activity() {

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
        val sent = SyncService.sendJson(msg)
        toast(
            when {
                sent && msg.kind == "image" -> "Изображение отправлено на ПК"
                sent -> "Текст отправлен на ПК"
                else -> "Нет подключения к ПК — попробуйте ещё раз"
            }
        )
        finish()
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
