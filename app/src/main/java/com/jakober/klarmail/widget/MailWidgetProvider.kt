package com.jakober.klarmail.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.jakober.klarmail.MainActivity
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailMessage
import java.io.File

/** Homescreen-Widget: zeigt die neuesten Mails aus dem Posteingangs-Cache. */
class MailWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { id ->
            val rv = RemoteViews(context.packageName, R.layout.widget_mail_list)
            rv.setRemoteAdapter(R.id.widget_list, Intent(context, MailWidgetService::class.java))
            rv.setEmptyView(R.id.widget_list, R.id.widget_empty)
            rv.setTextViewText(R.id.widget_count, unreadLabel(context))

            // Kopfzeile: App öffnen
            rv.setOnClickPendingIntent(
                R.id.widget_header,
                PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            // Zeilen: jeweilige Mail direkt öffnen (uid kommt per Fill-in-Intent)
            rv.setPendingIntentTemplate(
                R.id.widget_list,
                PendingIntent.getActivity(
                    context, 1,
                    Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            appWidgetManager.updateAppWidget(id, rv)
        }
    }

    private fun unreadLabel(context: Context): String = try {
        val f = File(context.filesDir, com.jakober.klarmail.data.Prefs.inboxCacheFileName())
        val unread = if (f.exists()) {
            MailMessage.listFromJson(f.readText()).count { !it.seen }
        } else 0
        if (unread > 0) context.getString(R.string.svc_widget_unread, unread) else ""
    } catch (e: Exception) {
        ""
    }

    companion object {
        /** Widget-Inhalte auffrischen, z. B. nach jeder Posteingangs-Änderung. */
        fun notifyData(context: Context) {
            runCatching {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = mgr.getAppWidgetIds(
                    ComponentName(context, MailWidgetProvider::class.java)
                )
                if (ids.isNotEmpty()) {
                    mgr.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
                    // Kopfzeile (Ungelesen-Zähler) mit aktualisieren
                    val provider = MailWidgetProvider()
                    provider.onUpdate(context, mgr, ids)
                }
            }
        }
    }
}
