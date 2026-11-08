package com.zerosynk.app.net

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object Api {

    data class PingResult(val pcName: String?, val proto: Int?)
    data class PairResult(
        val ok: Boolean,
        val token: String?,
        val pcName: String?,
        val error: String?,
        /** Session password handed out with the token (p. 18), null when none is set. */
        val pw: String? = null,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.MINUTES)
        .build()

    /** The upload currently in flight — cancelled by the notification's «Отменить» action. */
    @Volatile
    private var currentCall: Call? = null

    fun cancelUpload() {
        try { currentCall?.cancel() } catch (_: Exception) {}
    }

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
                        pw = json["pw"]?.jsonPrimitive?.contentOrNull,
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

    /** Appends the session password (p. 18) to an authenticated URL when one is set. */
    private fun withPw(url: String, pw: String?): String =
        if (pw.isNullOrEmpty()) url else "$url&pw=${Uri.encode(pw)}"

    /** Streams a content URI to the PC. [batch] groups multi-file sends so the PC
     *  cleans up old files only when a new batch starts; [total] tells the PC how
     *  many files to expect so it can show ONE notification per batch.
     *  [hash] — full SHA-256 of the file: the PC skips the body when it already
     *  has that hash (dedupe, p. 15). [onProgress] reports bytes written.
     *  Returns true on success (a dedupe reply counts as delivered). */
    suspend fun upload(
        resolver: ContentResolver,
        host: String,
        port: Int,
        token: String,
        uri: Uri,
        batch: String? = null,
        total: Int? = null,
        hash: String? = null,
        pw: String? = null,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val name = queryDisplayName(resolver, uri) ?: "file"
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val body = uriRequestBody(resolver, uri, mime, onProgress)
            val batchPart = if (batch != null) "&batch=${Uri.encode(batch)}" else ""
            val totalPart = if (total != null) "&total=$total" else ""
            val hashPart = if (hash != null) "&hash=$hash" else ""
            val url = withPw(
                "http://$host:$port/api/upload?token=$token&name=${Uri.encode(name)}$batchPart$totalPart$hashPart",
                pw,
            )
            val req = Request.Builder().url(url).post(body).build()
            val call = client.newCall(req)
            currentCall = call
            try {
                call.execute().use { r ->
                    if (!r.isSuccessful) return@withContext false
                    val text = r.body?.string() ?: return@withContext true
                    val json = ProtoJson.parseToJsonElement(text).jsonObject
                    // ok=true, dedupe=true → the PC already has this SHA-256 (p. 15)
                    json["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                }
            } finally {
                if (currentCall === call) currentCall = null
            }
        } catch (e: Exception) {
            android.util.Log.e("ZeroSynk", "upload error for $uri", e)
            false
        }
    }

    /** Full SHA-256 of a content URI as lowercase hex (null when unreadable). */
    fun sha256(resolver: ContentResolver, uri: Uri): String? = try {
        val input = resolver.openInputStream(uri) ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        input.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /** GET for a staged file. [range] — "bytes=N-" resumes a partial download (p. 14). */
    fun openStream(
        host: String,
        port: Int,
        token: String,
        fileId: String,
        range: String? = null,
        pw: String? = null,
    ): Request {
        val b = Request.Builder()
            .url(withPw("http://$host:$port/api/file/$fileId?token=$token", pw))
            .get()
        if (range != null) b.header("Range", range)
        return b.build()
    }

    // ---- file manager on the PC (p. 12) ----

    data class ListFile(val name: String, val size: Long, val mtime: Long)
    data class ListResult(
        val ok: Boolean,
        val path: String,
        val parent: String?,
        val dirs: List<String>,
        val files: List<ListFile>,
    )

    /** Lists a folder on the PC. Empty [ListResult.path] request = PC user profile. */
    suspend fun list(host: String?, port: Int, token: String?, path: String, pw: String? = null): ListResult? =
        withContext(Dispatchers.IO) {
            if (host.isNullOrEmpty() || token.isNullOrEmpty()) return@withContext null
            try {
                val url = withPw("http://$host:$port/api/list?token=$token&path=${Uri.encode(path)}", pw)
                val req = Request.Builder().url(url).get().build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@withContext null
                    val text = r.body?.string() ?: return@withContext null
                    val json = ProtoJson.parseToJsonElement(text).jsonObject
                    val ok = json["ok"]?.jsonPrimitive?.booleanOrNull ?: false
                    if (!ok) return@withContext ListResult(false, path, null, emptyList(), emptyList())
                    val dirs = json["dirs"]?.jsonArray
                        ?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    val files = json["files"]?.jsonArray?.mapNotNull { el ->
                        val o = el.jsonObject
                        val name = o["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        ListFile(
                            name = name,
                            size = o["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                            mtime = o["mtime"]?.jsonPrimitive?.longOrNull ?: 0L,
                        )
                    } ?: emptyList()
                    ListResult(
                        ok = true,
                        path = json["path"]?.jsonPrimitive?.contentOrNull ?: path,
                        parent = json["parent"]?.jsonPrimitive?.contentOrNull,
                        dirs = dirs,
                        files = files,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Direct URL for streaming one PC file into a viewer / browser (p. 12). */
    fun rawUrl(host: String?, port: Int, token: String?, path: String, pw: String? = null): String =
        withPw("http://$host:$port/api/raw?token=$token&path=${Uri.encode(path)}", pw)

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

    fun uriRequestBody(
        resolver: ContentResolver,
        uri: Uri,
        mime: String,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): RequestBody {
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
                input.use {
                    if (onProgress == null) {
                        sink.writeAll(it.source())
                        return
                    }
                    // Manual copy so the notification can show byte progress (p. 14).
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = it.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent, length)
                    }
                }
            }
        }
    }
}
