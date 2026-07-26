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
     * Weckt fällige zurückgestellte Mails: Erinnerungs-Benachrichtigung,
     * auf dem Server wieder als ungelesen markieren und Liste aktualisieren,
     * damit die Mail erneut oben unter "Neu" erscheint.
     */
    fun processDueSnoozes(context: Context) {
        val due = Prefs.snoozes().filter { it.until <= System.currentTimeMillis() }
        if (due.isEmpty()) return
        due.forEach { s ->
            Prefs.removeSnooze(s.uid)
            scope.launch { MailRepository.setInboxSeenByUid(s.uid, seen = false) }
            showNewMailNotification(
                context, s.uid, s.from, s.address, "⏰ Erinnerung: ${s.subject}"
            )
        }
        scope.launch { runCatching { MailRepository.refresh() } }
    }

    /**
     * Verschickt fällige geplante Mails aus der Ausgangs-Warteschlange.
     * Bei Fehlern bleibt die Mail in der Warteschlange; der nächste Versuch
     * wird 15 Minuten nach hinten geschoben, damit nichts im Minutentakt
     * fehlschlägt.
     */
    fun processOutbox(context: Context) {
        val due = Prefs.outbox().filter { it.sendAt <= System.currentTimeMillis() }
        if (due.isEmpty()) return
        scope.launch {
            due.forEach { m ->
                try {
                    MailRepository.send(
                        to = m.to, subject = m.subject, body = m.body,
                        html = m.html, cc = m.cc, bcc = m.bcc,
                        account = m.account
                    )
                    Prefs.removeOutbox(m.id)
                    statusNotification(context, "Geplante Mail gesendet: ${m.subject}", timeoutMs = 15_000)
                } catch (e: Exception) {
                    Prefs.saveOutbox(Prefs.outbox().map {
                        if (it.id == m.id) it.copy(sendAt = System.currentTimeMillis() + 15 * 60_000) else it
                    })
                    statusNotification(
                        context,
                        "Geplantes Senden fehlgeschlagen (${m.subject}) — neuer Versuch in 15 min"
                    )
                }
            }
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
                                // VIP-Filter: bei „Nur VIP benachrichtigen“ kommen
                                // andere Absender lautlos an (Mail erscheint normal)
                                val notifyAllowed = !Prefs.vipOnlyNotifications ||
                                    Prefs.isVip(addr)
                                if (!mail.seen && notifyAllowed) {
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

        // Konversations-Benachrichtigung (Android 11+): Der Absender-Avatar
        // erscheint damit groß LINKS, das App-Icon nur als kleines Abzeichen.
        // Voraussetzung ist ein langlebiger dynamischer Shortcut pro Absender.
        val senderName = from.ifBlank { fromAddress.ifBlank { "Absender" } }
        val personIcon = runCatching {
            NotificationUtil.senderAvatarIcon(senderName, fromAddress)
        }.getOrNull()
        val senderPerson = androidx.core.app.Person.Builder()
            .setName(senderName)
            .setKey(fromAddress.ifBlank { senderName })
            .apply { personIcon?.let { setIcon(it) } }
            .build()
        val shortcutId = "sender_" + fromAddress.ifBlank { senderName }.lowercase()
        runCatching {
            val shortcut = androidx.core.content.pm.ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(senderName)
                .setLongLived(true)
                .setPerson(senderPerson)
                .apply { personIcon?.let { setIcon(it) } }
                .setIntent(
                    Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                )
                .build()
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        }
        val messagingStyle = NotificationCompat.MessagingStyle(
            androidx.core.app.Person.Builder().setName("Ich").build()
        ).addMessage(subject, System.currentTimeMillis(), senderPerson)

        // Aktionen über den Mehrzweck-Receiver: Archivieren und Löschen direkt
        // aus der Benachrichtigung (eigene requestCodes je Aktion!)
        fun actionPending(actionName: String, requestCode: Int): PendingIntent {
            val intent = Intent(context, MarkReadReceiver::class.java).apply {
                action = actionName
                putExtra("uid", uid)
                putExtra("notifId", notifId)
            }
            return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        val archivePending = actionPending("com.jakober.klarmail.NOTIF_ARCHIVE", notifId + 1)
        val deletePending = actionPending("com.jakober.klarmail.NOTIF_DELETE", notifId + 2)
        val readPending = actionPending("com.jakober.klarmail.NOTIF_READ", notifId + 3)
        val openPending = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).apply {
                putExtra("open_uid", uid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Schnellantwort direkt aus der Benachrichtigung (RemoteInput);
        // der Versand läuft über den Sync-Dienst, der genug Zeit dafür hat
        val remoteInput = androidx.core.app.RemoteInput.Builder(MailSyncService.KEY_QUICK_REPLY)
            .setLabel("Antworten …")
            .build()
        val replyIntent = Intent(context, MailSyncService::class.java).apply {
            action = MailSyncService.ACTION_SEND_REPLY
            putExtra("uid", uid)
            putExtra("address", fromAddress)
            putExtra("subject", subject)
            putExtra("notifId", notifId)
        }
        val replyPending = PendingIntent.getForegroundService(
            context, notifId, replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val replyAction = NotificationCompat.Action.Builder(0, "Antworten", replyPending)
            .addRemoteInput(remoteInput)
            .setAllowGeneratedReplies(true)
            .build()

        val builder = NotificationCompat.Builder(context, MailApp.CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notif_mail)
            // Fallback für Android < 11 (dort greift der Konversations-Stil nicht)
            .setLargeIcon(avatar)
            .setColor(0xFFE85510.toInt())
            .setContentTitle(from)
            .setContentText(subject)
            .setStyle(messagingStyle)
            .setShortcutId(shortcutId)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // Aktions-Knöpfe nach Nutzer-Auswahl (Android zeigt höchstens 3 an)
        Prefs.notifActions.forEach { key ->
            when (key) {
                "reply" -> builder.addAction(replyAction)
                "read" -> builder.addAction(0, "Als gelesen", readPending)
                "archive" -> builder.addAction(0, "Archivieren", archivePending)
                "delete" -> builder.addAction(0, "Löschen", deletePending)
            }
        }
        val notification = builder.build()

        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (_: SecurityException) {
        }
    }

    /** Verschickt eine Schnellantwort aus der Benachrichtigung. */
    fun sendReplyAsync(
        context: Context,
        uid: Long,
        address: String,
        rawSubject: String,
        text: String,
        notifId: Int
    ) {
        scope.launch {
            val result = try {
                val cleaned = rawSubject.removePrefix("⏰ Erinnerung: ")
                val subject = if (cleaned.startsWith("Re:", ignoreCase = true)) cleaned
                else "Re: $cleaned"
                MailRepository.send(to = address, subject = subject, body = text)
                if (uid > 0) runCatching { MailRepository.markSeen(uid) }
                "Antwort gesendet ✓"
            } catch (e: Exception) {
                "Antwort fehlgeschlagen: ${e.message?.take(60) ?: "Fehler"}"
            }
            // Die Mail-Benachrichtigung durch eine kurze Bestätigung ersetzen
            val confirm = NotificationCompat.Builder(context, MailApp.CHANNEL_NEW_MAIL)
                .setSmallIcon(R.drawable.ic_notif_mail)
                .setColor(0xFFE85510.toInt())
                .setContentTitle("BlockMail")
                .setContentText(result)
                .setTimeoutAfter(8_000)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(notifId, confirm)
            } catch (_: SecurityException) {
            }
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
