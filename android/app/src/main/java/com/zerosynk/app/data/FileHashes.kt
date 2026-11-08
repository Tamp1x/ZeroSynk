package com.zerosynk.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Full SHA-256 hashes of files received from the PC (p. 15): when the same content
 * arrives again within 24 h the download is skipped and the PC's outbox is cleared.
 * JSON in filesDir, capped — no database in this app.
 */
@Serializable
data class ReceivedHash(val hash: String, val ts: Long)

object FileHashes {
    private const val MAX = 200
    private const val WINDOW_SEC = 24 * 60 * 60L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private fun storeFile(context: Context) = File(context.filesDir, "filehashes.json")

    /** True when the same content was received within the last 24 h. */
    fun recent(context: Context, hash: String): Boolean {
        val now = System.currentTimeMillis() / 1000
        synchronized(this) {
            return load(context).any {
                it.hash.equals(hash, ignoreCase = true) && now - it.ts < WINDOW_SEC
            }
        }
    }

    fun remember(context: Context, hash: String) {
        synchronized(this) {
            val now = System.currentTimeMillis() / 1000
            val items = load(context)
                .filter { now - it.ts < WINDOW_SEC && !it.hash.equals(hash, ignoreCase = true) }
                .toMutableList()
            items.add(0, ReceivedHash(hash.lowercase(), now))
            while (items.size > MAX) items.removeAt(items.size - 1)
            save(context, items)
        }
    }

    private fun load(context: Context): List<ReceivedHash> {
        val f = storeFile(context)
        if (!f.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(ListSerializer(ReceivedHash.serializer()), f.readText())
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, items: List<ReceivedHash>) {
        runCatching {
            storeFile(context).writeText(
                json.encodeToString(ListSerializer(ReceivedHash.serializer()), items),
            )
        }
    }
}
