package com.zerosynk.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.zerosynk.app.clipboard.ClipboardSync
import com.zerosynk.app.data.ClipEntry
import com.zerosynk.app.data.ClipHistory
import com.zerosynk.app.data.LoopGuard
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.design.Design
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.service.Conn
import com.zerosynk.app.service.MediaInfo
import com.zerosynk.app.service.ServiceState
import com.zerosynk.app.service.SyncService
import com.zerosynk.app.util.Xiaomi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val conn by ServiceState.conn.collectAsState()
    val appLabel = Design.flow.collectAsState().value
    var syncEnabled by remember { mutableStateOf(prefs.syncEnabled) }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val intent = Intent(context, SyncService::class.java).apply {
                action = SyncService.ACTION_SEND_FILE
                data = uri
            }
            ContextCompat.startForegroundService(context, intent)
            toast(context, "Отправка файла…")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(52.dp))
        UiText("Главная", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))

        // ---- status card ----
        SectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val color = when {
                        !syncEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        conn is Conn.Connected -> com.zerosynk.app.ui.theme.Green
                        conn is Conn.Connecting -> com.zerosynk.app.ui.theme.Orange
                        conn is Conn.AuthFailed -> com.zerosynk.app.ui.theme.Red
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    UiText(
                        text = when {
                            !syncEnabled -> "Синхронизация остановлена"
                            conn is Conn.Connected -> "Подключено к ${(conn as Conn.Connected).pcName}"
                            conn is Conn.Connecting -> "Подключение…"
                            conn is Conn.AuthFailed -> "Авторизация отклонена"
                            prefs.paired -> "Нет подключения"
                            else -> "Не подключено"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                val address = prefs.host?.let { "$it:${prefs.port}" } ?: "—"
                UiText(
                    text = when {
                        !syncEnabled -> "Синхронизация выключена — буфер и файлы не передаются"
                        conn is Conn.Connected -> "Адрес: $address"
                        conn is Conn.AuthFailed -> "На ПК были сброшены коды — переподключитесь"
                        else -> "Адрес ПК: $address"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))

                // Stop / start sync button
                Button(
                    onClick = {
                        syncEnabled = !syncEnabled
                        prefs.syncEnabled = syncEnabled
                        SyncService.setSyncEnabled(context, syncEnabled)
                        toast(context, if (syncEnabled) "Синхронизация запущена" else "Синхронизация остановлена")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = if (syncEnabled) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    UiText(if (syncEnabled) "Остановить синхронизацию" else "Запустить синхронизацию")
                }

                if (prefs.syncEnabled && conn !is Conn.Connected && prefs.paired) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { SyncService.start(context) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        UiText("Повторить подключение")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- what the PC plays right now (reverse media control) ----
        val media by ServiceState.media.collectAsState()
        media?.let { NowPlayingCard(it, context) }

        // ---- quick actions ----
        SectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Button(
                    onClick = {
                        scope.launch(Dispatchers.Default) {
                            val msg = ClipboardSync.readClipboardMessage(context)
                            withContext(Dispatchers.Main) {
                                if (msg == null) {
                                    toast(context, "Буфер пуст")
                                } else {
                                    ServiceState.bumpClipRevision()
                                    LoopGuard.lastSentHash = msg.hash
                                    val ok = SyncService.sendJson(msg)
                                    toast(
                                        context,
                                        when {
                                            ok && msg.kind == "image" -> "Изображение отправлено на ПК"
                                            ok -> "Текст отправлен на ПК"
                                            else -> "Нет подключения к ПК"
                                        },
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    UiText("Отправить буфер на ПК")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    UiText("Отправить файл на ПК")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- clipboard history (p. 7): tap = copy back, long press = send to the PC ----
        val clipRev by ServiceState.clipRev.collectAsState()
        val clipEntries = remember(clipRev) { ClipHistory.list(context) }
        if (clipEntries.isNotEmpty()) {
            SectionCard(title = "История буфера") {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    clipEntries.take(8).forEach { entry ->
                        ClipHistoryRow(
                            entry = entry,
                            context = context,
                            onTap = {
                                ClipboardSync.copyEntry(context, entry)
                                ServiceState.bumpClipRevision()
                                toast(context, "Скопировано в буфер")
                            },
                            onLongTap = {
                                val msg = ClipboardSync.messageForEntry(context, entry)
                                val ok = msg != null && SyncService.sendJson(msg)
                                if (ok) LoopGuard.lastSentHash = msg?.hash
                                toast(
                                    context,
                                    if (ok) "Отправлено на ПК" else "Нет подключения к ПК",
                                )
                            },
                        )
                    }
                    UiText(
                        "Тап — копировать, долгий тап — отправить на ПК",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- background clipboard hint ----
        SectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                UiText("Буфер в фоновом режиме", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                UiText(
                    "Когда приложение открыто, копирование синхронизируется автоматически. " +
                        "В фоне Android запрещает приложениям читать буфер — поэтому в уведомлении $appLabel " +
                        "есть кнопка «Отправить буфер»: скопируйте текст и нажмите её — он мгновенно уйдёт на ПК. " +
                        "Получение буфера с ПК работает всегда, даже в фоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // ---- Xiaomi / battery card ----
        if (Xiaomi.isXiaomi || !Xiaomi.ignoringBatteryOptimizations(context)) {
            Spacer(Modifier.height(14.dp))
            SectionCard(title = if (Xiaomi.isXiaomi) "Xiaomi / HyperOS" else "Стабильность в фоне") {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    UiText(
                        "Чтобы $appLabel работал в фоне без обрывов, разрешите автозапуск и отключите ограничения батареи.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (Xiaomi.isXiaomi) {
                        GuideButton("Уведомления приложения") {
                            Xiaomi.openNotificationSettings(context)
                        }
                        GuideButton("Автозапуск приложения") {
                            if (!Xiaomi.openAutoStart(context)) Xiaomi.openAppSettings(context)
                        }
                        GuideButton("Скрытые приложения / фон") {
                            if (!Xiaomi.openHiddenApps(context)) Xiaomi.openAppSettings(context)
                        }
                        GuideButton("Энергосбережение") {
                            if (!Xiaomi.openBatterySaver(context)) Xiaomi.openAppSettings(context)
                        }
                    }
                    if (!Xiaomi.ignoringBatteryOptimizations(context)) {
                        GuideButton("Отключить оптимизацию батареи") {
                            if (!Xiaomi.requestIgnoreBatteryOptimizations(context)) {
                                Xiaomi.openAppSettings(context)
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun GuideButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        UiText(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}

/** One clipboard-history row: tap copies it back, long press sends it to the PC. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipHistoryRow(
    entry: ClipEntry,
    context: Context,
    onTap: () -> Unit,
    onLongTap: () -> Unit,
) {
    val label = if (entry.kind == "image") "🖼 ${entry.text}" else entry.text
    UiText(
        text = label,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongTap)
            .padding(vertical = 8.dp),
    )
}

/** "Now playing on the PC" card: cover, progress and remote controls. */
@Composable
private fun NowPlayingCard(media: MediaInfo, context: Context) {
    SectionCard(title = "Играет на ПК") {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val cover = remember(media.cover) { media.cover?.let(::decodeCover) }
                if (cover != null) {
                    Image(
                        bitmap = cover,
                        contentDescription = null,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp)),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    UiText(
                        text = media.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val sub = listOf(media.artist, media.app)
                        .filter { it.isNotBlank() }
                        .joinToString(" — ")
                    if (sub.isNotEmpty()) {
                        UiText(
                            text = sub,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (media.duration > 0) {
                Spacer(Modifier.height(12.dp))
                val progress = (media.position.toFloat() / media.duration.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    UiText(
                        text = fmtTime(media.position),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    UiText(
                        text = fmtTime(media.duration),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MediaCmd("−10 с", context, "seekBack", Modifier.weight(1f))
                MediaCmd("⏮", context, "prev", Modifier.weight(1f))
                MediaCmd(
                    if (media.state == "playing") "⏸" else "▶",
                    context, "toggle", Modifier.weight(1f),
                )
                MediaCmd("⏭", context, "next", Modifier.weight(1f))
                MediaCmd("+10 с", context, "seekFwd", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MediaCmd("Громкость −", context, "volDown", Modifier.weight(1f))
                MediaCmd("Стоп", context, "stop", Modifier.weight(1f))
                MediaCmd("Громкость +", context, "volUp", Modifier.weight(1f))
                MediaCmd("Папка трека", context, "openFolder", Modifier.weight(1f))
            }
        }
    }
}

/** One remote-control button; toasts when the PC is unreachable. */
@Composable
private fun MediaCmd(label: String, context: Context, action: String, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = {
            val ok = SyncService.sendJson(Protocol.mediaControl(action))
            if (!ok) toast(context, "Нет подключения к ПК")
        },
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
    ) {
        UiText(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

private fun fmtTime(sec: Long): String {
    val m = sec / 60
    val s = (sec % 60).toString().padStart(2, '0')
    return "$m:$s"
}

/** Base64 album art -> bitmap for the card (decoded only when the cover changes). */
private fun decodeCover(b64: String): ImageBitmap? = try {
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
} catch (_: Exception) {
    null
}
