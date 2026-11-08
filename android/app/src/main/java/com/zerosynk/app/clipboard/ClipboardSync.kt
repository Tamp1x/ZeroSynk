package com.zerosynk.app.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import androidx.core.content.FileProvider
import com.zerosynk.app.data.ClipEntry
import com.zerosynk.app.data.ClipHistory
import com.zerosynk.app.data.LoopGuard
import com.zerosynk.app.design.Design
import com.zerosynk.app.net.Protocol
import com.zerosynk.app.net.WsMessage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

object ClipboardSync {

    private const val MAX_IMAGE_BYTES = Protocol.MAX_IMAGE_BYTES
    private const val MAX_SIDE = 2560

    /**
     * Reads the current clipboard into a protocol message.
     * Caller must be able to read the clipboard (app focused or trampoline activity focused).
     * Every read also lands in the local clipboard history (p. 7).
     */
    fun readClipboardMessage(context: Context): WsMessage? {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        // Rich text first: HTML clips expose a coerced plain text too, so prefer html.
        for (i in 0 until clip.itemCount) {
            val html = clip.getItemAt(i).htmlText
            if (!html.isNullOrEmpty()) {
                val hash = sha256Hex(html)
                ClipHistory.add(context, "html", plainFromHtml(html), hash)
                return Protocol.clipboardHtml(html, hash)
            }
        }

        // Then plain text.
        for (i in 0 until clip.itemCount) {
            val t = clip.getItemAt(i).text?.toString()
            if (!t.isNullOrEmpty()) {
                val hash = sha256Hex(t)
                ClipHistory.add(context, "text", t, hash)
                return Protocol.clipboardText(t, hash)
            }
        }

        // Then images referenced by a content URI (copied screenshots, browser images, …).
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            val enc = imageFromUri(context, uri) ?: continue
            ClipHistory.add(context, "image", "Изображение", sha256Hex(enc.raw), enc.raw)
            return Protocol.clipboardImage(
                base64Png = enc.base64,
                hash = sha256Hex(enc.raw),
                mime = if (enc.isPng) "image/png" else "image/jpeg",
            )
        }
        return null
    }

    /** Applies a clipboard message received from the PC. Safe to call from a background service. */
    fun applyIncoming(context: Context, msg: WsMessage) {
        when (msg.kind) {
            "text" -> {
                val t = msg.text ?: return
                val hash = msg.hash ?: sha256Hex(t)
                LoopGuard.lastAppliedHash = hash
                ClipHistory.add(context, "text", t, hash)
                setClip(context, ClipData.newPlainText(Design.displayName(), t))
            }
            "html" -> {
                val html = msg.text ?: return
                val hash = msg.hash ?: sha256Hex(html)
                LoopGuard.lastAppliedHash = hash
                val plain = plainFromHtml(html)
                ClipHistory.add(context, "html", plain, hash)
                setClip(context, ClipData.newHtmlText(Design.displayName(), plain, html))
            }
            "image" -> {
                val data = msg.data ?: return
                val bytes = try {
                    Base64.decode(data, Base64.NO_WRAP)
                } catch (_: Exception) {
                    return
                }
                if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return
                val hash = msg.hash ?: sha256Hex(bytes)
                LoopGuard.lastAppliedHash = hash
                ClipHistory.add(context, "image", "Изображение", hash, bytes)
                saveAndSetImage(context, bytes)
            }
        }
    }

    /** Copies a history entry back into the system clipboard (tap action). */
    fun copyEntry(context: Context, entry: ClipEntry) {
        when (entry.kind) {
            "image" -> {
                val bytes = entry.file?.let { p ->
                    runCatching { File(p).readBytes() }.getOrNull()
                } ?: return
                if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) return
                saveAndSetImage(context, bytes)
            }
            else -> {
                if (entry.text.isEmpty()) return
                LoopGuard.lastAppliedHash = entry.hash
                setClip(context, ClipData.newPlainText(Design.displayName(), entry.text))
            }
        }
    }

    /** Builds the message that sends a history entry back to the PC (long-press action). */
    fun messageForEntry(context: Context, entry: ClipEntry): WsMessage? = when (entry.kind) {
        "image" -> {
            val bytes = entry.file?.let { p ->
                runCatching { File(p).readBytes() }.getOrNull()
            } ?: return null
            if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES) {
                null
            } else {
                Protocol.clipboardImage(
                    Base64.encodeToString(bytes, Base64.NO_WRAP),
                    sha256Hex(bytes),
                    "image/png",
                )
            }
        }
        else -> if (entry.text.isEmpty()) null else Protocol.clipboardText(entry.text, sha256Hex(entry.text))
    }

    /** HTML → displayable plain text. */
    fun plainFromHtml(html: String): String =
        android.text.Html.fromHtml(html, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()

    private fun setClip(context: Context, clip: ClipData) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        // setPrimaryClip is allowed from a background service; post to main for safety.
        Handler(Looper.getMainLooper()).post {
            try {
                cm.setPrimaryClip(clip)
            } catch (_: Exception) {
            }
        }
    }

    private fun saveAndSetImage(context: Context, png: ByteArray) {
        try {
            val dir = File(context.cacheDir, "clip").apply { mkdirs() }
            dir.listFiles()?.forEach { f ->
                if (System.currentTimeMillis() - f.lastModified() > 3600_000) f.delete()
            }
            val file = File(dir, "clip-${System.currentTimeMillis()}.png")
            file.writeBytes(png)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val clip = ClipData(
                ClipDescription(Design.displayName(), arrayOf("image/png")),
                ClipData.Item(uri),
            )
            setClip(context, clip)
        } catch (_: Exception) {
        }
    }

    private class Encoded(val base64: String, val raw: ByteArray, val isPng: Boolean)

    private fun imageFromUri(context: Context, uri: Uri): Encoded? {
        return try {
            val decoded: Bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null
            var bmp = scaleDown(decoded, MAX_SIDE)

            pngOrNull(bmp)?.let { return Encoded(Base64.encodeToString(it, Base64.NO_WRAP), it, true) }
            var cur = bmp
            repeat(2) {
                cur = scaleDown(cur, (cur.width * 0.7f).toInt().coerceAtLeast(64))
                pngOrNull(cur)?.let { return Encoded(Base64.encodeToString(it, Base64.NO_WRAP), it, true) }
            }
            jpegOrNull(cur, 85)?.let { return Encoded(Base64.encodeToString(it, Base64.NO_WRAP), it, false) }
            jpegOrNull(cur, 60)?.let { return Encoded(Base64.encodeToString(it, Base64.NO_WRAP), it, false) }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= maxSide) return b
        val ratio = maxSide.toFloat() / m
        val scaled = Bitmap.createScaledBitmap(
            b,
            (b.width * ratio).toInt().coerceAtLeast(1),
            (b.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled != b) b.recycle()
        return scaled
    }

    private fun pngOrNull(b: Bitmap): ByteArray? = try {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, out)
        val bytes = out.toByteArray()
        if (bytes.size <= MAX_IMAGE_BYTES) bytes else null
    } catch (_: Exception) {
        null
    }

    private fun jpegOrNull(b: Bitmap, quality: Int): ByteArray? = try {
        val out = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val bytes = out.toByteArray()
        if (bytes.size <= MAX_IMAGE_BYTES) bytes else null
    } catch (_: Exception) {
        null
    }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
            .take(16)
}
