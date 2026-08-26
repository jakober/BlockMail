package com.jakober.klarmail.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.jakober.klarmail.data.MailRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Verarbeitet die Aktions-Buttons der Mail-Benachrichtigung:
 * Als gelesen markieren, Archivieren und Löschen.
 */
class MarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val uid = intent.getLongExtra("uid", -1L)
        val notifId = intent.getIntExtra("notifId", -1)
        // Konto der Mail ("" = aktives Konto) — sonst träfe die Aktion bei
        // mehreren Postfächern die falsche UID
        val account = intent.getStringExtra("account").orEmpty()
        if (notifId != -1) {
            NotificationManagerCompat.from(context).cancel(notifId)
        }
        if (uid == -1L) return
        val action = intent.action
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "com.jakober.klarmail.NOTIF_ARCHIVE" ->
                        MailRepository.archiveInboxByUid(uid, account)
                    "com.jakober.klarmail.NOTIF_DELETE" ->
                        MailRepository.deleteInboxByUid(uid, account)
                    else -> MailRepository.markSeen(uid, account)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
