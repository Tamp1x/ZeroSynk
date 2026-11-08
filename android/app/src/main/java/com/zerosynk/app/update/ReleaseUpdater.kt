package com.zerosynk.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.zerosynk.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class ReleaseInfo(
    val version: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?,
    val sha256: String?,
)

sealed interface UpdateCheck {
    data class Available(val release: ReleaseInfo) : UpdateCheck
    data object UpToDate : UpdateCheck
    data class Error(val message: String) : UpdateCheck
}

object ReleaseUpdater {
    private const val RELEASE_URL = "https://api.github.com/repos/Tamp1x/ZeroSynk/releases/latest"
    private const val MAX_APK_BYTES = 120L * 1024 * 1024
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun check(): UpdateCheck = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "ZeroSynk-Android")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheck.Error(
                        if (response.code == 404) "Релиз пока не опубликован или репозиторий недоступен."
                        else "GitHub ответил HTTP ${response.code}.",
                    )
                }
                val body = response.body?.string() ?: return@withContext UpdateCheck.Error("Пустой ответ от GitHub.")
                val json = JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank()) return@withContext UpdateCheck.Error("В релизе нет номера версии.")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                var digest: String? = null
                if (assets != null) for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true) && name.contains("zerosynk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        digest = asset.optString("digest").removePrefix("sha256:").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                        break
                    }
                }
                val release = ReleaseInfo(
                    version = tag,
                    notes = json.optString("body").take(6000),
                    pageUrl = json.optString("html_url"),
                    apkUrl = apkUrl,
                    sha256 = digest,
                )
                if (compareVersions(release.version, BuildConfig.VERSION_NAME) <= 0) UpdateCheck.UpToDate
                else UpdateCheck.Available(release)
            }
        } catch (e: Exception) {
            UpdateCheck.Error(e.message ?: "Не удалось проверить релизы GitHub.")
        }
    }

    suspend fun download(context: Context, release: ReleaseInfo): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = requireNotNull(release.apkUrl) { "В релизе нет APK ZeroSynk." }
            val request = Request.Builder().url(url).header("User-Agent", "ZeroSynk-Android").build()
            client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "Загрузка APK: HTTP ${response.code}." }
                val body = requireNotNull(response.body) { "Пустой файл APK." }
                check(body.contentLength() in 1..MAX_APK_BYTES) { "Размер APK недопустим." }
                val targetDir = File(context.cacheDir, "updates").apply { mkdirs() }
                val partial = File(targetDir, "ZeroSynk.apk.part")
                val target = File(targetDir, "ZeroSynk.apk")
                partial.outputStream().use { out -> body.byteStream().use { input -> input.copyTo(out) } }
                check(partial.length() in 1..MAX_APK_BYTES) { "Загруженный APK имеет недопустимый размер." }
                if (release.sha256 != null) {
                    val actual = sha256(partial)
                    check(actual.equals(release.sha256, ignoreCase = true)) { "Контрольная сумма APK не совпала." }
                }
                check(partial.renameTo(target)) { "Не удалось сохранить APK." }
                target
            }
        }
    }

    fun install(context: Context, apk: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permission = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permission)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        val install = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(install)
        return true
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun compareVersions(left: String, right: String): Int {
        fun parts(version: String) = version.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(left)
        val b = parts(right)
        for (i in 0 until maxOf(a.size, b.size)) {
            val result = (a.getOrElse(i) { 0 }).compareTo(b.getOrElse(i) { 0 })
            if (result != 0) return result
        }
        return 0
    }
}
