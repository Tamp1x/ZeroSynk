package com.zerosynk.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Clipboard history on the phone (p. 7): entries pushed from the PC (including the
 * initial stack seed) plus copies the user made locally. Tap = copy back to the
 * clipboard, long-press = send to the PC. Stored as JSON in filesDir.
 */
@Serializable
data class ClipEntry(
    val ts: Long,
    /** text | html | image */
    val kind: String,
    /** Plain text shown in the list (stripped of HTML for html entries). */
    val text: String = "",
    val hash: String,
    /** Absolute path of the stored image bytes (kind=image only). */
    val file: String? = null,
)

object ClipHistory {
    private const val MAX = 30
    private const val MAX_TEXT = 10_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private fun storeFile(context: Context) = File(context.filesDir, "cliphist.json")
    private fun imageDir(context: Context): File =
        File(context.filesDir, "cliphist").apply { mkdirs() }

    /** Newest-first snapshot of the history (empty list when nothing is stored). */
    fun list(context: Context): List<ClipEntry> = synchronized(this) { load(context) }

    /** Adds (or refreshes) an entry; deduped by hash — a repeat moves it to the top. */
    fun add(context: Context, kind: String, text: String, hash: String, imageBytes: ByteArray? = null) {
        synchronized(this) {
            val items = load(context).toMutableList()
            val removed = items.filter { it.hash == hash }
            items.removeAll { it.hash == hash }
            removed.forEach { e -> e.file?.let { p -> runCatching { File(p).delete() } } }

            var filePath: String? = null
            if (imageBytes != null) {
                val f = File(imageDir(context), "$hash.img")
                val ok = runCatching {
                    if (!f.exists()) f.writeBytes(imageBytes)
                    true
                }.getOrDefault(false)
                if (ok) filePath = f.absolutePath
            }

            items.add(
                0,
                ClipEntry(
                    ts = System.currentTimeMillis() / 1000,
                    kind = kind,
                    text = text.take(MAX_TEXT),
                    hash = hash,
                    file = filePath,
                ),
            )

            // Cap the list and drop images of evicted entries.
            while (items.size > MAX) {
                val old = items.removeAt(items.size - 1)
                old.file?.let { p -> runCatching { File(p).delete() } }
            }
            save(context, items)
            runCatching { com.zerosynk.app.send.ClipWidget.refresh(context) }
        }
    }

    fun clear(context: Context) {
        synchronized(this) {
            load(context).forEach { e -> e.file?.let { p -> runCatching { File(p).delete() } } }
            save(context, emptyList())
            runCatching { com.zerosynk.app.send.ClipWidget.refresh(context) }
        }
    }

    private fun load(context: Context): List<ClipEntry> {
        val f = storeFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ClipEntry.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<ClipEntry>) {
        runCatching {
            storeFile(context).writeText(
                json.encodeToString(ListSerializer(ClipEntry.serializer()), items),
            )
        }
    }
}
