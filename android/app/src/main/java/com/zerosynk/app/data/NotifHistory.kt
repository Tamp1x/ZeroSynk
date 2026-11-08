package com.zerosynk.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Notification history on the phone (p. 20): every mirrored PC notification is
 * stored here so the home-screen card survives a reboot. Newest-first, capped,
 * JSON in filesDir — same pattern as ClipHistory.
 */
@Serializable
data class NotifEntry(
    /** win:{Id} for PC-origin, app key otherwise — replaces the older copy. */
    val key: String,
    val app: String,
    val title: String,
    val text: String = "",
    val ts: Long,
    /** pc = came from the computer, phone = mirrored locally (future-proof). */
    val origin: String = "pc",
)

object NotifHistory {
    private const val MAX = 50

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private fun storeFile(context: Context) = File(context.filesDir, "notifhist.json")

    /** Newest-first snapshot of the history (empty list when nothing is stored). */
    fun list(context: Context): List<NotifEntry> = synchronized(this) { load(context) }

    /** Stores a notification; a repeat of the same key moves its copy to the top. */
    fun add(
        context: Context,
        key: String,
        app: String,
        title: String,
        text: String,
        origin: String = "pc",
    ) {
        if (title.isBlank() && text.isBlank()) return
        synchronized(this) {
            val items = load(context).toMutableList()
            items.removeAll { it.key == key }
            items.add(
                0,
                NotifEntry(
                    key = key,
                    app = app,
                    title = title,
                    text = text,
                    ts = System.currentTimeMillis() / 1000,
                    origin = origin,
                ),
            )
            while (items.size > MAX) items.removeAt(items.size - 1)
            save(context, items)
        }
    }

    fun clear(context: Context) {
        synchronized(this) { save(context, emptyList()) }
    }

    private fun load(context: Context): List<NotifEntry> {
        val f = storeFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(NotifEntry.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<NotifEntry>) {
        runCatching {
            storeFile(context).writeText(
                json.encodeToString(ListSerializer(NotifEntry.serializer()), items),
            )
        }
    }
}
