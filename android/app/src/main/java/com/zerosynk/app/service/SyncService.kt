package com.zerosynk.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Base64
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.zerosynk.app.App
import com.zerosynk.app.MainActivity
import com.zerosynk.app.R
import com.zerosynk.app.clipboard.ClipboardSync
import com.zerosynk.app.data.ClipHistory
import com.zerosynk.app.data.FileHashes
import com.zerosynk.app.data.NotifHistory
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.data.SaveFolder
import com.zerosynk.app.design.Design
import com.zerosynk.app.media.PhoneMedia
import com.zerosynk.app.net.Api
import com.zerosynk.app.net.ProtoJson
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.net.WsMessage
import com.zerosynk.app.send.SendClipboardActivity
import com.zerosynk.app.send.SendFileActivity
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

    /** File ids currently being downloaded — guards against duplicate fileOffers. */
    private val inFlightDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** File ids the user cancelled from the download-progress notification (p. 14). */
    private val cancelDownloads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** True while a batch upload should stop after the current file («Отменить» action). */
    @Volatile
    private var uploadCancelled = false

    @Volatile
    private var lastUploadTick = 0L

    /** Where a finished download landed — name plus the URI the «Открыть» action needs (p. 16). */
    private data class SavedFile(val name: String, val uri: Uri)

    @Volatile
    private var shouldRun = true

    private var startedForeground = false
    private var pingRunnable: Runnable? = null
    private var reconnectRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs.get(this)
        // Reverse media control: watch the phone's sessions, push "now playing" to the PC.
        PhoneMedia.start(this)
        // Resumable .part files must outlive a restart (p. 14) — only ancient ones die.
        scope.launch(Dispatchers.IO) {
            try {
                val dir = File(cacheDir, "dl")
                val cutoff = System.currentTimeMillis() - 7 * 24 * 3600_000L
                dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
            } catch (_: Exception) {}
        }
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
            ACTION_CANCEL_DOWNLOAD -> {
                // Stop the transfer but keep the .part file for a later Range resume (p. 14).
                intent.getStringExtra(EXTRA_FILE_ID)?.let { cancelDownloads.add(it) }
                return START_STICKY
            }
            ACTION_CANCEL_UPLOAD -> {
                uploadCancelled = true
                Api.cancelUpload()
                return START_STICKY
            }
            ACTION_DOWNLOAD_PATH -> {
                // File manager on the phone (p. 12): stream an arbitrary PC file down.
                val path = intent.getStringExtra(EXTRA_PATH) ?: return START_STICKY
                val name = intent.getStringExtra(EXTRA_FILE_NAME) ?: path.substringAfterLast('/')
                scope.launch(Dispatchers.IO) { downloadPath(path, name) }
                return START_STICKY
            }
            ACTION_NOTIF_REPLY -> {
                // Inline reply on a mirrored PC notification (p. 21).
                val key = intent.getStringExtra(EXTRA_NOTIF_KEY) ?: return START_STICKY
                val results = RemoteInput.getResultsFromIntent(intent)
                val reply = results?.getCharSequence(EXTRA_REPLY)?.toString()?.trim()
                if (!reply.isNullOrEmpty()) {
                    sendJson(Protocol.notifReply(key, reply))
                    ServiceState.setEvent("Ответ отправлен на ПК")
                    NotificationManagerCompat.from(this).cancel(key.hashCode())
                }
                return START_STICKY
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
        PhoneMedia.stop()
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
            Design.displayName() to "Синхронизация остановлена"
        } else when (val c = ServiceState.conn.value) {
            is Conn.Connected -> "Подключено к ${c.pcName}" to "Синхронизация активна"
            is Conn.AuthFailed -> "Авторизация отклонена" to "Переподключитесь в приложении"
            // Stable text for Connecting/Disconnected/unpaired: one message, no
            // flicker between variants — MIUI alerts on every text change.
            else -> Design.displayName() to "Не подключено к ПК"
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
            .setColor(Design.accent())

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

        val pw = prefs.transferPassword?.takeIf { it.isNotEmpty() }
        val url = "ws://$host:${prefs.port}/ws?token=$token" +
            (if (pw != null) "&pw=${java.net.URLEncoder.encode(pw, "UTF-8")}" else "")
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
                // Tell the PC what is playing here right now (reverse-control card).
                PhoneMedia.pushNow()
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
                    // The PC owns the shared design skin — adopt it on connect.
                    msg.design?.let { applyRemoteDesign(it) }
                    // Wire-protocol compatibility check (phone declared its proto in hello,
                    // the PC echoes its own back in welcome).
                    val remoteProto = msg.proto
                    if (remoteProto != null && remoteProto != Protocol.VERSION) {
                        android.util.Log.w(
                            "ZeroSynk",
                            "proto mismatch: pc=$remoteProto phone=${Protocol.VERSION}",
                        )
                        toast("ПК: протокол v$remoteProto, телефон v${Protocol.VERSION} — обновите приложение на ПК")
                    }
                } else {
                    ServiceState.setConn(Conn.AuthFailed)
                    toast("PC отклонил подключение — переподключитесь")
                }
                refreshNotification()
            }
            "design" -> {
                msg.design?.let { applyRemoteDesign(it) }
            }
            "clipboard" -> {
                if (msg.kind == "stack") {
                    // Initial sync (p. 11): recent PC clipboard entries — recorded into
                    // the local history only, never applied to the system clipboard.
                    msg.items?.forEach { item ->
                        if (item.text.isBlank()) return@forEach
                        val plain = if (item.kind == "html") {
                            ClipboardSync.plainFromHtml(item.text)
                        } else {
                            item.text
                        }
                        ClipHistory.add(
                            this, item.kind, plain,
                            item.hash ?: ClipboardSync.sha256Hex(item.text),
                        )
                    }
                    ServiceState.bumpClipRevision()
                    return
                }
                if (prefs.autoApplyClipboard && prefs.syncEnabled) {
                    ClipboardSync.applyIncoming(this, msg)
                    ServiceState.bumpClipRevision()
                }
            }
            "fileOffer" -> {
                val id = msg.fileId ?: return
                val name = msg.fileName ?: "file"
                if (prefs.autoDownloadFiles && prefs.syncEnabled) {
                    // A reconnect may re-offer a file that is already downloading —
                    // don't save it twice.
                    if (!inFlightDownloads.add(id)) return
                    val size = msg.fileSize
                    val hash = msg.hash
                    scope.launch(Dispatchers.IO) {
                        try {
                            downloadOffer(id, name, size, hash)
                        } finally {
                            inFlightDownloads.remove(id)
                        }
                    }
                }
            }
            "media" -> {
                if (!prefs.mediaControls || !prefs.syncEnabled) {
                    hideMediaNotification()
                    return
                }
                val active = msg.state != null && msg.state != "stopped" && !msg.title.isNullOrBlank()
                if (active) {
                    showMediaNotification(msg)
                    updateNowPlayingCard(msg)
                } else {
                    hideMediaNotification()
                }
            }
            "mediaControl" -> {
                // PC -> phone: control this phone's own player (reverse direction).
                val action = msg.action ?: return
                PhoneMedia.handleAction(action, msg.position)
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
                com.zerosynk.app.notif.NotifListenerService.reply(key, replyText)
            }
            "notifRemoved" -> {
                // PC dismissed one of our notifications — cancel local copy if we made one
                // (no-op for now, the local notification is ours and we don't track PC-side keys)
            }
            "status" -> {
                // PC told us its sync state
                when (msg.state) {
                    "stopped" -> {
                        ServiceState.setConn(Conn.Disconnected)
                        hideMediaNotification()
                        refreshNotification()
                    }
                    "started" -> {
                        // PC re-enabled sync: the socket never went away, only our
                        // UI state was flipped by the earlier "stopped".
                        if (prefs.syncEnabled) {
                            ServiceState.setConn(Conn.Connected(prefs.pcName ?: "ПК"))
                            refreshNotification(force = true)
                        }
                    }
                }
            }
            "pong" -> Unit
        }
    }

    /** Applies a design skin pushed by the PC (shared across devices). */
    private fun applyRemoteDesign(design: String) {
        val d = Design.normalize(design)
        if (d == Design.current) return
        Design.apply(this, d)
        refreshNotification(force = true)
    }

    // ---- downloads (PC -> phone) ----

    /** Downloads a file offered by the PC (resume-aware, p. 14). */
    private fun downloadOffer(fileId: String, fileName: String, fileSize: Long?, hash: String?, retry: Int = 0) {
        val host = prefs.host ?: return
        val token = prefs.token ?: return
        downloadTo(fileId, fileName, fileSize, hash, retry, confirm = true) { range ->
            Api.openStream(host, prefs.port, token, fileId, range, prefs.transferPassword)
        }
    }

    /** Downloads an arbitrary PC file picked in the phone's file manager (p. 12). */
    private fun downloadPath(path: String, fileName: String) {
        val host = prefs.host ?: return
        val token = prefs.token ?: return
        val id = "p" + Integer.toHexString(path.hashCode())
        if (!inFlightDownloads.add(id)) return
        try {
            downloadTo(id, fileName, null, null, retry = 0, confirm = false) { range ->
                val b = Request.Builder().url(Api.rawUrl(host, prefs.port, token, path)).get()
                if (range != null) b.header("Range", range)
                b.build()
            }
        } finally {
            inFlightDownloads.remove(id)
        }
    }

    private fun downloadTo(
        fileId: String,
        fileName: String,
        fileSize: Long?,
        hash: String?,
        retry: Int = 0,
        confirm: Boolean,
        makeRequest: (String?) -> Request,
    ) {
        // Dedupe (p. 15): same content already landed in the last 24 h — just
        // confirm it so the PC drops its staged copy.
        if (hash != null && FileHashes.recent(this, hash)) {
            if (confirm) sendJson(Protocol.fileComplete(fileName, fileId))
            NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
            notifyFiles("Уже есть на телефоне: $fileName")
            return
        }

        val part = File(File(cacheDir, "dl").apply { mkdirs() }, "$fileId.part")
        try {
            // A complete .part from an interrupted run needs no transfer at all.
            if (fileSize != null && fileSize > 0 && part.exists() && part.length() >= fileSize) {
                finishDownload(part, fileName, fileId, hash, confirm)
                return
            }
            var offset = if (part.exists()) part.length() else 0L
            val req = makeRequest(if (offset > 0) "bytes=$offset-" else null)
            wsClient.newCall(req).execute().use { resp ->
                if (resp.code == 416 && offset > 0 && retry == 0) {
                    // Our .part is stale (file changed on the PC) — start over once.
                    part.delete()
                    downloadTo(fileId, fileName, fileSize, hash, retry + 1, confirm, makeRequest)
                    return
                }
                if (!resp.isSuccessful) {
                    notifyFiles("Не удалось получить: $fileName")
                    return
                }
                val body = resp.body ?: return
                if (resp.code != 206 && offset > 0) {
                    // No Range support in this reply — restart the .part from zero.
                    part.delete()
                    offset = 0
                }
                val total = contentRangeTotal(resp) ?: fileSize ?: 0L

                var lastTick = 0L
                var cancelled = false
                java.io.FileOutputStream(part, offset > 0).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var copied = offset
                        while (true) {
                            if (cancelDownloads.contains(fileId)) {
                                cancelled = true
                                break
                            }
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            copied += n
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastTick > 500) {
                                lastTick = now
                                notifyDownloadProgress(fileName, copied, total, fileId)
                            }
                        }
                    }
                }

                if (cancelled) {
                    // The .part stays: the next offer resumes from where it stopped.
                    NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
                    notifyFiles("Загрузка отменена: $fileName")
                    return
                }
                if (total > 0 && part.length() < total) {
                    // Connection dropped mid-file — keep the .part for a Range resume (p. 14).
                    NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
                    notifyFiles("Загрузка прервана — будет возобновлена: $fileName")
                    return
                }
                finishDownload(part, fileName, fileId, hash, confirm)
            }
        } catch (_: Exception) {
            NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
            notifyFiles("Ошибка загрузки: $fileName")
        } finally {
            cancelDownloads.remove(fileId)
        }
    }

    /** Verifies the SHA-256, moves the .part into place and confirms the offer to the PC. */
    private fun finishDownload(part: File, fileName: String, fileId: String, hash: String?, confirm: Boolean) {
        if (hash != null) {
            val actual = sha256File(part)
            if (actual == null || !actual.equals(hash, ignoreCase = true)) {
                part.delete()
                NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
                notifyFiles("Файл повреждён при передаче: $fileName")
                return
            }
            FileHashes.remember(this, hash)
        }
        val saved = try {
            part.inputStream().use { saveDownload(it, fileName) }
        } catch (e: Exception) {
            NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
            notifyFiles("Не удалось сохранить: $fileName")
            return
        }
        try { part.delete() } catch (_: Exception) {}
        NotificationManagerCompat.from(this).cancel(DOWNLOAD_NOTIF_ID)
        if (confirm) sendJson(Protocol.fileComplete(saved.name, fileId))
        ServiceState.setEvent("Получено: ${saved.name}")
        notifyFiles("Получено: ${saved.name}", saved.uri)
    }

    /** Total size from a Content-Range header ("bytes 100-999/1234"), null when absent. */
    private fun contentRangeTotal(resp: Response): Long? {
        val cr = resp.header("Content-Range") ?: return null
        val idx = cr.lastIndexOf('/')
        if (idx < 0) return null
        return cr.substring(idx + 1).trim().toLongOrNull()?.takeIf { it > 0 }
    }

    private fun sha256File(f: File): String? = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** Progress notification for a running download with a «Отменить» action (p. 14). */
    private fun notifyDownloadProgress(fileName: String, copied: Long, total: Long, fileId: String) {
        try {
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancel = PendingIntent.getService(
                this, 41,
                Intent(this, SyncService::class.java).apply {
                    action = ACTION_CANCEL_DOWNLOAD
                    putExtra(EXTRA_FILE_ID, fileId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val pct = if (total > 0) (copied * 100 / total).toInt().coerceIn(0, 100) else 0
            val text = if (total > 0) "${fmtSize(copied)} из ${fmtSize(total)} · $pct%"
            else fmtSize(copied)
            val n = NotificationCompat.Builder(this, App.CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle("Получение: $fileName")
                .setContentText(text)
                .setContentIntent(openApp)
                .setProgress(100, pct, total <= 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setColor(Design.accent())
                .addAction(0, "Отменить", cancel)
                .build()
            NotificationManagerCompat.from(this).notify(DOWNLOAD_NOTIF_ID, n)
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun fmtSize(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> "%.1f ГБ".format(bytes / 1024.0 / 1024.0 / 1024.0)
        bytes >= 1024L * 1024 -> "%.1f МБ".format(bytes / 1024.0 / 1024.0)
        bytes >= 1024 -> "%.0f КБ".format(bytes / 1024.0)
        else -> "$bytes Б"
    }

    /** Saves the finished download; returns where it landed (needed for «Открыть», p. 16). */
    private fun saveDownload(input: java.io.InputStream, fileName: String): SavedFile {
        val safe = SyncService.sanitizeFileName(fileName)
        // Custom folder picked in settings (SAF tree) wins over Download/ZeroSynk.
        SaveFolder.activeTree(this)?.let { tree ->
            val saved = saveToTree(tree, input, safe)
            if (saved != null) return saved
            // null = couldn't even start writing (grant gone, provider refused):
            // the stream is untouched, so fall back to the default path below.
        }
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
            SavedFile(safe, uri)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ZeroSynk")
            dir.mkdirs()
            val dest = uniqueFile(dir, safe)
            dest.outputStream().use { out -> input.copyTo(out) }
            SavedFile(dest.name, fileUri(dest))
        }
    }

    /** Content URI for a file in Download/ZeroSynk on pre-Q devices (FileProvider). */
    private fun fileUri(f: File): Uri = androidx.core.content.FileProvider.getUriForFile(
        this, "$packageName.fileprovider", f,
    )

    /** Writes into a user-picked SAF folder.
     *  Returns the saved file name, or null when the folder is unusable AND nothing was
     *  consumed from [input] — then the caller falls back to Download/ZeroSynk.
     *  Once writing has started, failures propagate (never splice a partial stream). */
    private fun saveToTree(tree: Uri, input: java.io.InputStream, safe: String): SavedFile? {
        val resolver = contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val dirDoc = DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
        val name = uniqueTreeName(tree, treeDocId, safe)
        val doc = try {
            DocumentsContract.createDocument(resolver, dirDoc, mimeFor(name), name)
        } catch (_: Exception) {
            null
        } ?: return null // folder gone / permission revoked — stream untouched
        val out = try {
            resolver.openOutputStream(doc)
        } catch (_: Exception) {
            null
        }
        if (out == null) {
            try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
            return null
        }
        try {
            out.use { sink -> input.copyTo(sink) }
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(resolver, doc) } catch (_: Exception) {}
            throw e
        }
        return SavedFile(name, doc)
    }

    /** Appends " (1)", " (2)"… so we never overwrite an existing file in the picked folder. */
    private fun uniqueTreeName(tree: Uri, dirDocId: String, name: String): String {
        val existing = mutableSetOf<String>()
        try {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dirDocId)
            contentResolver
                .query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c ->
                    val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    while (c.moveToNext()) {
                        if (idx >= 0) c.getString(idx)?.let { existing.add(it.lowercase()) }
                    }
                }
        } catch (_: Exception) {
        }
        if (name.lowercase() !in existing) return name
        val stem = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (true) {
            val cand = if (ext.isEmpty()) "$stem ($i)" else "$stem ($i).$ext"
            if (cand.lowercase() !in existing) return cand
            i++
        }
    }

    // ---- uploads (phone -> PC) ----

    /** Uploads one file. No notification here — callers show batch progress. */
    private suspend fun uploadFile(
        uri: Uri,
        batch: String? = null,
        total: Int? = null,
        index: Int = 1,
        batchTotal: Int = 1,
    ): Boolean {
        val host = prefs.host
        val token = prefs.token
        if (host == null || token == null) {
            toast("Не подключено к ПК")
            return false
        }
        val name = Api.queryDisplayName(contentResolver, uri) ?: "файл"
        // Full SHA-256 first (p. 15): the PC skips the body when it already has it.
        val hash = Api.sha256(contentResolver, uri)
        val ok = try {
            Api.upload(
                contentResolver, host, prefs.port, token, uri, batch, total, hash,
                prefs.transferPassword,
            ) { sent, size ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastUploadTick > 500) {
                    lastUploadTick = now
                    notifyUploadProgress(
                        index, batchTotal, 0, finished = false,
                        currentName = name, sent = sent, size = size,
                    )
                }
            }
        } catch (_: Exception) {
            false
        }
        if (ok) {
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
        uploadCancelled = false
        lastUploadTick = 0L
        for ((i, uri) in uris.withIndex()) {
            if (uploadCancelled) break
            if (uploadFile(uri, batch, total, index = i + 1, batchTotal = total)) done++ else failed++
            notifyUploadProgress(done + failed, total, failed, finished = false)
        }
        val stopped = uploadCancelled
        uploadCancelled = false
        notifyUploadProgress(done + failed, total, failed, finished = true, cancelled = stopped)
    }

    private fun notifyUploadProgress(
        completed: Int,
        total: Int,
        failed: Int,
        finished: Boolean,
        currentName: String? = null,
        sent: Long = 0,
        size: Long = 0,
        cancelled: Boolean = false,
    ) {
        try {
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val cancelUpload = PendingIntent.getService(
                this, 42,
                Intent(this, SyncService::class.java).apply { action = ACTION_CANCEL_UPLOAD },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val sentCount = total - failed
            val text = when {
                cancelled -> "Отправка отменена ($completed из $total)"
                finished && failed == 0 && total == 1 -> "Отправлено на ПК"
                finished && failed == 0 -> "Отправлено на ПК: ${pluralFiles(total)}"
                finished -> "Отправлено $sentCount из $total, ошибок: $failed"
                currentName != null && size > 0 -> {
                    val pct = (sent * 100 / size).toInt().coerceIn(0, 100)
                    "Отправка $completed из $total: $currentName · $pct%"
                }
                currentName != null -> "Отправка $completed из $total: $currentName"
                else -> "Отправка на ПК: $completed из $total"
            }
            val builder = NotificationCompat.Builder(this, App.CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(Design.displayName())
                .setContentText(text)
                .setContentIntent(openApp)
                .setColor(if (finished && failed > 0) 0xFFE53935.toInt() else Design.accent())
                .setOnlyAlertOnce(true)
                .apply {
                    if (finished) {
                        setAutoCancel(true)
                    } else {
                        setOngoing(true)
                        if (size > 0) {
                            setProgress(100, (sent * 100 / size).toInt().coerceIn(0, 100), false)
                        } else {
                            setProgress(total, completed, false)
                        }
                        addAction(0, "Отменить", cancelUpload)
                    }
                }
            NotificationManagerCompat.from(this).notify(UPLOAD_NOTIF_ID, builder.build())
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

    @Volatile private var lastMediaSig: String? = null
    @Volatile private var lastCoverKey: String? = null
    private var lastCoverBitmap: Bitmap? = null

    /** Keeps the home-screen "now playing" card in sync with the PC. */
    private fun updateNowPlayingCard(msg: WsMessage) {
        val title = msg.title ?: return
        val artist = msg.artist ?: ""
        val key = "$title|$artist"
        val prev = ServiceState.media.value
        ServiceState.setMedia(
            MediaInfo(
                state = msg.state ?: "paused",
                title = title,
                artist = artist,
                app = msg.app ?: "",
                position = msg.position ?: 0L,
                duration = msg.duration ?: 0L,
                coverKey = key,
                // Ticks carry no art — keep the cover of the same track.
                cover = msg.cover ?: prev?.takeIf { it.coverKey == key }?.cover,
            ),
        )
    }

    private fun showMediaNotification(msg: WsMessage) {
        val title = msg.title ?: return
        val state = msg.state ?: return
        val playing = state == "playing"
        val subtitle = msg.artist?.takeIf { it.isNotBlank() }
            ?: msg.app?.takeIf { it.isNotBlank() }
            ?: if (playing) "Воспроизведение" else "Пауза"
        val pos = (msg.position ?: 0L).coerceAtLeast(0)
        val dur = (msg.duration ?: 0L).coerceAtLeast(0)
        val cover = msg.cover
        val coverKey = "$title|${msg.artist ?: ""}"

        // MIUI/HyperOS can re-alert on re-posts whose content changed: only refresh
        // when the track/state changes or the progress moves by ≥2 seconds.
        val bucket = pos / 2
        val sig = "$title|$subtitle|$playing|$bucket|${cover != null}"
        if (sig == lastMediaSig) return
        lastMediaSig = sig

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

        val builder = NotificationCompat.Builder(this, App.CHANNEL_MEDIA)
            .setSmallIcon(R.drawable.ic_stat_media)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openApp)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setSilent(true) // progress updates must never buzz the user
            .setOngoing(false)
            .setAutoCancel(false)
            .setColor(Design.accent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_action_prev, "Предыдущее", cmd("prev", 21))
            .addAction(
                if (playing) R.drawable.ic_action_pause else R.drawable.ic_action_play,
                if (playing) "Пауза" else "Играть",
                cmd("toggle", 22),
            )
            .addAction(R.drawable.ic_action_next, "Следующее", cmd("next", 23))
            .addAction(0, "−10 с", cmd("seekBack", 24))
            .addAction(0, "+10 с", cmd("seekFwd", 25))

        decodeCover(cover, coverKey)?.let { builder.setLargeIcon(it) }

        if (dur > 0) {
            val progress = (pos.coerceAtMost(dur) * 1000 / dur).toInt()
            builder.setProgress(1000, progress, false)
            builder.setSubText("${fmtTime(pos)} / ${fmtTime(dur)}")
        } else {
            builder.setProgress(0, 0, true)
        }

        try {
            NotificationManagerCompat.from(this).notify(MEDIA_NOTIF_ID, builder.build())
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    /** Album art for the notification large icon, cached per track. */
    private fun decodeCover(cover: String?, key: String): Bitmap? {
        if (cover == null) return if (key == lastCoverKey) lastCoverBitmap else null
        if (key == lastCoverKey && lastCoverBitmap != null) return lastCoverBitmap
        return try {
            val bytes = Base64.decode(cover, Base64.DEFAULT)
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // The system downscales large icons anyway — cap decoding work at 512px.
            val max = 512
            val bmp = if (decoded.width > max || decoded.height > max) {
                val ratio = minOf(max.toFloat() / decoded.width, max.toFloat() / decoded.height)
                val scaled = Bitmap.createScaledBitmap(
                    decoded,
                    (decoded.width * ratio).toInt().coerceAtLeast(1),
                    (decoded.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
                decoded.recycle()
                scaled
            } else {
                decoded
            }
            lastCoverKey = key
            lastCoverBitmap = bmp
            bmp
        } catch (_: Exception) {
            null
        }
    }

    private fun fmtTime(sec: Long): String {
        val m = sec / 60
        val s = (sec % 60).toString().padStart(2, '0')
        return "$m:$s"
    }

    private fun hideMediaNotification() {
        lastMediaSig = null
        lastCoverKey = null
        lastCoverBitmap = null
        ServiceState.setMedia(null)
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

        // History (p. 20): survives a restart, feeds the home-screen card.
        NotifHistory.add(this, key, appName, title, text, origin = "pc")
        ServiceState.bumpNotifRevision()

        val openApp = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inline reply (p. 21): the answer goes to the PC as notifReply — the PC has
        // no public API to inject it into the Windows app, so it lands on its
        // clipboard with a toast (p. 23).
        val replyIntent = Intent(this, SyncService::class.java).apply {
            action = ACTION_NOTIF_REPLY
            putExtra(EXTRA_NOTIF_KEY, key)
        }
        val replyPending = PendingIntent.getService(
            this, 30 + ((key.hashCode() and 0x7fffffff) % 1000),
            replyIntent,
            // RemoteInput is filled in by the system — the PendingIntent must be
            // mutable on API 31+.
            PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0,
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Ответить", replyPending)
            .addRemoteInput(
                RemoteInput.Builder(EXTRA_REPLY).setLabel("Ответ").build(),
            )
            .build()

        val n = NotificationCompat.Builder(this, App.CHANNEL_FILES)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle("$appName: $title")
            .setContentText(text)
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setColor(Design.accent())
            .setOnlyAlertOnce(true)
            .addAction(replyAction)
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
        if (hash == com.zerosynk.app.data.LoopGuard.lastSentHash) return
        val sent = sendJson(Protocol.clipboardText(text, hash))
        if (sent) {
            com.zerosynk.app.data.LoopGuard.lastSentHash = hash
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

    private fun notifyFiles(text: String, openUri: Uri? = null) {
        try {
            val openApp = PendingIntent.getActivity(
                this, 0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(this, App.CHANNEL_FILES)
                .setSmallIcon(R.drawable.ic_stat_sync)
                .setContentTitle(Design.displayName())
                .setContentText(text)
                .setContentIntent(openApp)
                .setAutoCancel(true)
                .setColor(Design.accent())
            // «Открыть» — only ever on an explicit tap, never on receive (p. 16).
            if (openUri != null) {
                val open = PendingIntent.getActivity(
                    this, 20,
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(
                            openUri,
                            contentResolver.getType(openUri) ?: "application/octet-stream",
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                builder.addAction(0, "Открыть", open)
            }
            NotificationManagerCompat.from(this).notify(text.hashCode(), builder.build())
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
        const val ACTION_SEND_FILE = "com.zerosynk.app.SEND_FILE"
        const val ACTION_SEND_URIS = "com.zerosynk.app.SEND_URIS"
        const val ACTION_SEND_CLIPBOARD_TEXT = "com.zerosynk.app.SEND_CLIPBOARD_TEXT"
        const val ACTION_MEDIA = "com.zerosynk.app.MEDIA"
        const val ACTION_SET_SYNC = "com.zerosynk.app.SET_SYNC"
        const val ACTION_CANCEL_DOWNLOAD = "com.zerosynk.app.CANCEL_DOWNLOAD"
        const val ACTION_CANCEL_UPLOAD = "com.zerosynk.app.CANCEL_UPLOAD"
        const val ACTION_DOWNLOAD_PATH = "com.zerosynk.app.DOWNLOAD_PATH"
        const val ACTION_NOTIF_REPLY = "com.zerosynk.app.NOTIF_REPLY"
        const val EXTRA_TEXT = "text"
        const val EXTRA_URIS = "uris"
        const val EXTRA_MEDIA_ACTION = "media_action"
        const val EXTRA_SYNC_ENABLED = "sync_enabled"
        const val EXTRA_FILE_ID = "file_id"
        const val EXTRA_PATH = "path"
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_NOTIF_KEY = "notif_key"
        const val EXTRA_REPLY = "reply"
        private const val NOTIF_ID = 1
        private const val MEDIA_NOTIF_ID = 2
        private const val UPLOAD_NOTIF_ID = 3
        private const val DOWNLOAD_NOTIF_ID = 4

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

        /** Starts a download of an arbitrary PC file (file manager, p. 12). */
        fun requestPathDownload(context: Context, path: String, name: String) {
            val i = Intent(context, SyncService::class.java).apply {
                action = ACTION_DOWNLOAD_PATH
                putExtra(EXTRA_PATH, path)
                putExtra(EXTRA_FILE_NAME, name)
            }
            ContextCompat.startForegroundService(context, i)
        }

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
