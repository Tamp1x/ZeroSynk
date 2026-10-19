package com.buffersynk.app.send

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.buffersynk.app.data.Prefs
import com.buffersynk.app.service.Conn
import com.buffersynk.app.service.ServiceState
import com.buffersynk.app.service.SyncService
import com.buffersynk.app.ui.theme.Green
import com.buffersynk.app.ui.theme.ZeroSynkTheme

/**
 * Target of the system share sheet ("Поделиться" → ZeroSynk).
 * Accepts ACTION_SEND / ACTION_SEND_MULTIPLE (files, images, text) and shows a
 * small confirm dialog before pushing everything to the paired PC.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = ArrayList<Uri>()
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        if (uris.isEmpty()) intent.data?.let { uris.add(it) }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        if (uris.isEmpty() && text.isNullOrBlank()) {
            finish()
            return
        }

        val prefs = Prefs.get(this)
        if (!prefs.paired) {
            Toast.makeText(this, "Сначала подключите ПК в ZeroSynk", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val pcName = prefs.pcName ?: "ПК"
        val names = uris.map { queryName(it) }
        val totalSize = uris.sumOf { querySize(it) }

        setContent {
            ZeroSynkTheme {
                ShareConfirm(
                    pcName = pcName,
                    fileNames = names,
                    totalSize = totalSize,
                    text = text,
                    onSend = {
                        send(uris, text)
                        finish()
                    },
                    onCancel = { finish() },
                )
            }
        }
    }

    private fun send(uris: List<Uri>, text: String?) {
        // Take persistable read permission BEFORE finishing, otherwise URIs die with the Activity
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (_: Exception) {
                // Some providers don't support persistable grants — that's fine,
                // the temporary grant lasts until the sending process completes.
            }
        }
        val intent = Intent(this, SyncService::class.java)
        when {
            uris.isNotEmpty() -> {
                intent.action = SyncService.ACTION_SEND_URIS
                intent.putStringArrayListExtra(SyncService.EXTRA_URIS, ArrayList(uris.map { it.toString() }))
            }
            !text.isNullOrBlank() -> {
                intent.action = SyncService.ACTION_SEND_CLIPBOARD_TEXT
                intent.putExtra(SyncService.EXTRA_TEXT, text)
            }
            else -> {
                finish()
                return
            }
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Отправка…", Toast.LENGTH_SHORT).show()
    }

    private fun queryName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else uri.lastPathSegment ?: "файл"
        } ?: (uri.lastPathSegment ?: "файл")
    } catch (_: Exception) {
        uri.lastPathSegment ?: "файл"
    }

    private fun querySize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && c.columnCount > 0) c.getLong(0) else 0L
        } ?: 0L
    } catch (_: Exception) {
        0L
    }
}

@Composable
private fun ShareConfirm(
    pcName: String,
    fileNames: List<String>,
    totalSize: Long,
    text: String?,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val conn by ServiceState.conn.collectAsState()
    val connected = conn is Conn.Connected
    val statusText = when (conn) {
        is Conn.Connected -> "ПК подключён"
        is Conn.Connecting -> "Подключение к ПК…"
        is Conn.AuthFailed -> "Авторизация отклонена"
        Conn.Disconnected -> "Нет соединения с ПК"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
        ) {
            Column(Modifier.padding(22.dp)) {
                Text("Отправить на $pcName", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (fileNames.isNotEmpty()) {
                    val title = fileNames.first()
                    Text(
                        text = if (fileNames.size == 1) title
                        else "$title и ещё ${fileNames.size - 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (totalSize > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            formatSize(totalSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!text.isNullOrBlank()) {
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel) { Text("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSend) { Text("Отправить") }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "%.0f КБ".format(bytes / (1L shl 10).toDouble())
    else -> "$bytes Б"
}
