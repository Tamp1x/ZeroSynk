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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import android.media.projection.MediaProjectionManager
import android.app.Activity
import androidx.core.content.ContextCompat
import com.zerosynk.app.send.ScreenshotSyncService
import com.zerosynk.app.update.ReleaseUpdater
import com.zerosynk.app.update.UpdateCheck
import kotlinx.coroutines.launch

private fun isNotificationListenerEnabled(context: android.content.Context): Boolean {
    val cn = android.content.ComponentName(context, com.zerosynk.app.notif.NotifListenerService::class.java)
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: ""
    return flat.contains(cn.flattenToString())
}

@Composable
fun SettingsScreen(onRepaired: () -> Unit, onLanguageChanged: (Boolean) -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    val conn by ServiceState.conn.collectAsState()
    val lastEvent by ServiceState.lastEvent.collectAsState()
    val pingMs by ServiceState.pingMs.collectAsState()
    var showDiagnostics by remember { mutableStateOf(false) }
    var showWhatsNew by remember { mutableStateOf(prefs.lastWhatsNewVersion != BuildConfig.VERSION_NAME) }
    var language by remember { mutableStateOf(prefs.language) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var updateRelease by remember { mutableStateOf<com.zerosynk.app.update.ReleaseInfo?>(null) }
    var checkingUpdate by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    var autoApply by remember { mutableStateOf(prefs.autoApplyClipboard) }
    var autoDownload by remember { mutableStateOf(prefs.autoDownloadFiles) }
    var startOnBoot by remember { mutableStateOf(prefs.startOnBoot) }
    var mediaControls by remember { mutableStateOf(prefs.mediaControls) }
    var syncNotifs by remember { mutableStateOf(prefs.syncNotifs) }
    var mirrorWin by remember { mutableStateOf(prefs.mirrorWinNotifs) }
    var screenshotSync by remember { mutableStateOf(prefs.screenshotSync) }
    var notifMode by remember { mutableStateOf(prefs.notifMode) }
    var notifApps by remember { mutableStateOf(prefs.notifApps) }
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

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val capture = android.content.Intent(context, ScreenshotSyncService::class.java).apply {
                putExtra(ScreenshotSyncService.EXTRA_RESULT, result.resultCode)
                putExtra(ScreenshotSyncService.EXTRA_DATA, result.data)
            }
            ContextCompat.startForegroundService(context, capture)
            screenshotSync = true
            prefs.screenshotSync = true
        } else {
            screenshotSync = false
            prefs.screenshotSync = false
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
        UiText("Настройки", style = MaterialTheme.typography.headlineLarge)
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
                    label = { UiText("Пароль на передачу") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                UiText(
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
                    UiText("Сохранить пароль")
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
                    UiText("Переподключиться к другому ПК")
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
            UiText(
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
            SwitchRow(
                title = "Отправлять скриншоты на ПК",
                subtitle = "Выкл. по умолчанию · Android спросит разрешение на захват экрана; снимок отправляется раз в 10 минут",
                checked = screenshotSync,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        val manager = context.getSystemService(MediaProjectionManager::class.java)
                        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
                    } else {
                        context.stopService(android.content.Intent(context, ScreenshotSyncService::class.java))
                        screenshotSync = false
                        prefs.screenshotSync = false
                    }
                },
            )
            // ---- where PC files land ----
            ValueRow("Папка для файлов с ПК", saveFolderLabel)
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    UiText(if (saveFolderCustom) "Выбрать другую папку" else "Выбрать папку")
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
                        UiText("Вернуть Download/ZeroSynk")
                    }
                }
                Spacer(Modifier.height(6.dp))
                UiText(
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
                UiText(
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
                    UiText("Разрешить доступ к уведомлениям")
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
            UiText(
                "Фильтр для зеркалирования",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
            )
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("all" to "Все", "important" to "Важные").forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { notifMode = value; prefs.notifMode = value },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) { UiText(if (notifMode == value) "✓ $label" else label) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("allow" to "Только список", "block" to "Кроме списка").forEach { (value, label) ->
                        OutlinedButton(
                            onClick = { notifMode = value; prefs.notifMode = value },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                        ) { UiText(if (notifMode == value) "✓ $label" else label) }
                    }
                }
                if (notifMode == "allow" || notifMode == "block") {
                    OutlinedTextField(
                        value = notifApps,
                        onValueChange = { notifApps = it; prefs.notifApps = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { UiText("Приложения") },
                        supportingText = { UiText("Package ID через запятую, например com.whatsapp") },
                        minLines = 1,
                        maxLines = 3,
                    )
                }
                if (notifMode == "important") {
                    UiText(
                        "Важность определяется по уровню уведомления Android; уведомления Windows считаются важными.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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
                        UiText("Разрешить доступ к уведомлениям")
                    }
                } else {
                    UiText(
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
                    UiText("Разрешить уведомления")
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
                    UiText("Отключить оптимизацию батареи")
                }
            }
            if (Xiaomi.isXiaomi) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    UiText(
                        "Xiaomi / HyperOS: дополнительно разрешите автозапуск ${Design.displayName(design)} в «Безопасности» — иначе система будет убивать соединение.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    androidx.compose.material3.TextButton(
                        onClick = { Xiaomi.openNotificationSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        UiText("Уведомления приложения")
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { if (!Xiaomi.openAutoStart(context)) Xiaomi.openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        UiText("Открыть «Автозапуск»")
                    }
                    androidx.compose.material3.TextButton(
                        onClick = { if (!Xiaomi.openHiddenApps(context)) Xiaomi.openAppSettings(context) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        UiText("Открыть «Скрытые приложения»")
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        SectionCard(title = "О приложении") {
            ValueRow("Версия", BuildConfig.VERSION_NAME)
            ValueRow("Протокол", "v1")
            UiText("Язык / Language", style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ru" to "Русский", "en" to "English").forEach { (code, label) ->
                    OutlinedButton(
                        onClick = {
                            language = code
                            prefs.language = code
                            onLanguageChanged(code == "en")
                        },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp),
                    ) { UiText(if (language == code) "✓ $label" else label) }
                }
            }
            OutlinedButton(
                onClick = { showWhatsNew = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
            ) { UiText("Что нового") }
            OutlinedButton(
                onClick = {
                    checkingUpdate = true
                    scope.launch {
                        when (val result = ReleaseUpdater.check()) {
                            is UpdateCheck.Available -> {
                                updateRelease = result.release
                                updateMessage = if (result.release.apkUrl == null)
                                    "Найдена версия ${result.release.version}, но в релизе нет APK. Открыть страницу релиза?"
                                else "Доступна версия ${result.release.version}. Скачать и установить?"
                            }
                            UpdateCheck.UpToDate -> updateMessage = "Установлена последняя версия."
                            is UpdateCheck.Error -> updateMessage = result.message
                        }
                        checkingUpdate = false
                    }
                },
                enabled = !checkingUpdate,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
            ) { UiText(if (checkingUpdate) "Проверка…" else "Проверить обновление") }
            OutlinedButton(
                onClick = { showDiagnostics = true },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
            ) { UiText("Диагностика") }
        }

        if (showDiagnostics) {
            AlertDialog(
                onDismissRequest = { showDiagnostics = false },
                title = { UiText("Диагностика ZeroSynk") },
                text = {
                    UiText(
                        "Соединение: ${when (conn) {
                            is Conn.Connected -> "подключено"
                            is Conn.Connecting -> "подключение"
                            is Conn.AuthFailed -> "ошибка авторизации"
                            else -> "нет соединения"
                        }}\nПК: ${prefs.host ?: "—"}:${prefs.port}\n" +
                            "Протокол: v1\nПинг: ${pingMs?.let { "$it мс" } ?: "—"}\nПоследнее событие: ${lastEvent ?: "—"}",
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showDiagnostics = false }) {
                        UiText("Закрыть")
                    }
                },
            )
        }
        if (updateMessage != null) {
            val release = updateRelease
            AlertDialog(
                onDismissRequest = { updateMessage = null; updateRelease = null },
                title = { UiText(if (release == null) "Обновление" else "ZeroSynk ${release.version}") },
                text = { UiText(updateMessage.orEmpty()) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        updateMessage = null
                        if (release != null) scope.launch {
                            if (release.apkUrl == null) {
                                context.startActivity(android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(release.pageUrl),
                                ))
                            } else {
                                val downloaded = ReleaseUpdater.download(context, release)
                                downloaded.onSuccess { ReleaseUpdater.install(context, it) }
                                    .onFailure { updateMessage = it.message ?: "Не удалось скачать APK." }
                            }
                            updateRelease = null
                        }
                    }) { UiText(if (release == null) "Закрыть" else if (release.apkUrl == null) "Открыть релиз" else "Установить") }
                },
                dismissButton = if (release != null) ({
                    androidx.compose.material3.TextButton(onClick = { updateMessage = null; updateRelease = null }) { UiText("Отмена") }
                }) else null,
            )
        }
        if (showWhatsNew) {
            AlertDialog(
                onDismissRequest = {
                    showWhatsNew = false
                    prefs.lastWhatsNewVersion = BuildConfig.VERSION_NAME
                },
                title = { UiText("Что нового · ${BuildConfig.VERSION_NAME}") },
                text = {
                    UiText("История буфера и уведомлений, файловый менеджер ПК, возобновление передач, " +
                        "управление медиа с телефона, экранные фильтры уведомлений, виджет и быстрая плитка.")
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showWhatsNew = false
                        prefs.lastWhatsNewVersion = BuildConfig.VERSION_NAME
                    }) { UiText("Понятно") }
                },
            )
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
            UiText(
                label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) scheme.primary else scheme.onSurface,
            )
            UiText(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}
