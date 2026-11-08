package com.zerosynk.app.ui

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit

val LocalUiEnglish = compositionLocalOf { false }

private val english = mapOf(
    "Главная" to "Home", "Файлы" to "Files", "Файлы ПК" to "PC files", "История" to "History",
    "Настройки" to "Settings", "Подключение" to "Connection", "Статус" to "Status",
    "подключено" to "Connected", "Не подключено" to "Disconnected", "Нет подключения" to "No connection",
    "ошибка авторизации" to "Authentication error", "Подключение…" to "Connecting…",
    "Подключение к ПК…" to "Connecting to PC…", "ПК" to "PC", "Адрес" to "Address",
    "Адрес ПК" to "PC address", "Порт" to "Port", "PIN-код с ПК" to "PIN from PC",
    "Подключить" to "Connect", "Сканировать QR-код" to "Scan QR code",
    "Ввести адрес и PIN вручную" to "Enter address and PIN manually", "Наверх" to "Up",
    "Скачать" to "Download", "Папка пуста" to "Folder is empty", "Папка" to "Folder",
    "Чтение папки…" to "Reading folder…", "История буфера" to "Clipboard history",
    "Буфер обмена" to "Clipboard", "Уведомления" to "Notifications", "Записей пока нет" to "No entries yet",
    "Изображение" to "Image", "Пустая запись" to "Empty entry", "Скопировано в буфер" to "Copied to clipboard",
    "Тап — копировать, долгий тап — отправить на ПК" to "Tap to copy · long press to send to PC",
    "Буфер в фоновом режиме" to "Background clipboard", "Синхронизация" to "Sync",
    "Применять буфер с ПК" to "Apply clipboard from PC", "Копирование на ПК появится в буфере телефона" to "Copies on the PC appear on the phone",
    "Автоскачивать файлы" to "Download files automatically", "Отправлять скриншоты на ПК" to "Send screenshots to PC",
    "Оформление" to "Appearance", "Медиа-плеер" to "Media player", "Управление медиа с телефона" to "Control phone media from PC",
    "Уведомления телефона → ПК" to "Phone notifications → PC", "Уведомления Windows → телефон" to "Windows notifications → phone",
    "Фильтр для зеркалирования" to "Mirroring filter", "Все" to "All", "Важные" to "Important",
    "Только список" to "Allow list only", "Кроме списка" to "Block list", "Приложения" to "Apps",
    "Package ID через запятую, например com.whatsapp" to "Package IDs, comma-separated, e.g. com.whatsapp",
    "Версия" to "Version", "Протокол" to "Protocol", "Диагностика" to "Diagnostics",
    "Диагностика ZeroSynk" to "ZeroSynk diagnostics", "Закрыть" to "Close",
    "Что нового" to "What's new", "Проверить обновление" to "Check for updates", "Понятно" to "Got it",
    "Перейти в приложение" to "Continue to app", "Подключено — вот что можно делать" to "Connected — here's what you can do",
    "Записей пока нет" to "No entries yet", "Пусто — без пароля. Пароль задаётся на ПК и приходит вместе с подключением." to "Leave empty for no password. The PC password is sent during pairing.",
    "Пароль на передачу" to "Transfer password", "Сохранить пароль" to "Save password", "Пароль сохранён" to "Password saved",
    "Пароль отключён" to "Password disabled", "Синхронизация остановлена" to "Sync is paused",
    "Синхронизация запущена" to "Sync started", "Запустить синхронизацию" to "Start sync",
    "Остановить синхронизацию" to "Stop sync", "Переподключиться к другому ПК" to "Pair with another PC",
    "Нет подключения к ПК" to "No PC connection", "Попробовать снова" to "Try again", "Отправить файл на ПК" to "Send file to PC",
    "Отправка файла…" to "Sending file…", "Отправлено на ПК" to "Sent to PC", "Отправка…" to "Sending…",
    "Отмена" to "Cancel", "Отправить" to "Send", "Отправить на" to "Send to", "Изображение отправлено на ПК" to "Image sent to PC",
    "Текст отправлен на ПК" to "Text sent to PC", "Местоположение отправлено на ПК" to "Location sent to PC",
    "Нет приложения, чтобы открыть файл" to "No app can open this file", "О приложении" to "About",
    "Уведомления приложения" to "App notifications", "Открыть «Автозапуск»" to "Open Auto-start",
    "Открыть «Скрытые приложения»" to "Open hidden apps", "Энергосбережение" to "Battery saver",
    "Отключить оптимизацию батареи" to "Disable battery optimization", "Стабильность в фоне" to "Background reliability",
    "Скрытые приложения / фон" to "Hidden apps / background", "Автозапуск приложения" to "App auto-start",
    "Выбрать папку" to "Choose folder", "Выбрать другую папку" to "Choose another folder",
    "Вернуть Download/ZeroSynk" to "Restore Download/ZeroSynk", "Папка недоступна" to "Folder unavailable",
    "Папка сброшена: Download/ZeroSynk" to "Folder reset: Download/ZeroSynk", "Папка для файлов с ПК" to "Folder for files from PC",
    "Файлы, отправленные с ПК, сохраняются в Download/ZeroSynk" to "Files from the PC are saved to Download/ZeroSynk",
    "Подключение" to "Connection", "Уведомления и фон" to "Notifications and background",
    "Зеркалить уведомления мессенджеров и других приложений на ПК (требуется «Доступ к уведомлениям»)" to "Mirror app notifications to the PC (notification access required)",
    "Показывать уведомления с ПК на телефоне" to "Show PC notifications on the phone",
    "Разрешить доступ к уведомлениям" to "Grant notification access", "Разрешить уведомления" to "Allow notifications",
    "Доступ к уведомлениям разрешён ✓" to "Notification access granted ✓", "Сначала подключите ПК" to "Pair with a PC first",
    "Синхронизация выключена — буфер и файлы не передаются" to "Sync is off — clipboard and files are not transferred",
    "Телефон не соединён с ПК" to "Phone is not connected to the PC", "Подключено к" to "Connected to",
    "Скачать файлы автоматически" to "Download files automatically", "Отправить буфер на ПК" to "Send clipboard to PC",
    "Буфер пуст" to "Clipboard is empty", "Последнее: изображение" to "Latest: image", "ПК подключён" to "PC connected",
    "ПК не в сети" to "PC offline", "Сначала подключите ПК" to "Pair a PC first",
    "Выкл. по умолчанию · Android спросит разрешение на захват экрана; снимок отправляется раз в 10 минут" to "Off by default · Android asks before screen capture; a screenshot is sent every 10 minutes",
    "Важность определяется по уровню уведомления Android; уведомления Windows считаются важными." to "Importance follows Android notification priority; Windows notifications are treated as important.",
    "Применять буфер с ПК" to "Apply PC clipboard", "Отправлять буфер" to "Send clipboard",
    "Оформление меняется на всех подключённых устройствах — на ПК и на телефоне." to "Appearance is synced across the PC and phone.",
    "Русский" to "Russian", "✓ Русский" to "✓ Russian", "✓ English" to "✓ English",
    "Адрес ПК: \$address" to "PC address: \$address", "Адрес: \$address" to "Address: \$address",
    "Скачивание: ${'$'}{f.name}" to "Downloading: ${'$'}{f.name}",
    "Подключено к ${'$'}{(conn as Conn.Connected).pcName}" to "Connected to ${'$'}{(conn as Conn.Connected).pcName}",
    "Что нового · ${'$'}{BuildConfig.VERSION_NAME}" to "What's new · ${'$'}{BuildConfig.VERSION_NAME}",
    "Язык / Language" to "Language", "Авторизация отклонена" to "Authentication rejected",
    "Запускать после перезагрузки" to "Start after reboot", "Запустить синхронизацию" to "Start sync",
    "и медиа телефона в карточке на ПК" to "and phone media on the PC card", "Играет на ПК" to "Playing on PC",
    "Загрузок не выбрано — домашняя папка" to "No folder selected — home folder",
    "На ПК были сброшены коды — переподключитесь" to "PC pairing codes were reset — pair again",
    "Не удалось связаться с ПК" to "Could not reach the PC", "Повторить подключение" to "Reconnect",
    "Отправлять буфер" to "Send clipboard", "Отправка файла…" to "Sending file…",
    "Отправляйте буфер и файлы с телефона, просматривайте файлы ПК, " to "Send clipboard and files from your phone, browse PC files, ",
    "управление медиа с телефона, экранные фильтры уведомлений, виджет и быстрая плитка." to "control phone media, filter notifications, and use the widget and Quick Settings tile.",
    "управляйте музыкой и настройте зеркалирование уведомлений в параметрах." to "control music and configure notification mirroring in Settings.",
    "Отправить на" to "Send to", "Отмена" to "Cancel", "Отправить" to "Send",
    "Подключено к" to "Connected to", "Не подключено" to "Disconnected", "нет подключения" to "disconnected",
    "подключение…" to "connecting…", "Стоп" to "Stop", "Громкость +" to "Volume +", "Громкость −" to "Volume −",
    "+10 с" to "+10 s", "−10 с" to "−10 s", "Наверх" to "Up",
    "Буфер обмена и файлы между ПК и телефоном\nв одной локальной сети" to "Clipboard and files between your PC and phone\non your local network",
    "На вкладке «Главная» показан QR-код и 6-значный PIN-код." to "The Home tab shows a QR code and a 6-digit PIN.",
    "1. На ПК откройте \$appLabel" to "1. Open \$appLabel on your PC",
    "2. Подключите телефон" to "2. Pair your phone",
    "Наведите камеру на QR-код на экране ПК" to "Point the camera at the QR code on your PC",
    "Выберите любую папку на телефоне (например, «Фото» или карту памяти) — " to "Choose a folder on your phone (for example, Photos or an SD card) — ",
    "файлы с ПК будут сохраняться именно туда. Доступ не сгорает после перезагрузки." to "files from your PC will be saved there. Access remains after reboot.",
    "Чтобы ПК видел и управлял тем, что играет на телефоне, нужен «Доступ к " to "To show and control phone playback on the PC, grant notification access",
    "уведомлениям» — то же разрешение, что и для зеркала уведомлений." to "— the same permission used for notification mirroring.",
    "Когда приложение открыто, копирование синхронизируется автоматически. " to "When the app is open, clipboard changes sync automatically. ",
    "В фоне Android запрещает приложениям читать буфер — поэтому в уведомлении \$appLabel " to "Android blocks background clipboard reads, so \$appLabel notification has a ",
    "есть кнопка «Отправить буфер»: скопируйте текст и нажмите её — он мгновенно уйдёт на ПК. " to "Send clipboard button: copy text and tap it to send it to your PC. ",
    "Получение буфера с ПК работает всегда, даже в фоне." to "Receiving clipboard from the PC works in the background too.",
    "Уведомление-плеер для музыки/видео на ПК (⏮ ⏸ ⏭, прогресс, seek) " to "Media controls for music/video playing on the PC (⏮ ⏸ ⏭, progress, seek) ",
    "Чтобы \$appLabel работал в фоне без обрывов, разрешите автозапуск и отключите ограничения батареи." to "Allow auto-start and remove battery restrictions so \$appLabel can stay connected in the background.",
    "Xiaomi / HyperOS: дополнительно разрешите автозапуск ${'$'}{Design.displayName(design)} в «Безопасности» — иначе система будет убивать соединение." to "Xiaomi / HyperOS: also allow ${'$'}{Design.displayName(design)} to auto-start in Security, or the system may stop the connection.",
    "${'$'}{entry.kind} · нажмите, чтобы скопировать" to "${'$'}{entry.kind} · tap to copy",
    "Папка сброшена: Download/ZeroSynk" to "Folder reset: Download/ZeroSynk",
    "Файлы с ПК будут сохраняться в «\$saveFolderLabel»" to "Files from the PC will be saved in “\$saveFolderLabel”",
    "Файлы, отправленные с ПК, сохраняются в \$saveFolderLabel" to "Files sent from the PC are saved in \$saveFolderLabel",
    "Диагностика ZeroSynk" to "ZeroSynk diagnostics",
    "Тап — копировать, долгий тап — отправить на ПК" to "Tap to copy; long-press to send to PC",
    "Доступ к уведомлениям разрешён ✓" to "Notification access granted ✓",
    "Выкл. по умолчанию · Android спросит разрешение на захват экрана; снимок отправляется раз в 10 минут" to "Off by default · Android asks before screen capture; a screenshot is sent every 10 minutes",
    "Изображение отправлено на ПК" to "Image sent to PC", "Местоположение отправлено на ПК" to "Location sent to PC",
    "Подключено — вот что можно делать" to "Connected — here's what you can do",
    "Синхронизация выключена — буфер и файлы не передаются" to "Sync is off — clipboard and files are not transferred",
    "Пусто — без пароля. Пароль задаётся на ПК и приходит вместе с подключением." to "Leave empty for no password. The PC password is sent during pairing.",
    "Синхронизация запущена" to "Sync started", "Синхронизация остановлена" to "Sync stopped",
    "Отправлено на ПК" to "Sent to PC", "Скопировано в буфер" to "Copied to clipboard",
    "Файлы, отправленные с ПК, сохраняются в Download/ZeroSynk" to "Files from the PC are saved to Download/ZeroSynk",
    "Отправить буфер на ПК" to "Send clipboard to PC", "Последнее: изображение" to "Latest: image",
    "ПК подключён" to "PC connected", "ПК не в сети" to "PC offline", "Сначала подключите ПК" to "Pair with a PC first",
)

fun localize(value: String, useEnglish: Boolean): String {
    if (!useEnglish) return value
    if (value.startsWith("Подключено к ")) return "Connected to " + value.removePrefix("Подключено к ")
    if (value.startsWith("Скачивание: ")) return "Downloading: " + value.removePrefix("Скачивание: ")
    if (value.startsWith("Файлы с ПК будут сохраняться в «")) return "Files from the PC will be saved in “" + value.removePrefix("Файлы с ПК будут сохраняться в «").removeSuffix("»") + "”"
    if (value.startsWith("Файлы, отправленные с ПК, сохраняются в ")) return "Files sent from the PC are saved in " + value.removePrefix("Файлы, отправленные с ПК, сохраняются в ")
    return english[value] ?: value
}

@Composable
fun UiText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    MaterialText(
        text = localize(text, LocalUiEnglish.current), modifier = modifier, color = color,
        fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight, fontFamily = fontFamily,
        letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign,
        lineHeight = lineHeight, overflow = overflow, softWrap = softWrap, maxLines = maxLines,
        minLines = minLines, onTextLayout = onTextLayout, style = style,
    )
}
