package com.jakober.klarmail.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.mail.FetchProfile
import javax.mail.Folder
import javax.mail.Store
import javax.mail.UIDFolder
import kotlin.math.min

/**
 * Hält eine dauerhafte IMAP-IDLE-Verbindung zu Gmail offen, damit neue Mails
 * sofort (Push) gemeldet werden. Verpasste Mails aus Verbindungslücken werden
 * beim Neuverbinden über eine fortlaufende UID-Merkliste nachgeholt.
 */
class MailSyncService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null

    @Volatile
    private var activeStore: Store? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                SERVICE_NOTIF_ID, serviceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(SERVICE_NOTIF_ID, serviceNotification())
        }
        when (intent?.action) {
            ACTION_RESTART -> restartIdleLoop()
            ACTION_CHECK_NOW -> {
                if (idleJob?.isActive != true) startIdleLoop()
                checkNowAsync()
            }
            ACTION_NEWSLETTER -> {
                if (idleJob?.isActive != true) startIdleLoop()
                scope.launch {
                    runCatching {
                        com.jakober.klarmail.data.NewsletterCleaner
                            .runWithNotification(applicationContext)
                    }
                }
            }
            else -> if (idleJob?.isActive != true) startIdleLoop()
        }
        if (cleanerJob?.isActive != true) startNewsletterScheduler()
        return START_STICKY
    }

    /**
     * Sofort-Prüfung per Launcher-Shortcut: eigene Verbindung, meldet neue
     * Mails über die üblichen Benachrichtigungen; gibt es keine, kommt eine
     * kurze Statusmeldung — die App muss dafür nicht geöffnet werden.
     */
    private fun checkNowAsync() {
        scope.launch {
            var newCount = -1
            try {
                val store = MailRepository.openStore()
                try {
                    val inbox = store.getFolder("INBOX") as IMAPFolder
                    inbox.open(Folder.READ_ONLY)
                    newCount = processNewMessages(inbox)
                    syncFlags(inbox)
                } finally {
                    runCatching { store.close() }
                }
            } catch (_: Exception) {
            }
            when {
                newCount == 0 -> statusNotification("Keine neuen Mails", timeoutMs = 15_000)
                newCount < 0 -> statusNotification("Prüfung fehlgeschlagen — bitte Verbindung prüfen")
            }
        }
    }

    private fun statusNotification(text: String, timeoutMs: Long = 0) {
        val openIntent = PendingIntent.getActivity(
            this, STATUS_NOTIF_ID, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, MailApp.CHANNEL_NEW_MAIL)
            .setSmallIcon(R.drawable.ic_notif_mail)
            .setLargeIcon(NotificationUtil.logoBitmap(this))
            .setColor(0xFFE85510.toInt())
            .setContentTitle("BlockMail")
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .apply { if (timeoutMs > 0) setTimeoutAfter(timeoutMs) }
            .build()
        try {
            NotificationManagerCompat.from(this).notify(STATUS_NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }

    private var cleanerJob: Job? = null

    /** Startet den täglichen Newsletter-Aufräumlauf (ab 20 Uhr, einmal pro Tag). */
    private fun startNewsletterScheduler() {
        cleanerJob = scope.launch {
            while (isActive) {
                try {
                    if (Prefs.isConfigured) {
                        val cal = java.util.Calendar.getInstance()
                        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
                        if (cal.get(java.util.Calendar.HOUR_OF_DAY) >= 20 &&
                            Prefs.lastNewsletterRunDay != today
                        ) {
                            Prefs.lastNewsletterRunDay = today
                            runCatching {
                                com.jakober.klarmail.data.NewsletterCleaner.run(applicationContext)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
                // Vorgeladene Mail-Inhalte, die älter als eine Woche sind, entfernen
                runCatching { MailRepository.cleanupBodyCache() }
                delay(10 * 60_000)
            }
        }
    }

    /** Erneuert die Verbindung innerhalb des laufenden Dienstes (kein Stopp/Start-Rennen). */
    private fun restartIdleLoop() {
        idleJob?.cancel()
        val store = activeStore
        Thread { runCatching { store?.close() } }.start()
        startIdleLoop()
    }

    private fun serviceNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, MailApp.CHANNEL_SYNC)
            .setSmallIcon(R.drawable.ic_notif_mail)
            .setColor(0xFFEE5F0F.toInt())
            .setContentTitle("BlockMail aktiv")
            .setContentText("Wartet auf neue E-Mails")
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun now(): String = SimpleDateFormat("HH:mm:ss", Locale.GERMAN).format(Date())

    private fun startIdleLoop() {
        idleJob = scope.launch {
            val self = kotlin.coroutines.coroutineContext[Job]
            val stillActive: () -> Boolean = { self?.isActive == true }
            var backoff = 5_000L
            while (isActive) {
                if (!Prefs.isConfigured) {
                    pushStatus.value = "Kein Konto verbunden"
                    delay(30_000)
                    continue
                }
                try {
                    connectAndIdle(stillActive) { backoff = 5_000L }
                    if (!isActive) break
                    pushStatus.value = "Getrennt (${now()}) – verbinde gleich neu …"
                    delay(3_000)
                } catch (e: Exception) {
                    if (!isActive) break
                    pushStatus.value =
                        "Getrennt (${now()}): ${e.message?.take(80) ?: "Fehler"} – neuer Versuch in ${backoff / 1000}s"
                    delay(backoff)
                    backoff = min(backoff * 2, 120_000L)
                }
            }
        }
    }

    private fun connectAndIdle(stillActive: () -> Boolean, onConnected: () -> Unit) {
        pushStatus.value = "Verbinde … (${now()})"
        val store = MailRepository.openStore(idleMode = true)
        activeStore = store
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            onConnected()
            pushStatus.value = "Verbunden – wartet auf Mails (seit ${now()})"
            // Nachholen, was während einer Verbindungslücke angekommen ist
            processNewMessages(inbox)
            syncFlags(inbox)
            // idle() blockiert, bis der Server ein Ereignis meldet, und kehrt dann
            // zurück. Neue Mails werden HIER verarbeitet (nicht in einem Listener) —
            // Serverabfragen aus dem IMAP-Ereignis-Thread können die Verbindung
            // blockieren.
            while (stillActive() && inbox.isOpen) {
                inbox.idle()
                if (!stillActive()) break
                processNewMessages(inbox)
                syncFlags(inbox)
            }
        } finally {
            runCatching { store.close() }
            activeStore = null
        }
    }

    /**
     * Meldet alle Mails mit UID oberhalb der Merkliste und rückt sie vor.
     * Liefert die Anzahl der neu gemeldeten Mails (ohne blockierte).
     */
    private fun processNewMessages(inbox: IMAPFolder): Int {
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
                                        mail.uid, mail.from, mail.fromAddress, mail.subject
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
            pushStatus.value = "Verbunden – letzte Mail verarbeitet ${now()}"
        } catch (_: Exception) {
        }
        return newCount
    }

    /**
     * Gleicht die Lese-Markierungen der letzten Inbox-Mails ab: extern gelesene
     * Mails werden in der App aktualisiert und ihre Benachrichtigung entfernt.
     */
    private fun syncFlags(inbox: IMAPFolder) {
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
                    NotificationManagerCompat.from(this).cancel((uid % Int.MAX_VALUE).toInt())
                }
            }
            MailRepository.applyRemoteFlags(seenByUid)
        } catch (_: Exception) {
        }
    }

    private fun showNewMailNotification(uid: Long, from: String, fromAddress: String, subject: String) {
        val notifId = (uid % Int.MAX_VALUE).toInt()
        // Absender-Avatar wie in der Mail-Liste (lädt ggf. aus dem Netz —
        // wir laufen hier bereits auf einem IO-Thread)
        val avatar = runCatching {
            NotificationUtil.senderAvatarBitmap(from, fromAddress)
        }.getOrNull() ?: NotificationUtil.logoBitmap(this)

        val markReadIntent = Intent(this, MarkReadReceiver::class.java).apply {
            action = "com.jakober.klarmail.MARK_READ"
            putExtra("uid", uid)
            putExtra("notifId", notifId)
        }
        val markReadPending = PendingIntent.getBroadcast(
            this, notifId, markReadIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPending = PendingIntent.getActivity(
            this, notifId,
            Intent(this, MainActivity::class.java).apply {
                putExtra("open_uid", uid)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, MailApp.CHANNEL_NEW_MAIL)
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
            NotificationManagerCompat.from(this).notify(notifId, notification)
        } catch (_: SecurityException) {
        }
    }

    override fun onDestroy() {
        pushStatus.value = "Gestoppt"
        // Store schließen, um das blockierende idle() zu beenden
        val store = activeStore
        Thread { runCatching { store?.close() } }.start()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val SERVICE_NOTIF_ID = 1
        private const val STATUS_NOTIF_ID = 4300
        const val ACTION_RESTART = "com.jakober.klarmail.RESTART_PUSH"
        const val ACTION_CHECK_NOW = "com.jakober.klarmail.CHECK_NOW"
        const val ACTION_NEWSLETTER = "com.jakober.klarmail.RUN_NEWSLETTER"

        /** Startet den Dienst mit einer bestimmten Aktion (Launcher-Shortcuts). */
        fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, MailSyncService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        /** Sichtbarer Zustand des Push-Dienstes für die Einstellungen. */
        val pushStatus = MutableStateFlow("Noch nicht gestartet")

        fun start(context: Context) {
            val intent = Intent(context, MailSyncService::class.java)
            context.startForegroundService(intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, MailSyncService::class.java).setAction(ACTION_RESTART)
            context.startForegroundService(intent)
        }
    }
}
