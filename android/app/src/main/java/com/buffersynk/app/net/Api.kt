package com.buffersynk.app.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

object Api {

    data class PingResult(val pcName: String?, val proto: Int?)
    data class PairResult(val ok: Boolean, val token: String?, val pcName: String?, val error: String?)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun ping(host: String, port: Int): PingResult? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url("http://$host:$port/api/ping").get().build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val body = r.body?.string() ?: return@withContext null
                val json = ProtoJson.parseToJsonElement(body).jsonObject
                PingResult(
                    pcName = json["pc"]?.jsonPrimitive?.contentOrNull,
                    proto = json["proto"]?.jsonPrimitive?.intOrNull,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun pair(host: String, port: Int, pin: String): PairResult = withContext(Dispatchers.IO) {
        try {
            val bodyJson = """{"pin":"${pin.trim()}"}"""
            val req = Request.Builder()
                .url("http://$host:$port/api/pair")
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(req).execute().use { r ->
                val body = r.body?.string() ?: """{"ok":false}"""
                val json = ProtoJson.parseToJsonElement(body).jsonObject
                val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                if (ok) {
                    PairResult(
                        ok = true,
                        token = json["token"]?.jsonPrimitive?.contentOrNull,
                        pcName = json["pcName"]?.jsonPrimitive?.contentOrNull,
                        error = null,
                    )
                } else {
                    PairResult(
                        ok = false,
                        token = null,
                        pcName = null,
                        error = json["error"]?.jsonPrimitive?.contentOrNull ?: "http_${r.code}",
                    )
                }
            }
        } catch (e: Exception) {
            PairResult(false, null, null, e.javaClass.simpleName)
        }
    }

    /** Streams a content URI to the PC. [batch] groups multi-file sends so the PC
     *  cleans up old files only when a new batch starts; [total] tells the PC how
     *  many files to expect so it can show ONE notification per batch.
     *  Returns true on success. */
    suspend fun upload(
        resolver: ContentResolver,
        host: String,
        port: Int,
        token: String,
        uri: Uri,
        batch: String? = null,
        total: Int? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(resolver, uri) ?: "file"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val body = uriRequestBody(resolver, uri, mime)
            val batchPart = if (batch != null) "&batch=${Uri.encode(batch)}" else ""
            val totalPart = if (total != null) "&total=$total" else ""
            val url = "http://$host:$port/api/upload?token=$token&name=${Uri.encode(name)}$batchPart$totalPart"
            val req = Request.Builder().url(url).post(body).build()
            client.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            android.util.Log.e("ZeroSynk", "upload error for $uri", e)
            false
        }
    }

    fun openStream(host: String, port: Int, token: String, fileId: String) =
        Request.Builder()
            .url("http://$host:$port/api/file/$fileId?token=$token")
            .get()
            .build()

    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        if (uri.scheme == "file") {
            // Cache copies from ShareTargetActivity are named "<timestamp>_<original>"
            return uri.lastPathSegment?.replaceFirst(Regex("^\\d+_"), "")
        }
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i) else null
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }

    fun uriRequestBody(resolver: ContentResolver, uri: Uri, mime: String): RequestBody {
        val length = try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0) c.getLong(i) else -1L
                } else -1L
            } ?: -1L
        } catch (_: Exception) {
            -1L
        }
        return object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun contentLength() = length
            override fun writeTo(sink: BufferedSink) {
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")
                input.use { sink.writeAll(it.source()) }
            }
        }
    }
}
