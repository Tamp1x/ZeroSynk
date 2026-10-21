package com.buffersynk.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.buffersynk.app.App
import com.buffersynk.app.MainActivity
import com.buffersynk.app.R
import com.buffersynk.app.clipboard.ClipboardSync
import com.buffersynk.app.data.Prefs
import com.buffersynk.app.net.Api
import com.buffersynk.app.net.ProtoJson
import com.buffersynk.app.net.Protocol
import com.buffersynk.app.net.WsMessage
import com.buffersynk.app.send.SendClipboardActivity
import com.buffersynk.app.send.SendFileActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.min

class SyncService : Service() {

    private lateinit var prefs: Prefs
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    private val wsClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var ws: WebSocket? = null
    private var reconnectAttempt = 0

    @Volatile
    private var shouldRun = true

    private var startedForeground = false
    private var pingRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs.get(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()

        when (intent?.action) {
            ACTION_SEND_FILE -> {
                val uri = intent.data ?: return START_STICKY
                // Own batch per single file: PC cleans older files when it arrives
                val batch = java.util.UUID.randomUUID().toString()
                scope.launch(Dispatchers.IO) { uploadBatch(listOf(uri), batch) }
            }
            ACTION_SEND_URIS -> {
                val list = intent.getStringArrayListExtra(EXTRA_URIS).orEmpty()
                android.util.Log.d("ZeroSynk", "SEND_URIS: received ${list.size} uri(s)")
                if (list.isNotEmpty()) {
                    // One batch for the whole multi-file send so the PC keeps all of them
                    val batch = java.util.UUID.randomUUID().toString()
                    val uris = list.map { Uri.parse(it) }
                    scope.launch(Dispatchers.IO) { uploadBatch(uris, batch) }
                }
            }
            ACTION_SEND_CLIPBOARD_TEXT -> {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (text != null) sendClipboardText(text)
            }
            ACTION_MEDIA -> {
                val action = intent.getStringExtra(EXTRA_MEDIA_ACTION)
                if (action != null) {
                    sendJson(Protocol.mediaControl(action))
                }
            }
            ACTION_SET_SYNC -> {
                val enabled = intent.getBooleanExtra(EXTRA_SYNC_ENABLED, true)
                prefs.syncEnabled = enabled
                if (enabled) {
                    // Start: reconnect WS
                    shouldRun = true
                    if (ws == null && prefs.paired) connect()
                } else {
                    // Stop: close WS, stop reconnects
                    shouldRun = false
                    pingRunnable?.let { handler.removeCallbacks(it) }
                    reconnectRunnable?.let { handler.removeCallbacks(it) }
                    try { ws?.close(1000, "stopped") } catch (_: Exception) {}
                    ws = null
                    ServiceState.setConn(Conn.Disconnected)
                    hideMediaNotification()
                }
                refreshNotification()
                return START_STICKY
            }
        }

        if (shouldRun && prefs.syncEnabled && prefs.paired && ws == null) connect()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        shouldRun = false
        pingRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        ws = null
        scope.cancel()
        if (instance === this) instance = null
        ServiceState.setConn(Conn.Disconnected)
        hideMediaNotification()
        super.onDestroy()
    }

    // ---- foreground notification ----

