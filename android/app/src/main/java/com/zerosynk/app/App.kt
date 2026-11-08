package com.zerosynk.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.zerosynk.app.design.Design

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        createChannels()
        Design.load(this)
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        // Some OEMs (HyperOS) refuse to raise an existing channel's importance via
        // createNotificationChannel — delete and recreate to upgrade LOW -> DEFAULT.
        val existing = nm.getNotificationChannel(CHANNEL_SYNC)
        if (existing != null && existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
            nm.deleteNotificationChannel(CHANNEL_SYNC)
        }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "Синхронизация",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Постоянное уведомление соединения с ПК"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_FILES,
                "Файлы",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Уведомления о передаче файлов"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MEDIA,
                "Музыка и видео",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Плеер для медиа, играющих на ПК"
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        )
    }

    companion object {
        // "sync2": a fresh channel id guarantees DEFAULT importance — some OEMs
        // refuse to raise importance of an existing channel even after delete+recreate.
        const val CHANNEL_SYNC = "sync2"
        const val CHANNEL_FILES = "files"
        const val CHANNEL_MEDIA = "media"
    }
}
