package com.zerosynk.app.send

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.zerosynk.app.service.SyncService

/**
 * Invisible trampoline launched from the notification action "Отправить файл".
 * Opens the system document picker (user gesture — allowed from notification),
 * then hands the chosen URI to SyncService for upload.
 */
class SendFileActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK)
        } catch (_: Exception) {
            Toast.makeText(this, "Не удалось открыть выбор файла", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val intent = Intent(this, SyncService::class.java).apply {
                    action = SyncService.ACTION_SEND_FILE
                    this.data = uri
                }
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "Отправка файла…", Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        private const val REQ_PICK = 41
    }
}