    private fun startForegroundCompat() {
        if (startedForeground) return
        startedForeground = true
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, 0)
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sendClip = PendingIntent.getActivity(
            this, 1,
            Intent(this, SendClipboardActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val sendFile = PendingIntent.getActivity(
            this, 2,
            Intent(this, SendFileActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val startSync = PendingIntent.getService(
            this, 3,
            Intent(this, SyncService::class.java).apply {
                action = ACTION_SET_SYNC
                putExtra(EXTRA_SYNC_ENABLED, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val (title, text) = if (!prefs.syncEnabled) {
            "ZeroSynk" to "Синхронизация остановлена"
        } else when (val c = ServiceState.conn.value) {
            is Conn.Connected -> "Подключено к ${c.pcName}" to "Синхронизация активна"
            is Conn.AuthFailed -> "Авторизация отклонена" to "Переподключитесь в приложении"
            // Stable text for Connecting/Disconnected/unpaired: one message, no
            // flicker between variants — MIUI alerts on every text change.
            else -> "ZeroSynk" to "Не подключено к ПК"
        }

        val builder = NotificationCompat.Builder(this, App.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(prefs.syncEnabled)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setColor(0xFF0A84FF.toInt())

        if (prefs.syncEnabled) {
            builder.addAction(0, "Отправить буфер", sendClip)
                .addAction(0, "Отправить файл", sendFile)
        } else {
            builder.addAction(0, "Запустить", startSync)
        }
        return builder.build()
    }

    @Volatile private var lastNotifSig: String? = null

    private fun refreshNotification(force: Boolean = false) {
        try {
            val n = buildNotification()
            // MIUI/HyperOS alert on every re-post even with setOnlyAlertOnce when
            // the content changes — skip identical updates (reconnect loop, pings).
            val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = n.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val sig = "$title|$text"
            if (!force && sig == lastNotifSig) return
            lastNotifSig = sig
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    // ---- websocket ----

    private fun connect() {
        if (!prefs.syncEnabled) {
            ServiceState.setConn(Conn.Disconnected)
            return
        }
        val host = prefs.host
        val token = prefs.token
        if (host == null || token == null) {
            ServiceState.setConn(Conn.Disconnected)
            return
        }
        ServiceState.setConn(Conn.Connecting(reconnectAttempt))

        val url = "ws://$host:${prefs.port}/ws?token=$token"
        val req = Request.Builder().url(url).build()
        ws = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                reconnectAttempt = 0
                webSocket.send(
                    ProtoJson.encodeToString(
                        WsMessage.serializer(),
                        Protocol.hello(deviceName(), prefs.deviceId, token),
                    )
                )
                schedulePing()
                refreshNotification()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleServerMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                try { webSocket.close(1000, null) } catch (_: Exception) {}
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ws = null
                ServiceState.setConn(Conn.Disconnected)
                refreshNotification()
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ws = null
                ServiceState.setConn(Conn.Disconnected)
                refreshNotification()
                scheduleReconnect()
            }
        })
    }

    private fun schedulePing() {
        pingRunnable?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            val json = ProtoJson.encodeToString(WsMessage.serializer(), Protocol.ping())
            ws?.send(json)
            // Re-assert the ongoing notification: some OEMs (HyperOS) silently drop it.
            refreshNotification(force = true)
            schedulePing()
        }
        pingRunnable = r
        handler.postDelayed(r, 25_000)
    }

    private fun scheduleReconnect() {
        if (!shouldRun || !prefs.paired || !prefs.syncEnabled) return
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectAttempt++
        val delays = longArrayOf(2_000, 5_000, 10_000, 20_000, 30_000)
        val delay = delays[min(reconnectAttempt - 1, delays.size - 1)]
        ServiceState.setConn(Conn.Connecting(reconnectAttempt))
        val r = Runnable {
            if (shouldRun && prefs.paired && ws == null) connect()
        }
        reconnectRunnable = r
        handler.postDelayed(r, delay)
    }

    private fun handleServerMessage(text: String) {
        val msg = try {
            ProtoJson.decodeFromString(WsMessage.serializer(), text)
        } catch (_: Exception) {
            return
        }
        when (msg.type) {
            "welcome" -> {
                if (msg.ok == true) {
                    val name = msg.pcName ?: prefs.pcName ?: "ПК"
                    prefs.pcName = name
                    ServiceState.setConn(Conn.Connected(name))
                } else {
                    ServiceState.setConn(Conn.AuthFailed)
                    toast("PC отклонил подключение — переподключитесь")
                }
                refreshNotification()
            }
            "clipboard" -> {
                if (prefs.autoApplyClipboard && prefs.syncEnabled) {
                    ClipboardSync.applyIncoming(this, msg)
                }
            }
            "fileOffer" -> {
                val id = msg.fileId ?: return
                val name = msg.fileName ?: "file"
                if (prefs.autoDownloadFiles && prefs.syncEnabled) {
                    scope.launch(Dispatchers.IO) { downloadOffer(id, name) }
                }
            }
            "media" -> {
                if (!prefs.mediaControls || !prefs.syncEnabled) {
                    hideMediaNotification()
                    return
                }
                val active = msg.state != null && msg.state != "stopped" && !msg.title.isNullOrBlank()
                if (active) showMediaNotification(msg) else hideMediaNotification()
            }
            "notif" -> {
                // PC -> phone: show a notification from the PC
                if (!prefs.mirrorWinNotifs || !prefs.syncEnabled) return
                if (msg.origin == "phone") return // never echo our own
                showRemoteNotification(msg)
            }
            "notifReply" -> {
                // PC replied to one of our mirrored notifications
                val key = msg.key ?: return
                val replyText = msg.text ?: return
                com.buffersynk.app.notif.NotifListenerService.reply(key, replyText)
            }
            "notifRemoved" -> {
                // PC dismissed one of our notifications — cancel local copy if we made one
                // (no-op for now, the local notification is ours and we don't track PC-side keys)
            }
            "status" -> {
                // PC told us its sync state
                if (msg.state == "stopped") {
                    ServiceState.setConn(Conn.Disconnected)
                    hideMediaNotification()
                    refreshNotification()
                }
            }
            "pong" -> Unit
        }
    }

    // ---- downloads (PC -> phone) ----

    private fun downloadOffer(fileId: String, fileName: String) {
        val host = prefs.host ?: return
        val token = prefs.token ?: return
        try {
            val req = Api.openStream(host, prefs.port, token, fileId)
            wsClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    notifyFiles("Не удалось получить: $fileName")
                    return
                }
                val body = resp.body?.byteStream() ?: return
                val saved = body.use { saveDownload(it, fileName) }
                sendJson(Protocol.fileComplete(saved))
                ServiceState.setEvent("Получено: $saved")
                notifyFiles("Получено: $saved")
            }
        } catch (_: Exception) {
            notifyFiles("Ошибка загрузки: $fileName")
        }
    }

    private fun saveDownload(input: java.io.InputStream, fileName: String): String {
        val safe = SyncService.sanitizeFileName(fileName)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safe)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(safe))
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZeroSynk")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: throw IllegalStateException("insert failed")
            resolver.openOutputStream(uri)?.use { out -> input.copyTo(out) }
                ?: throw IllegalStateException("openOutputStream failed")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            safe
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ZeroSynk")
            dir.mkdirs()
            val dest = uniqueFile(dir, safe)
            dest.outputStream().use { out -> input.copyTo(out) }
            dest.name
        }
    }

