package com.jakober.klarmail.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailMessage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Liefert die Widget-Zeilen aus dem lokalen Posteingangs-Cache. */
class MailWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {

        private var mails: List<MailMessage> = emptyList()

        override fun onCreate() {}

        override fun onDataSetChanged() {
            mails = try {
                val f = File(context.filesDir, com.jakober.klarmail.data.Prefs.inboxCacheFileName())
                if (f.exists()) MailMessage.listFromJson(f.readText()).take(20) else emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        }

        override fun getCount(): Int = mails.size

        override fun getViewAt(position: Int): RemoteViews {
            val m = mails[position]
            val rv = RemoteViews(context.packageName, R.layout.widget_mail_item)
            rv.setTextViewText(R.id.widget_from, m.from)
            rv.setTextViewText(R.id.widget_subject, m.subject)
            rv.setTextViewText(R.id.widget_time, timeLabel(m.date))
            rv.setTextColor(
                R.id.widget_from,
                if (m.seen) context.getColor(R.color.widget_text_primary)
                else context.getColor(R.color.brand_orange)
            )
            rv.setOnClickFillInIntent(
                R.id.widget_item_root,
                Intent().putExtra("open_uid", m.uid)
            )
            return rv
        }

        private fun timeLabel(millis: Long): String {
            val now = Calendar.getInstance()
            val then = Calendar.getInstance().apply { time = Date(millis) }
            val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
                now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
            val pattern = if (sameDay) "HH:mm" else "dd.MM."
            return SimpleDateFormat(pattern, Locale.GERMAN).format(Date(millis))
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long =
            mails.getOrNull(position)?.uid ?: position.toLong()
        override fun hasStableIds(): Boolean = true
        override fun onDestroy() {}
    }
}
