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
 * Nimmt Aktions-Buttons ALTER Mail-Benachrichtigungen entgegen (vor dem
 * Update gepostet) und reicht sie an den Sync-Dienst weiter: Ein Receiver
 * bekommt vom System nur ~10 Sekunden — der IMAP-Verbindungsaufbau darf
 * aber allein 15 Sekunden dauern, was regelmäßig in "App reagiert nicht"
 * endete. Neue Benachrichtigungen zeigen direkt auf den Dienst.
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
        val action = when (intent.action) {
            "com.jakober.klarmail.NOTIF_ARCHIVE" -> MailSyncService.ACTION_NOTIF_ARCHIVE
            "com.jakober.klarmail.NOTIF_DELETE" -> MailSyncService.ACTION_NOTIF_DELETE
            else -> MailSyncService.ACTION_NOTIF_READ
        }
        try {
            context.startForegroundService(
                Intent(context, MailSyncService::class.java).apply {
                    this.action = action
                    putExtra("uid", uid)
                    putExtra("account", account)
                }
            )
        } catch (_: Exception) {
            // Rückfall: Dienststart nicht erlaubt (seltene Hintergrund-Lage) —
            // dann im Zeitbudget des Receivers selbst versuchen
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    runCatching {
                        when (action) {
                            MailSyncService.ACTION_NOTIF_ARCHIVE ->
                                MailRepository.archiveInboxByUid(uid, account)
                            MailSyncService.ACTION_NOTIF_DELETE ->
                                MailRepository.deleteInboxByUid(uid, account)
                            else -> MailRepository.markSeen(uid, account)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