    // ---- uploads (phone -> PC) ----

    /** Uploads one file. No notification here — callers show batch progress. */
    private suspend fun uploadFile(uri: Uri, batch: String? = null, total: Int? = null): Boolean {
        val host = prefs.host
        val token = prefs.token
        if (host == null || token == null) {
            toast("Не подключено к ПК")
            return false
        }
        val ok = try {
            Api.upload(contentResolver, host, prefs.port, token, uri, batch, total)
        } catch (_: Exception) {
            false
        }
        if (ok) {
            val name = Api.queryDisplayName(contentResolver, uri) ?: "файл"
            ServiceState.setEvent("Отправлено: $name")
            android.util.Log.d("ZeroSynk", "upload OK: $name ($uri)")
        } else {
            android.util.Log.e("ZeroSynk", "upload FAILED: $uri")
        }
        // Cache copies made by ShareTargetActivity (grant died with the Activity) — drop them
        if (uri.scheme == "file") {
            try {
                val path = uri.path ?: ""
                if (path.contains("shared-out")) File(path).delete()
            } catch (_: Exception) {}
        }
        return ok
    }

    /** Uploads a list of files, showing one progress notification "N из M". */
    private suspend fun uploadBatch(uris: List<Uri>, batch: String) {
        val total = uris.size
        var done = 0
        var failed = 0
        for (uri in uris) {
            if (uploadFile(uri, batch, total)) done++ else failed++
            notifyUploadProgress(done + failed, total, failed, finished = false)
        }
        notifyUploadProgress(total, total, failed, finished = true)
    }

