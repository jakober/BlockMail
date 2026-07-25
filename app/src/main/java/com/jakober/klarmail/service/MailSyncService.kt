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
import javax.mail.Folder
import javax.mail.Store
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
            val newCount = MailChecker.checkOnce(applicationContext)
            when {
                newCount == 0 -> MailChecker.statusNotification(
                    applicationContext, "Keine neuen Mails", timeoutMs = 15_000
                )
                newCount < 0 -> MailChecker.statusNotification(
                    applicationContext, "Prüfung fehlgeschlagen — bitte Verbindung prüfen"
                )
            }
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
                // Fällige zurückgestellte Mails wecken (Snooze)
                runCatching { MailChecker.processDueSnoozes(applicationContext) }
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
                lastAliveMs = System.currentTimeMillis()
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
            lastAliveMs = System.currentTimeMillis()
            pushStatus.value = "Verbunden – wartet auf Mails (seit ${now()})"
            // Nachholen, was während einer Verbindungslücke angekommen ist
            if (MailChecker.processNewMessages(this, inbox) > 0) {
                pushStatus.value = "Verbunden – letzte Mail verarbeitet ${now()}"
            }
            MailChecker.syncFlags(this, inbox)
            // idle() blockiert, bis der Server ein Ereignis meldet, und kehrt dann
            // zurück. Neue Mails werden HIER verarbeitet (nicht in einem Listener) —
            // Serverabfragen aus dem IMAP-Ereignis-Thread können die Verbindung
            // blockieren.
            while (stillActive() && inbox.isOpen) {
                inbox.idle()
                lastAliveMs = System.currentTimeMillis()
                if (!stillActive()) break
                if (MailChecker.processNewMessages(this, inbox) > 0) {
                    pushStatus.value = "Verbunden – letzte Mail verarbeitet ${now()}"
                }
                MailChecker.syncFlags(this, inbox)
            }
        } finally {
            runCatching { store.close() }
            activeStore = null
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
        const val ACTION_RESTART = "com.jakober.klarmail.RESTART_PUSH"

        /**
         * Letztes Lebenszeichen der IDLE-Schleife. Statisch im App-Prozess:
         * Wird der Prozess beendet, steht hier wieder 0 — genau daran erkennt
         * der Wächter-Worker einen toten Push-Dienst.
         */
        @Volatile
        var lastAliveMs: Long = 0
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
