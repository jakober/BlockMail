package com.jakober.klarmail.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.jakober.klarmail.data.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Verarbeitet den "Als gelesen markieren"-Button in der Benachrichtigung. */
class MarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getLongExtra("uid", -1L)
        val notifId = intent.getIntExtra("notifId", -1)
        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
        if (uid == -1L) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MailRepository.markSeen(uid)
            } finally {
                pending.finish()
            }
        }
    }
}
