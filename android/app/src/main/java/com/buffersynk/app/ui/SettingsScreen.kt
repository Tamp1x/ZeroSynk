package com.buffersynk.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.buffersynk.app.BuildConfig
import com.buffersynk.app.data.Prefs
import com.buffersynk.app.net.PairingCtl
import com.buffersynk.app.service.Conn
import com.buffersynk.app.service.ServiceState
import com.buffersynk.app.service.SyncService
import com.buffersynk.app.util.Xiaomi
import android.provider.Settings

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val cn = android.content.ComponentName(context, com.buffersynk.app.notif.NotifListenerService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(cn.flattenToString())
}

@Composable
fun SettingsScreen(onRepaired: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val conn by ServiceState.conn.collectAsState()

    var autoApply by remember { mutableStateOf(prefs.autoApplyClipboard) }
    var autoDownload by remember { mutableStateOf(prefs.autoDownloadFiles) }
    var startOnBoot by remember { mutableStateOf(prefs.startOnBoot) }
    var mediaControls by remember { mutableStateOf(prefs.mediaControls) }
    var syncNotifs by remember { mutableStateOf(prefs.syncNotifs) }
    var mirrorWin by remember { mutableStateOf(prefs.mirrorWinNotifs) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(52.dp))
        Text("Настройки", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(16.dp))

        SectionCard(title = "Подключение") {
            ValueRow("ПК", prefs.pcName ?: "—")
            ValueRow("Адрес", prefs.host?.let { "$it:${prefs.port}" } ?: "—")
            ValueRow(
                "Статус",
                when (conn) {
                    is Conn.Connected -> "подключено"
                    is Conn.Connecting -> "подключение…"
                    is Conn.AuthFailed -> "ошибка авторизации"
                    Conn.Disconnected -> "нет подключения"
                },
            )
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedButton(
                    onClick = {
                        prefs.clear()
                        SyncService.stop(context)
                        PairingCtl.reset()
                        onRepaired()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Переподключиться к другому ПК")
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "Синхронизация") {
            SwitchRow(
                title = "Применять буфер с ПК",
                subtitle = "Копирование на ПК появится в буфере телефона",
                checked = autoApply,
                onCheckedChange = { autoApply = it; prefs.autoApplyClipboard = it },
            )
            SwitchRow(
                title = "Автоскачивать файлы",
                subtitle = "Файлы, отправленные с ПК, сохраняются в Download/ZeroSynk",
                checked = autoDownload,
                onCheckedChange = { autoDownload = it; prefs.autoDownloadFiles = it },
            )
            SwitchRow(
                title = "Запускать после перезагрузки",
                checked = startOnBoot,
                onCheckedChange = { startOnBoot = it; prefs.startOnBoot = it },
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "Медиа-плеер") {
            SwitchRow(
                title = "Управление медиа с телефона",
                subtitle = "Показывать уведомление-плеер для музыки/видео на ПК (кнопки ⏮ ⏸ ⏭)",
                checked = mediaControls,
                onCheckedChange = { mediaControls = it; prefs.mediaControls = it },
            )
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "Уведомления") {
            SwitchRow(
                title = "Уведомления телефона → ПК",
                subtitle = "Зеркалить уведомления мессенджеров и других приложений на ПК (требуется «Доступ к уведомлениям»)",
                checked = syncNotifs,
                onCheckedChange = { enabled ->
                    syncNotifs = enabled
                    prefs.syncNotifs = enabled
                    if (enabled && !isNotificationListenerEnabled(context)) {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                        )
                    }
                },
            )
            SwitchRow(
                title = "Уведомления Windows → телефон",
                subtitle = "Показывать уведомления с ПК на телефоне",
                checked = mirrorWin,
                onCheckedChange = { mirrorWin = it; prefs.mirrorWinNotifs = it },
            )
            if (syncNotifs) {
                val listenerEnabled = isNotificationListenerEnabled(context)
                if (!listenerEnabled) {
                    Button(
                        onClick = {
                            context.startActivity(
                                android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Разрешить доступ к уведомлениям")
                    }
                } else {
                    Text(
                        "Доступ к уведомлениям разрешён ✓",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "Уведомления и фон") {
            val notifEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
            val permGranted = Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!notifEnabled || !permGranted) {
                Button(
                    onClick = { Xiaomi.openAppSettings(context) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Разрешить уведомления")
                }
            }
            val ignoring = Xiaomi.ignoringBatteryOptimizations(context)
            if (!ignoring) {
                Button(
                    onClick = {
                        if (!Xiaomi.requestIgnoreBatteryOptimizations(context)) {
                            Xiaomi.openAppSettings(context)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Отключить оптимизацию батареи")
                }
            }
            if (Xiaomi.isXiaomi) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Text(
                        "Xiaomi / HyperOS: дополнительно разрешите автозапуск ZeroSynk в «Безопасности» — иначе система будет убивать соединение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { Xiaomi.openNotificationSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Уведомления приложения")
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { if (!Xiaomi.openAutoStart(context)) Xiaomi.openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть «Автозапуск»")
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { if (!Xiaomi.openHiddenApps(context)) Xiaomi.openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Открыть «Скрытые приложения»")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "О приложении") {
            ValueRow("Версия", BuildConfig.VERSION_NAME)
            ValueRow("Протокол", "v1")
        }

        Spacer(Modifier.height(110.dp))
    }
}
