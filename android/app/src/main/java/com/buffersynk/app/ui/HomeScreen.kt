package com.buffersynk.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.buffersynk.app.clipboard.ClipboardSync
import com.buffersynk.app.data.LoopGuard
import com.buffersynk.app.data.Prefs
import com.buffersynk.app.service.Conn
import com.buffersynk.app.service.ServiceState
import com.buffersynk.app.service.SyncService
import com.buffersynk.app.util.Xiaomi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val conn by ServiceState.conn.collectAsState()
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
        Text("Главная", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))

        // ---- status card ----
        SectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val color = when {
                        !syncEnabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        conn is Conn.Connected -> com.buffersynk.app.ui.theme.Green
                        conn is Conn.Connecting -> com.buffersynk.app.ui.theme.Orange
                        conn is Conn.AuthFailed -> com.buffersynk.app.ui.theme.Red
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(color, CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
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
                Text(
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
                    Text(if (syncEnabled) "Остановить синхронизацию" else "Запустить синхронизацию")
                }

                if (prefs.syncEnabled && conn !is Conn.Connected && prefs.paired) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { SyncService.start(context) },
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Повторить подключение")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

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
                    Text("Отправить буфер на ПК")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Отправить файл на ПК")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // ---- background clipboard hint ----
        SectionCard {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Буфер в фоновом режиме", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Когда приложение открыто, копирование синхронизируется автоматически. " +
                        "В фоне Android запрещает приложениям читать буфер — поэтому в уведомлении ZeroSynk " +
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
                    Text(
                        "Чтобы ZeroSynk работал в фоне без обрывов, разрешите автозапуск и отключите ограничения батареи.",
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
        Text(text, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
    }
}
