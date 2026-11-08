package com.zerosynk.app.send

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
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.design.Design
import com.zerosynk.app.service.Conn
import com.zerosynk.app.service.ServiceState
import com.zerosynk.app.service.SyncService
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.ui.theme.Green
import com.zerosynk.app.ui.UiText
import com.zerosynk.app.ui.LocalUiEnglish
import com.zerosynk.app.ui.localize
import com.zerosynk.app.ui.theme.ZeroSynkTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Target of the system share sheet ("Поделиться" → ZeroSynk).
 * Accepts ACTION_SEND / ACTION_SEND_MULTIPLE (files, images, text) and shows a
 * small confirm dialog before pushing everything to the paired PC.
 */
class ShareTargetActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uris = LinkedHashSet<Uri>()
        // clipData first: the framework guarantees read grants through it,
        // and MIUI gallery puts the ORIGINALS here.
        intent.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let { uris.add(it) }
        }
        // Basenames already covered by clipData. MIUI gallery also converts HEIC->JPG
        // and puts those copies in EXTRA_STREAM — same photo, different URI. Skip them.
        val seenBase = HashSet<String>()
        for (u in uris) seenBase.add(baseName(u))
        // EXTRA_STREAM: some senders put the full list ONLY here —
        // as a single Uri, an ArrayList<Uri> or a Uri[] — leaving clipData empty/partial.
        // extras.get() instead of getParcelableExtra(): the latter CCEs on ArrayList.
        val streamExtra = try { intent.extras?.get(Intent.EXTRA_STREAM) } catch (_: Exception) { null }
        fun addStream(u: Uri) {
            val base = baseName(u)
            if (seenBase.add(base)) uris.add(u)
            else android.util.Log.d("ZeroSynk", "skip converted duplicate: $u")
        }
        when (streamExtra) {
            is Uri -> addStream(streamExtra)
            is Array<*> -> for (u in streamExtra) if (u is Uri) addStream(u)
            is Collection<*> -> for (u in streamExtra) if (u is Uri) addStream(u)
        }
        // Legacy single-file shape: everything lives in data
        if (uris.isEmpty()) intent.data?.let { uris.add(it) }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        android.util.Log.d(
            "ZeroSynk",
            "ShareTarget: action=${intent.action} clipItems=${intent.clipData?.itemCount ?: 0} " +
                "streamType=${streamExtra?.javaClass?.simpleName} " +
                "uris=${uris.size} data=${intent.data != null} text=${text != null}",
        )

        if (uris.isEmpty() && text.isNullOrBlank()) {
            finish()
            return
        }

        val prefs = Prefs.get(this)
        if (!prefs.paired) {
            Toast.makeText(this, localize("Сначала подключите ПК в ${Design.displayName()}", Prefs.get(this).language == "en"), Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val pcName = prefs.pcName ?: "ПК"
        val names = uris.map { queryName(it) }
        val totalSize = uris.sumOf { querySize(it) }

        var sending by mutableStateOf(false)

        setContent {
            ZeroSynkTheme {
                CompositionLocalProvider(LocalUiEnglish provides (prefs.language == "en")) {
                    ShareConfirm(
                        pcName = pcName,
                        fileNames = names,
                        totalSize = totalSize,
                        text = text,
                        sending = sending,
                        onSend = {
                            if (!sending) {
                                sending = true
                                lifecycleScope.launch {
                                    send(uris, text)
                                    finish()
                                }
                            }
                        },
                        onCancel = { if (!sending) finish() },
                    )
                }
            }
        }
    }

    private suspend fun send(uris: Collection<Uri>, text: String?) {
        val sharedText = text ?: intent.dataString
        val location = sharedText?.takeIf { it.trimStart().startsWith("geo:", ignoreCase = true) }
        if (location != null) {
            if (!SyncService.isConnected()) SyncService.start(this)
            SyncService.sendJson(Protocol.sharedLocation(location.trim()))
            Toast.makeText(this, localize("Местоположение отправлено на ПК", Prefs.get(this).language == "en"), Toast.LENGTH_SHORT).show()
            if (uris.isEmpty()) return
        }
        // Share-sheet grants are temporary and die with this Activity; persistable
        // grants are usually refused (no FLAG_GRANT_PERSISTABLE from senders).
        // So: try persistable first, otherwise copy the bytes into our cache
        // while the grant is still alive, and hand file:// URIs to the service.
        val handoff = ArrayList<Uri>()
        withContext(Dispatchers.IO) {
            for (uri in uris) {
                var persisted = false
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                    android.util.Log.d("ZeroSynk", "persistable grant OK: $uri")
                    persisted = true
                } catch (e: Exception) {
                    android.util.Log.w("ZeroSynk", "persistable grant FAILED: $uri (${e.javaClass.simpleName})")
                }
                if (persisted) {
                    handoff.add(uri)
                } else {
                    val copied = copyToCache(uri)
                    if (copied != null) handoff.add(copied)
                    else android.util.Log.e("ZeroSynk", "handoff skipped, unreadable: $uri")
                }
            }
        }
        android.util.Log.d("ZeroSynk", "send(): handing ${handoff.size} uri(s) to SyncService")
        val intent = Intent(this, SyncService::class.java)
        when {
            handoff.isNotEmpty() -> {
                intent.action = SyncService.ACTION_SEND_URIS
                intent.putStringArrayListExtra(SyncService.EXTRA_URIS, ArrayList(handoff.map { it.toString() }))
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
        Toast.makeText(this, localize("Отправка…", Prefs.get(this).language == "en"), Toast.LENGTH_SHORT).show()
    }

    private fun copyToCache(uri: Uri): Uri? {
        return try {
            val name = queryName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(cacheDir, "shared-out").apply { mkdirs() }
            dir.listFiles()?.forEach { f ->
                if (System.currentTimeMillis() - f.lastModified() > 86_400_000L) f.delete()
            }
            val dest = File(dir, "${System.currentTimeMillis()}_$name")
            val input = contentResolver.openInputStream(uri) ?: return null
            input.use { ins -> dest.outputStream().use { out -> ins.copyTo(out) } }
            android.util.Log.d("ZeroSynk", "cached copy: $dest (${dest.length()} bytes)")
            Uri.fromFile(dest)
        } catch (e: Exception) {
            android.util.Log.w("ZeroSynk", "cache copy FAILED: $uri (${e.javaClass.simpleName})")
            null
        }
    }

    /** "IMG_001.heic" -> "img_001" — used to match gallery originals with their converted copies. */
    private fun baseName(uri: Uri): String {
        val name = queryName(uri)
        return name.substringBeforeLast('.', name).lowercase()
    }

    private fun queryName(uri: Uri): String {
        val name = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && c.columnCount > 0) c.getString(0) else uri.lastPathSegment ?: "файл"
            } ?: (uri.lastPathSegment ?: "файл")
        } catch (_: Exception) {
            uri.lastPathSegment ?: "файл"
        }
        return if (contentResolver.getType(uri)?.contains("vcard", ignoreCase = true) == true &&
            !name.endsWith(".vcf", ignoreCase = true)) "$name.vcf" else name
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
    sending: Boolean = false,
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
                UiText("Отправить на $pcName", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
                if (fileNames.isNotEmpty()) {
                    val title = fileNames.first()
                    UiText(
                        text = if (fileNames.size == 1) title
                        else "$title и ещё ${fileNames.size - 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (totalSize > 0) {
                        Spacer(Modifier.height(4.dp))
                        UiText(
                            formatSize(totalSize),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (!text.isNullOrBlank()) {
                    UiText(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                UiText(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onCancel, enabled = !sending) { UiText("Отмена") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onSend, enabled = !sending) {
                        UiText(if (sending) "Отправка…" else "Отправить")
                    }
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
