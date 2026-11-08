package com.zerosynk.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.zerosynk.app.BuildConfig
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.data.SaveFolder
import com.zerosynk.app.design.Design
import com.zerosynk.app.net.PairingCtl
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.service.Conn
import com.zerosynk.app.service.ServiceState
import com.zerosynk.app.service.SyncService
import com.zerosynk.app.util.Xiaomi
import android.provider.Settings

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val cn = android.content.ComponentName(context, com.zerosynk.app.notif.NotifListenerService::class.java)
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
    var saveFolderLabel by remember { mutableStateOf(SaveFolder.statusLabel(context)) }
    var saveFolderCustom by remember { mutableStateOf(SaveFolder.isCustom(context)) }
    var pwText by remember { mutableStateOf(prefs.transferPassword ?: "") }
    val design by Design.flow.collectAsState()

    // SAF folder picker: choose where files sent from the PC are saved.
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            saveFolderLabel = SaveFolder.adopt(context, uri)
            saveFolderCustom = true
            toast(context, "Файлы с ПК будут сохраняться в «$saveFolderLabel»")
        }
    }

    val selectDesign: (String) -> Unit = { key ->
        if (key != Design.current) {
            Design.apply(context, key)
            // Tell the PC so every connected device switches together.
            SyncService.sendJson(Protocol.design(key))
        }
    }

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
                // Session password from the PC (p. 18): normally delivered by QR/PIN,
                // editable here when it was set on the PC after pairing.
                OutlinedTextField(
                    value = pwText,
                    onValueChange = { pwText = it },
                    label = { Text("Пароль на передачу") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Пусто — без пароля. Пароль задаётся на ПК и приходит вместе с подключением.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                )
                OutlinedButton(
                    onClick = {
                        prefs.transferPassword = pwText.trim().ifEmpty { null }
                        toast(
                            context,
                            if (pwText.isBlank()) "Пароль отключён" else "Пароль сохранён",
                        )
                        // Reconnect so the new password is used on the next handshake.
                        SyncService.stop(context)
                        SyncService.start(context)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text("Сохранить пароль")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        SaveFolder.reset(context) // don't leak the SAF grant across re-pairing
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

        SectionCard(title = "Оформление") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                DesignChip("Zero", "ZeroSynk", design == Design.ZERO, Modifier.weight(1f)) {
                    selectDesign(Design.ZERO)
                }
                DesignChip("Onyx", "OnyxSynk", design == Design.ONYX, Modifier.weight(1f)) {
                    selectDesign(Design.ONYX)
                }
                DesignChip("Eclipx", "Eclipx", design == Design.ECLIPX, Modifier.weight(1f)) {
                    selectDesign(Design.ECLIPX)
                }
            }
            Text(
                "Оформление меняется на всех подключённых устройствах — на ПК и на телефоне.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
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
                subtitle = "Файлы, отправленные с ПК, сохраняются в $saveFolderLabel",
                checked = autoDownload,
                onCheckedChange = { autoDownload = it; prefs.autoDownloadFiles = it },
            )
            // ---- where PC files land ----
            ValueRow("Папка для файлов с ПК", saveFolderLabel)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Text(if (saveFolderCustom) "Выбрать другую папку" else "Выбрать папку")
                }
                if (saveFolderCustom) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            SaveFolder.reset(context)
                            saveFolderLabel = SaveFolder.DEFAULT_LABEL
                            saveFolderCustom = false
                            toast(context, "Папка сброшена: ${SaveFolder.DEFAULT_LABEL}")
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Text("Вернуть Download/ZeroSynk")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Выберите любую папку на телефоне (например, «Фото» или карту памяти) — " +
                        "файлы с ПК будут сохраняться именно туда. Доступ не сгорает после перезагрузки.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
                subtitle = "Уведомление-плеер для музыки/видео на ПК (⏮ ⏸ ⏭, прогресс, seek) " +
                    "и медиа телефона в карточке на ПК",
                checked = mediaControls,
                onCheckedChange = { mediaControls = it; prefs.mediaControls = it },
            )
            if (mediaControls && !isNotificationListenerEnabled(context)) {
                Text(
                    "Чтобы ПК видел и управлял тем, что играет на телефоне, нужен «Доступ к " +
                        "уведомлениям» — то же разрешение, что и для зеркала уведомлений.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
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
            }
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
                        "Xiaomi / HyperOS: дополнительно разрешите автозапуск ${Design.displayName(design)} в «Безопасности» — иначе система будет убивать соединение.",
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

/** One selectable design option (Zero / Onyx / Eclipx). */
@Composable
private fun DesignChip(
    label: String,
    sub: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) scheme.primary else scheme.outline),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                scheme.primary.copy(alpha = 0.12f)
            } else {
                scheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) scheme.primary else scheme.onSurface,
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
