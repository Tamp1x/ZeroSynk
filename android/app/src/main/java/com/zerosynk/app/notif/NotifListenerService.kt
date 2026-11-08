package com.zerosynk.app.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.data.NotifFilter
import com.zerosynk.app.data.NotifHistory
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.service.SyncService
import java.security.MessageDigest

/**
 * Mirrors phone notifications to the PC over the existing WS channel.
 * Filters: own package, ongoing, empty. Loop-guard: hash(key+title+text) dedupe.
 */
class NotifListenerService : NotificationListenerService() {

    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs.get(this)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Send current active notifications on connect
        try {
            val active = activeNotifications ?: return
            for (sbn in active) maybeForward(sbn, force = true)
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        maybeForward(sbn, force = false)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        if (!prefs.syncNotifs || !prefs.syncEnabled) return
        if (!shouldMirror(sbn)) return
        val key = sbn.key
        if (SyncService.isConnected()) {
            SyncService.sendJson(Protocol.notifRemoved(key))
        }
    }

    private fun maybeForward(sbn: StatusBarNotification, force: Boolean) {
        if (!prefs.syncNotifs || !prefs.syncEnabled) return
        if (!shouldMirror(sbn)) return

        val title = extractTitle(sbn) ?: return
        val text = extractText(sbn)
        if (title.isBlank() && text.isBlank()) return

        val key = sbn.key
        val hash = sha256Hex("$key|$title|$text")

        // Loop-guard: skip if we already sent this exact content
        if (!force && hash == lastSentHash) return
        // Also skip if this came from our own reply (echo suppression)
        if (isReplyHash(hash)) return
        lastSentHash = hash

        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName
        }

        NotifHistory.add(this, key, appName, title, text, origin = "phone")
        com.zerosynk.app.service.ServiceState.bumpNotifRevision()

        val replyable = sbn.notification.actions?.any {
            it.remoteInputs?.isNotEmpty() == true
        } == true

        val msg = Protocol.notif(
            key = key,
            pkg = sbn.packageName,
            appName = appName,
            title = title,
            text = text,
            replyable = replyable,
            origin = "phone",
        )
        SyncService.sendJson(msg)
    }

    private fun shouldMirror(sbn: StatusBarNotification): Boolean {
        // Exclude own package (loop prevention)
        if (sbn.packageName == packageName) return false
        // Exclude ongoing (media players, downloads, etc.)
        if (sbn.isOngoing) return false
        // Exclude low-priority / silent
        if (sbn.notification.priority == Notification.PRIORITY_MIN) return false
        // Exclude notifications without content
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (title.isNullOrBlank() && text.isNullOrBlank()) return false
        val important = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = getSystemService(NotificationListenerService.NOTIFICATION_SERVICE)
                .let { it as android.app.NotificationManager }
                .getNotificationChannel(sbn.notification.channelId)
            channel == null || channel.importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT
        } else {
            sbn.notification.priority >= Notification.PRIORITY_DEFAULT
        }
        val appName = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName
        }
        if (!NotifFilter.allows(prefs, sbn.packageName, appName, important)) return false
        return true
    }

    /** Apply a reply from the PC to the original notification via RemoteInput. */
    fun applyReply(key: String, replyText: String) {
        try {
            val sbn = activeNotifications?.find { it.key == key } ?: return
            val notification = sbn.notification
            val action = notification.actions?.find { it.remoteInputs?.isNotEmpty() == true } ?: return
            val remoteInputs = action.remoteInputs ?: return

            // Mark this hash as "recently replied" to suppress echo when messenger reposts
            val hash = sha256Hex("$key|${extractTitle(sbn) ?: ""}|${extractText(sbn)}")
            addReplyHash(hash)
            addReplyHash(sha256Hex("$key|${extractTitle(sbn) ?: ""}|$replyText"))

            // Build RemoteInput result and send via the action's PendingIntent
            val result = android.content.Intent()
            val bundle = android.os.Bundle()
            for (ri in remoteInputs) {
                bundle.putCharSequence(ri.resultKey, replyText)
            }
            android.app.RemoteInput.addResultsToIntent(remoteInputs, result, bundle)
            try {
                action.actionIntent?.send(applicationContext, 0, result)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private fun extractTitle(sbn: StatusBarNotification): String? =
        sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    private fun extractText(sbn: StatusBarNotification): String =
        sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

    companion object {
        @Volatile private var lastSentHash: String? = null
        @Volatile private var instance: NotifListenerService? = null
        private val recentReplies = java.util.Collections.synchronizedList(mutableListOf<String>())

        /** Apply a reply from the PC (called by SyncService). */
        fun reply(key: String, text: String) {
            instance?.applyReply(key, text)
        }

        private fun addReplyHash(hash: String) {
            synchronized(recentReplies) {
                recentReplies.add(hash)
                while (recentReplies.size > 64) recentReplies.removeAt(0)
            }
        }

        private fun isReplyHash(hash: String): Boolean {
            synchronized(recentReplies) {
                return hash in recentReplies
            }
        }

        fun sha256Hex(s: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
