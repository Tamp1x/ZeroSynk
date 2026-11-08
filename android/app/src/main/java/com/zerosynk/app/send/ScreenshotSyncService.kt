package com.zerosynk.app.send

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.zerosynk.app.App
import com.zerosynk.app.R
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.service.SyncService
import java.io.File

/** User-consented screen capture. A single frame is sent at most every ten minutes. */
class ScreenshotSyncService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var latestFrame: Bitmap? = null
    private var captureScheduled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT, 0) ?: 0
        val resultData = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultData == null || resultCode == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val type = if (Build.VERSION.SDK_INT >= 29) android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            androidx.core.app.ServiceCompat.startForeground(this, NOTIF_ID, notification(), type)
            thread = HandlerThread("ZeroSynkScreenCapture").also { it.start() }
            handler = Handler(thread!!.looper)
            val manager = getSystemService(MediaProjectionManager::class.java)
            projection = manager.getMediaProjection(resultCode, resultData)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, handler)

            val metrics = if (Build.VERSION.SDK_INT >= 30) {
                val bounds = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
                DisplayMetrics().apply {
                    widthPixels = bounds.width()
                    heightPixels = bounds.height()
                    densityDpi = resources.displayMetrics.densityDpi
                }
            } else resources.displayMetrics
            reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
            reader?.setOnImageAvailableListener({ source -> updateFrame(source.acquireLatestImage()) }, handler)
            display = projection?.createVirtualDisplay(
                "ZeroSynk screenshot sync", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler,
            )
        } catch (_: Exception) {
            Prefs.get(this).screenshotSync = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun updateFrame(image: Image?) {
        if (image == null) return
        image.use {
            try {
                val plane = image.planes[0]
                val pixelStride = plane.pixelStride
                val rowPadding = plane.rowStride - pixelStride * image.width
                val paddedWidth = image.width + rowPadding / pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = if (paddedWidth == image.width) padded
                    else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
                latestFrame?.recycle()
                latestFrame = bitmap
                if (!captureScheduled) {
                    captureScheduled = true
                    handler?.postDelayed({ captureLatestFrame() }, INTERVAL_MS)
                }
            } catch (_: Exception) { }
        }
    }

    private fun captureLatestFrame() {
        try {
            val frame = latestFrame ?: return
            val now = System.currentTimeMillis()
            val dir = File(cacheDir, "screenshots").apply {
                mkdirs()
                listFiles()?.forEach { if (now - it.lastModified() > 86_400_000L) it.delete() }
            }
            val file = File(dir, "screenshot-${now}.jpg")
            file.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val upload = Intent(this, SyncService::class.java).apply {
                action = SyncService.ACTION_SEND_FILE
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(this, upload)
        } catch (_: Exception) {
            // A capture/write failure leaves the service active for the next interval.
        } finally {
            handler?.postDelayed({ captureLatestFrame() }, INTERVAL_MS)
        }
    }

    private fun notification(): Notification = NotificationCompat.Builder(this, App.CHANNEL_SYNC)
        .setSmallIcon(R.drawable.ic_stat_sync)
        .setContentTitle("Передача скриншотов включена")
        .setContentText("Снимок экрана будет отправляться на ПК раз в 10 минут")
        .setOngoing(true)
        .setSilent(true)
        .build()

    override fun onDestroy() {
        reader?.setOnImageAvailableListener(null, null)
        display?.release()
        reader?.close()
        projection?.stop()
        latestFrame?.recycle()
        latestFrame = null
        thread?.quitSafely()
        Prefs.get(this).screenshotSync = false
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_RESULT = "resultCode"
        const val EXTRA_DATA = "resultData"
        private const val NOTIF_ID = 7021
        private const val INTERVAL_MS = 10 * 60 * 1000L
    }
}
