package com.zerosynk.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.net.Api
import com.zerosynk.app.service.SyncService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Window into the PC" (p. 12): browses the computer's folders from the phone.
 * Tap a folder to enter it, tap a file to view it, «Скачать» saves it to the phone
 * through the same resumable/progress-notified pipeline as regular offers (p. 14).
 */
@Composable
fun FilesScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var path by rememberSaveable { mutableStateOf("") }
    var listing by remember { mutableStateOf<Api.ListResult?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        loading = true
        error = null
        val r = withContext(Dispatchers.IO) {
            if (prefs.host == null || prefs.token == null) null
            else Api.list(prefs.host, prefs.port, prefs.token, path, prefs.transferPassword)
        }
        loading = false
        when {
            r == null -> {
                listing = null
                error = if (prefs.host == null || prefs.token == null) {
                    "Телефон не соединён с ПК"
                } else {
                    "Не удалось связаться с ПК"
                }
            }
            !r.ok -> {
                listing = null
                error = "Папка недоступна"
            }
            else -> listing = r
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(52.dp))
        Text("Файлы ПК", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = path.ifEmpty { "Загрузок не выбрано — домашняя папка" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))

        val current = listing
        SectionCard {
            Column(Modifier.padding(vertical = 4.dp)) {
                if (loading) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Чтение папки…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                error?.let { msg ->
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }

                // ".." — one level up.
                if (current?.parent != null) {
                    FileRow(
                        glyph = "⬆",
                        title = "…",
                        subtitle = "Наверх",
                        onClick = { path = current.parent!! },
                    )
                }

                current?.dirs?.forEach { dir ->
                    FileRow(
                        glyph = "📁",
                        title = dir,
                        subtitle = "Папка",
                        onClick = { path = if (path.isEmpty()) dir else "$path/$dir" },
                    )
                }

                current?.files?.forEach { f ->
                    FileRow(
                        glyph = "📄",
                        title = f.name,
                        subtitle = "${fmtSize(f.size)} · ${fmtDate(f.mtime)}",
                        onClick = { openOnPhone(context, prefs, join(path, f.name)) },
                        onDownload = {
                            SyncService.requestPathDownload(context, join(path, f.name), f.name)
                            toast(context, "Скачивание: ${f.name}")
                        },
                    )
                }

                if (!loading && error == null && current != null &&
                    current.dirs.isEmpty() && current.files.isEmpty()
                ) {
                    Text(
                        "Папка пуста",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(140.dp))
    }
}

private fun join(dir: String, name: String) = if (dir.isEmpty()) name else "$dir/$name"

/** Opens the file in the phone's viewer/browser straight off the PC (p. 12). */
private fun openOnPhone(context: android.content.Context, prefs: Prefs, path: String) {
    val url = Api.rawUrl(prefs.host, prefs.port, prefs.token, path, prefs.transferPassword)
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        toast(context, "Нет приложения, чтобы открыть файл")
    }
}

@Composable
private fun FileRow(
    glyph: String,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(glyph, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onDownload != null) {
            TextButton(onClick = onDownload) {
                Text("Скачать", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private fun fmtSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024 -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}

private fun fmtDate(epochSeconds: Long): String = try {
    SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(epochSeconds * 1000))
} catch (_: Exception) {
    ""
}
