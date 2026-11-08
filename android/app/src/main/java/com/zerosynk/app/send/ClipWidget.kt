package com.zerosynk.app.send

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.zerosynk.app.R
import com.zerosynk.app.data.ClipHistory
import com.zerosynk.app.data.Prefs
import com.zerosynk.app.service.SyncService

class ClipWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val latest = ClipHistory.list(context).firstOrNull()
        val prefs = Prefs.get(context)
        val status = when {
            SyncService.isConnected() -> "ПК подключён"
            prefs.paired -> "ПК не в сети"
            else -> "Сначала подключите ПК"
        }
        ids.forEach { id ->
            val views = RemoteViews(context.packageName, R.layout.widget_clip).apply {
                setTextViewText(R.id.widget_status, status)
                setTextViewText(
                    R.id.widget_preview,
                    when {
                        latest == null -> "Буфер пока пуст"
                        latest.kind == "image" -> "Последнее: изображение"
                        else -> latest.text.take(70)
                    },
                )
                val intent = Intent(context, SendClipboardActivity::class.java)
                val pending = PendingIntent.getActivity(context, id, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                setOnClickPendingIntent(R.id.widget_send, pending)
            }
            manager.updateAppWidget(id, views)
        }
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(android.content.ComponentName(context, ClipWidget::class.java))
            if (ids.isNotEmpty()) ClipWidget().onUpdate(context, manager, ids)
        }
    }
}
