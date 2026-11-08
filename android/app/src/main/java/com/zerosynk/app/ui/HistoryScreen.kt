package com.zerosynk.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zerosynk.app.clipboard.ClipboardSync
import com.zerosynk.app.data.ClipHistory
import com.zerosynk.app.data.NotifHistory
import com.zerosynk.app.service.ServiceState

@Composable
fun HistoryScreen() {
    val context = LocalContext.current
    val clipRev by ServiceState.clipRev.collectAsState()
    val notifRev by ServiceState.notifRev.collectAsState()
    val clips = remember(clipRev) { ClipHistory.list(context) }
    val notifs = remember(notifRev) { NotifHistory.list(context) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(52.dp))
        UiText("История", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))
        SectionCard(title = "Буфер обмена") {
            if (clips.isEmpty()) {
                UiText("Записей пока нет", modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            clips.forEach { entry ->
                Column(
                    Modifier.fillMaxWidth().clickable {
                        ClipboardSync.copyEntry(context, entry)
                        ServiceState.bumpClipRevision()
                        toast(context, "Скопировано в буфер")
                    }.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    UiText(if (entry.kind == "image") "Изображение" else entry.text.ifBlank { "Пустая запись" },
                        maxLines = 3, style = MaterialTheme.typography.bodyMedium)
                    UiText("${entry.kind} · нажмите, чтобы скопировать",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        SectionCard(title = "Уведомления") {
            if (notifs.isEmpty()) {
                UiText("Записей пока нет", modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            notifs.forEach { item ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    UiText(item.title.ifBlank { item.app }, style = MaterialTheme.typography.bodyLarge)
                    if (item.text.isNotBlank()) UiText(item.text, style = MaterialTheme.typography.bodyMedium)
                    UiText("${item.app} · ${if (item.origin == "pc") "ПК" else "телефон"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(92.dp))
    }
}