    private fun notifyUploadProgress(completed: Int, total: Int, failed: Int, finished: Boolean) {
        try {
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sent = total - failed
            val text = when {
                finished && failed == 0 && total == 1 -> "Отправлено на ПК"
                finished && failed == 0 -> "Отправлено на ПК: ${pluralFiles(total)}"
                finished -> "Отправлено ${sent} из $total, ошибок: $failed"
                else -> "Отправка на ПК: $completed из $total"
            }
            val n = NotificationCompat.Builder(this, App.CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("ZeroSynk")
                .setContentText(text)
                .setContentIntent(openApp)
                .setColor(if (finished && failed > 0) 0xFFE53935.toInt() else 0xFF0A84FF.toInt())
                .setOnlyAlertOnce(true)
                .apply {
                    if (finished) {
                        setAutoCancel(true)
                    } else {
                        setOngoing(true)
                        setProgress(total, completed, false)
                    }
                }
                .build()
            NotificationManagerCompat.from(this).notify(UPLOAD_NOTIF_ID, n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun pluralFiles(n: Int): String {
        val m10 = n % 10
        val m100 = n % 100
        val word = when {
            m10 == 1 && m100 != 11 -> "файл"
            m10 in 2..4 && m100 !in 12..14 -> "файла"
            else -> "файлов"
        }
        return "$n $word"
    }

    // ---- media player notification (PC -> phone controls) ----

    private fun showMediaNotification(msg: WsMessage) {
        val title = msg.title ?: return
        val state = msg.state ?: return
        val playing = state == "playing"
        val subtitle = msg.artist?.takeIf { it.isNotBlank() }
            ?: msg.app?.takeIf { it.isNotBlank() }
            ?: if (playing) "Воспроизведение" else "Пауза"

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun cmd(action: String, requestCode: Int): PendingIntent {
            val i = Intent(this, SyncService::class.java).apply {
                this.action = ACTION_MEDIA
                putExtra(EXTRA_MEDIA_ACTION, action)
            }
            return PendingIntent.getService(
                this, requestCode, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val n = NotificationCompat.Builder(this, App.CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_media)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openApp)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(false)
            .setColor(0xFF0A84FF.toInt())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_action_prev, "Предыдущее", cmd("prev", 21))
            .addAction(
                if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play,
                if (playing) "Пауза" else "Играть",
                cmd("toggle", 22),
            )
            .addAction(R.drawable.ic_action_next, "Следующее", cmd("next", 23))
            .build()
        try {
            NotificationManagerCompat.from(this).notify(MEDIA_NOTIF_ID, n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun hideMediaNotification() {
        try {
            NotificationManagerCompat.from(this).cancel(MEDIA_NOTIF_ID)
        } catch (_: Exception) {
        }
    }

    // ---- remote notification from PC (Windows -> phone mirror) ----

    private fun showRemoteNotification(msg: WsMessage) {
        val title = msg.title ?: msg.appName ?: "Уведомление с ПК"
        val text = msg.text ?: ""
        val appName = msg.appName ?: "Windows"
        val key = msg.key ?: "$title|$text".hashCode().toString()

        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, App.CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("$appName: $title")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setColor(0xFF0A84FF.toInt())
            .setOnlyAlertOnce(true)
            .build()
        try {
            // Use key hash as notification id so same app+key replaces
            NotificationManagerCompat.from(this).notify(key.hashCode(), n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    // ---- clipboard send ----

    private fun sendClipboardText(text: String) {
        val hash = ClipboardSync.sha256Hex(text)
        if (hash == com.buffersynk.app.data.LoopGuard.lastSentHash) return
        val sent = sendJson(Protocol.clipboardText(text, hash))
        if (sent) {
            com.buffersynk.app.data.LoopGuard.lastSentHash = hash
            ServiceState.setEvent("Буфер отправлен на ПК")
        }
    }

    fun sendJson(msg: WsMessage): Boolean {
        val socket = ws ?: return false
        return try {
            socket.send(ProtoJson.encodeToString(WsMessage.serializer(), msg))
        } catch (_: Exception) {
            false
        }
    }

    // ---- helpers ----

    private fun notifyFiles(text: String) {
        try {
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(this, App.CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("ZeroSynk")
                .setContentText(text)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setColor(0xFF0A84FF.toInt())
                .build()
            NotificationManagerCompat.from(this).notify(text.hashCode(), n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun toast(msg: String) {
        handler.post {
            try {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
        }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    companion object {
        const val ACTION_SEND_FILE = "com.buffersynk.app.SEND_FILE"
        const val ACTION_SEND_URIS = "com.buffersynk.app.SEND_URIS"
        const val ACTION_SEND_CLIPBOARD_TEXT = "com.buffersynk.app.SEND_CLIPBOARD_TEXT"
        const val ACTION_MEDIA = "com.buffersynk.app.MEDIA"
        const val ACTION_SET_SYNC = "com.buffersynk.app.SET_SYNC"
        const val EXTRA_TEXT = "text"
        const val EXTRA_URIS = "uris"
        const val EXTRA_MEDIA_ACTION = "media_action"
        const val EXTRA_SYNC_ENABLED = "sync_enabled"
        private const val NOTIF_ID = 1
        private const val MEDIA_NOTIF_ID = 2
        private const val UPLOAD_NOTIF_ID = 3

        @Volatile
        private var instance: SyncService? = null

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SyncService::class.java))
        }

        fun setSyncEnabled(context: Context, enabled: Boolean) {
            val i = Intent(context, SyncService::class.java).apply {
                action = ACTION_SET_SYNC
                putExtra(EXTRA_SYNC_ENABLED, enabled)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun isConnected(): Boolean = instance?.ws != null

        fun sendJson(msg: WsMessage): Boolean = instance?.sendJson(msg) ?: false

        fun sanitizeFileName(name: String): String {
            val bad = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
            val cleaned = buildString(name.length) {
                for (ch in name) append(if (ch in bad) '_' else ch)
            }.trim()
            return cleaned.ifEmpty { "file.bin" }
        }

        private fun uniqueFile(dir: File, name: String): File {
            val direct = File(dir, name)
            if (!direct.exists()) return direct
            val stem = name.substringBeforeLast('.')
            val ext = name.substringAfterLast('.', "")
            var i = 1
            while (true) {
                val candidate = File(dir, if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext")
                if (!candidate.exists()) return candidate
                i++
            }
        }

        private fun mimeFor(name: String): String {
            val ext = name.substringAfterLast('.', "").lowercase()
            return when (ext) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "svg" -> "image/svg+xml"
                "txt", "log" -> "text/plain"
                "pdf" -> "application/pdf"
                "zip" -> "application/zip"
                "7z" -> "application/x-7z-compressed"
                "rar" -> "application/vnd.rar"
                "gz" -> "application/gzip"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "apk" -> "application/vnd.android.package-archive"
                "json" -> "application/json"
                "html", "htm" -> "text/html"
                "csv" -> "text/csv"
                "doc", "docx" -> "application/msword"
                "xls", "xlsx" -> "application/vnd.ms-excel"
                else -> "application/octet-stream"
            }
        }
    }
}
