package com.jakober.klarmail.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jakober.klarmail.MailApp
import com.jakober.klarmail.MainActivity
import com.jakober.klarmail.R
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.UIDFolder

/**
 * Kernlogik der Mail-Prüfung: neue Mails über die UID-Merkliste erkennen,
 * benachrichtigen, Inhalte vorladen und Lese-Markierungen abgleichen.
 * Vom Push-Dienst (IDLE-Schleife) UND vom Wächter-Worker genutzt — braucht
 * deshalb nur einen Context statt eines laufenden Dienstes.
 */
object MailChecker {

    private const val STATUS_NOTIF_ID = 4300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Eigenständige Einmal-Prüfung mit eigener Verbindung.
     * Liefert die Anzahl neuer Mails, -1 bei Verbindungsfehler.
     */
    fun checkOnce(context: Context): Int {
        if (!Prefs.isConfigured) return 0
        return try {
            val store = MailRepository.openStore()
            try {
                val inbox = store.getFolder("INBOX") as IMAPFolder
                inbox.open(Folder.READ_ONLY)
                val newCount = processNewMessages(context, inbox)
                syncFlags(context, inbox)
                newCount
            } finally {
                runCatching { store.close() }
            }
        } catch (_: Exception) {
            -1
        }
    }

    /**
     * Meldet alle Mails mit UID oberhalb der Merkliste und rückt sie vor.
     * Liefert die Anzahl der neu gemeldeten Mails (ohne blockierte).
     */
    fun processNewMessages(context: Context, inbox: IMAPFolder): Int {
        var newCount = 0
        try {
            val count = inbox.messageCount
            if (count <= 0) return 0
            val maxUid = inbox.uidNext.let { next ->
                if (next > 0) next - 1 else inbox.getUID(inbox.getMessage(count))
            }
            val lastUid = Prefs.lastPushUid
            if (lastUid <= 0L) {
                // Erststart: aktuellen Stand merken, ohne alte Mails zu melden
                Prefs.lastPushUid = maxUid
                return 0
            }
            if (maxUid <= lastUid) return 0
            val msgs = inbox.getMessagesByUID(lastUid + 1, maxUid)
            if (msgs.isNotEmpty()) {
                val fp = FetchProfile().apply {
                    add(FetchProfile.Item.ENVELOPE)
                    add(FetchProfile.Item.FLAGS)
                    add(FetchProfile.Item.CONTENT_INFO)
                    add(UIDFolder.FetchProfileItem.UID)
                }
                inbox.fetch(msgs, fp)
                val toPrefetch = mutableListOf<Long>()
                for (m in msgs) {
                    try {
                        val uid = inbox.getUID(m)
                        if (uid <= lastUid) continue
                        val mail = MailRepository.toMailMessage(uid, m)
                        val addr = mail.fromAddress.lowercase()
                        when {
                            Prefs.isBlocked(addr) -> {
                                // Blockiert: sofort löschen, keine Benachrichtigung
                                scope.launch { MailRepository.deleteInboxByUid(uid) }
                            }
                            Prefs.isMuted(addr) -> {
                                // Stumm: als gelesen markieren, keine Benachrichtigung
                                MailRepository.onNewMessage(mail.copy(seen = true))
                                scope.launch { MailRepository.setInboxSeenByUid(uid) }
                                toPrefetch.add(uid)
                                newCount++
                            }
                            else -> {
                                MailRepository.onNewMessage(mail)
                                if (!mail.seen) {
                                    showNewMailNotification(
                                        context, mail.uid, mail.from, mail.fromAddress, mail.subject
                                    )
                                }
                                toPrefetch.add(uid)
                                newCount++
                            }
                        }
                    } catch (_: Exception) {
                    }
                }
                // Inhalte der neuen Mails direkt herunterladen (eigene Verbindung,
                // nacheinander), damit das Öffnen später sofort aus dem Cache geht.
                if (toPrefetch.isNotEmpty()) {
                    scope.launch {
                        toPrefetch.forEach { MailRepository.prefetchBody(it) }
                    }
                }
            }
            Prefs.lastPushUid = maxUid
        } catch (_: Exception) {
        }
        return newCount
    }

    /**
     * Gleicht die Lese-Markierungen der letzten Inbox-Mails ab: extern gelesene
     * Mails werden in der App aktualisiert und ihre Benachrichtigung entfernt.
     */
    fun syncFlags(context: Context, inbox: IMAPFolder) {
        try {
            val count = inbox.messageCount
            if (count <= 0) return
            val start = kotlin.math.max(1, count - 40)
            val msgs = inbox.getMessages(start, count)
            val fp = FetchProfile().apply {
                add(FetchProfile.Item.FLAGS)
                add(UIDFolder.FetchProfileItem.UID)
            }
            inbox.fetch(msgs, fp)
            val seenByUid = HashMap<Long, Boolean>()
            for (m in msgs) {
                val uid = inbox.getUID(m)
                val seen = m.flags.contains(javax.mail.Flags.Flag.SEEN)
                seenByUid[uid] = seen
                if (seen) {
                    // Benachrichtigung entfernen, falls die Mail woanders gelesen wurde
                    NotificationManagerCompat.from(context).cancel((uid % Int.MAX_VALUE).toInt())
                }
            }
            MailRepository.applyRemoteFlags(seenByUid)
        } catch (_: Exception) {
        }
    }

    fun showNewMailNotification(
        context: Context,
        uid: Long,
        from: String,
        fromAddress: String,
        subject: String
    ) {
        val notifId = (uid % Int.MAX_VALUE).toInt()
        // Absender-Avatar wie in der Mail-Liste (lädt ggf. aus dem Netz —
        // wir laufen hier bereits auf einem IO-Thread)
        val avatar = runCatching {
            NotificationUtil.senderAvatarBitmap(from, fromAddress)
        }.getOrNull() ?: NotificationUtil.logoBitmap(context)

        val markReadIntent = Intent(context, MarkReadReceiver::class.java).apply {
            action = "com.jakober.klarmail.MARK_READ"
            putExtra("uid", uid)
            putExtra("notifId", notifId)
        }
        val markReadPending = PendingIntent.getBroadcast(
            context, notifId, markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPending = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_uid", uid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, MailApp.CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notif_mail)
            .setLargeIcon(avatar)
            .setColor(0xFFE85510.toInt())
            .setContentTitle(from)
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subject))
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, "Als gelesen markieren", markReadPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (_: SecurityException) {
        }
    }

    fun statusNotification(context: Context, text: String, timeoutMs: Long = 0) {
        val openIntent = PendingIntent.getActivity(
            context, STATUS_NOTIF_ID, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, MailApp.CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notif_mail)
            .setLargeIcon(NotificationUtil.logoBitmap(context))
            .setColor(0xFFE85510.toInt())
            .setContentTitle("BlockMail")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(STATUS_NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
