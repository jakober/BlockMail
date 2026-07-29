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

    private var netCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        // Bei einem Netzwerkwechsel (WLAN <-> Mobilfunk) stirbt die alte
        // IMAP-Verbindung oft lautlos — sofort neu verbinden, statt bis zum
        // Lese-Timeout blind zu sein.
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        val cb = object : android.net.ConnectivityManager.NetworkCallback() {
            @Volatile
            private var lastNet: android.net.Network? = null
            override fun onAvailable(network: android.net.Network) {
                val previous = lastNet
                lastNet = network
                // Beim Registrieren meldet Android sofort das aktuelle Netz —
                // erst ein echter Wechsel danach löst den Neuaufbau aus
                if (previous != null && previous != network && idleJob?.isActive == true) {
                    pushStatus.value = PushState(PushKind.NET_CHANGE, now())
                    restartIdleLoop()
                }
            }
        }
        runCatching { cm?.registerDefaultNetworkCallback(cb) }
        netCallback = cb
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse: darf im Gegensatz zu dataSync auch direkt nach dem
            // Geräteneustart (BOOT_COMPLETED) gestartet werden
            startForeground(
                SERVICE_NOTIF_ID, serviceNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(SERVICE_NOTIF_ID, serviceNotification())
        }
        // Sparmodus: keine Dauerverbindung — der Dienst erledigt nur die
        // angeforderte Aktion und beendet sich danach wieder selbst
        val eco = Prefs.pushMode == "eco"
        fun maybeStartIdle() {
            if (!eco && idleJob?.isActive != true) startIdleLoop()
        }
        when (intent?.action) {
            ACTION_RESTART -> if (eco) stopSelfClean() else restartIdleLoop()
            ACTION_CHECK_NOW -> {
                maybeStartIdle()
                checkNowAsync(stopAfter = eco)
            }
            ACTION_NEWSLETTER -> {
                maybeStartIdle()
                scope.launch {
                    // BlockMail Pro: Der Newsletter-Scan ist eine Pro-Funktion.
                    // Ohne Pro tut der Launcher-Shortcut einfach nichts (kein
                    // Dialog auf der Service-Seite). In der Testphase
                    // (TEST_PHASE_UNLOCK) ist isPro immer true.
                    if (com.jakober.klarmail.data.ProAccess.isPro) {
                        runCatching {
                            com.jakober.klarmail.data.NewsletterCleaner
                                .runWithNotification(applicationContext)
                        }
                    }
                    if (eco) stopSelfClean()
                }
            }
            ACTION_BUILD_INDEX -> {
                maybeStartIdle()
                // Schnellaufbau des Suchindex im Dienst-Scope: läuft auch
                // weiter, wenn die Einstellungen geschlossen werden
                scope.launch {
                    runCatching {
                        com.jakober.klarmail.data.MailIndex.fullBuild(applicationContext)
                    }
                    if (eco) stopSelfClean()
                }
            }
            ACTION_SEND_REPLY -> {
                maybeStartIdle()
                val text = intent?.let {
                    androidx.core.app.RemoteInput.getResultsFromIntent(it)
                        ?.getCharSequence(KEY_QUICK_REPLY)?.toString()?.trim()
                }.orEmpty()
                val address = intent?.getStringExtra("address").orEmpty()
                if (text.isNotBlank() && address.isNotBlank()) {
                    MailChecker.sendReplyAsync(
                        applicationContext,
                        uid = intent?.getLongExtra("uid", -1L) ?: -1L,
                        address = address,
                        rawSubject = intent?.getStringExtra("subject").orEmpty(),
                        text = text,
                        notifId = intent?.getIntExtra("notifId", -1) ?: -1
                    )
                }
                if (eco) {
                    // Dem Versand etwas Zeit lassen, dann wieder beenden
                    scope.launch {
                        delay(30_000)
                        stopSelfClean()
                    }
                }
            }
            else -> if (eco) stopSelfClean() else maybeStartIdle()
        }
        if (!eco && cleanerJob?.isActive != true) startNewsletterScheduler()
        return START_STICKY
    }

    /** Vordergrund-Status samt Benachrichtigung entfernen und Dienst beenden. */
    private fun stopSelfClean() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    /**
     * Sofort-Prüfung per Launcher-Shortcut: eigene Verbindung, meldet neue
     * Mails über die üblichen Benachrichtigungen; gibt es keine, kommt eine
     * kurze Statusmeldung — die App muss dafür nicht geöffnet werden.
     */
    private fun checkNowAsync(stopAfter: Boolean = false) {
        scope.launch {
            val newCount = MailChecker.checkOnce(applicationContext)
            when {
                newCount == 0 -> MailChecker.statusNotification(
                    applicationContext, getString(R.string.svc_check_no_new), timeoutMs = 15_000
                )
                newCount < 0 -> MailChecker.statusNotification(
                    applicationContext, getString(R.string.svc_check_failed)
                )
            }
            if (stopAfter) stopSelfClean()
        }
    }

    private var cleanerJob: Job? = null

    /** Startet den täglichen Newsletter-Aufräumlauf (ab 20 Uhr, einmal pro Tag). */
    private fun startNewsletterScheduler() {
        cleanerJob = scope.launch {
            while (isActive) {
                try {
                    // BlockMail Pro: Der automatische Newsletter-Lauf ist eine
                    // Pro-Funktion — ohne Pro läuft der Planer einfach leer
                    if (Prefs.isConfigured && Prefs.newsletterAutoEnabled &&
                        com.jakober.klarmail.data.ProAccess.isPro
                    ) {
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
                // Fällige geplante Mails verschicken
                runCatching { MailChecker.processOutbox(applicationContext) }
                // Antwort-Radar (läuft intern höchstens einmal pro Tag)
                runCatching { MailChecker.runReplyRadar(applicationContext) }
                // Lokalen Suchindex schonend weiter aufbauen: ein kleiner Batch
                // (25 Mails je Konto) pro Lauf — mehr nicht, um Akku und Netz
                // nicht zu belasten
                runCatching {
                    if (Prefs.indexEnabled && Prefs.isConfigured) {
                        com.jakober.klarmail.data.MailIndex.syncBatch(applicationContext)
                    }
                }
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
            .setContentTitle(getString(R.string.svc_sync_notif_title))
            .setContentText(getString(R.string.svc_sync_notif_text))
            // Bewusst NICHT ongoing: So lässt sich die stille Meldung auf allen
            // Android-Versionen wegwischen; der Dienst läuft trotzdem weiter
            .setOngoing(false)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
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
                    pushStatus.value = PushState(PushKind.NO_ACCOUNT)
                    delay(30_000)
                    continue
                }
                try {
                    connectAndIdle(stillActive) { backoff = 5_000L }
                    if (!isActive) break
                    pushStatus.value = PushState(PushKind.DISCONNECTED, now())
                    delay(3_000)
                } catch (e: Exception) {
                    if (!isActive) break
                    pushStatus.value = PushState(
                        PushKind.DISCONNECTED_ERROR, now(),
                        detail = e.message?.take(80).orEmpty(),
                        retrySeconds = backoff / 1000
                    )
                    delay(backoff)
                    backoff = min(backoff * 2, 120_000L)
                }
            }
        }
    }

    private fun connectAndIdle(stillActive: () -> Boolean, onConnected: () -> Unit) {
        pushStatus.value = PushState(PushKind.CONNECTING, now())
        val store = MailRepository.openStore(idleMode = true)
        activeStore = store
        try {
            val inbox = store.getFolder("INBOX") as IMAPFolder
            inbox.open(Folder.READ_ONLY)
            onConnected()
            lastAliveMs = System.currentTimeMillis()
            pushStatus.value = PushState(PushKind.WAITING, now())
            // Nachholen, was während einer Verbindungslücke angekommen ist
            if (MailChecker.processNewMessages(this, inbox) > 0) {
                pushStatus.value = PushState(PushKind.PROCESSED, now())
            }
            MailChecker.syncFlags(this, inbox)
            // WICHTIG: idle(true) statt idle() — die parameterlose Variante kehrt
            // bei neuen Mails NICHT zurück (sie feuert nur Listener und blockiert
            // weiter, bis die Verbindung abreißt). idle(true) kehrt nach der
            // ersten Server-Meldung zurück, sodass die Schleife die neue Mail
            // sofort verarbeitet und danach wieder in den Wartemodus geht.
            while (stillActive() && inbox.isOpen) {
                inbox.idle(true)
                lastAliveMs = System.currentTimeMillis()
                if (!stillActive()) break
                if (MailChecker.processNewMessages(this, inbox) > 0) {
                    pushStatus.value = PushState(PushKind.PROCESSED, now())
                }
                MailChecker.syncFlags(this, inbox)
            }
        } finally {
            runCatching { store.close() }
            activeStore = null
        }
    }

    override fun onDestroy() {
        netCallback?.let { cb ->
            runCatching {
                getSystemService(android.net.ConnectivityManager::class.java)
                    ?.unregisterNetworkCallback(cb)
            }
        }
        pushStatus.value = PushState(PushKind.STOPPED)
        // Store schließen, um das blockierende idle() zu beenden
        val store = activeStore
        Thread { runCatching { store?.close() } }.start()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Sprachneutraler Push-Zustand: Der Dienst speichert nur WAS passiert ist
     * (plus Uhrzeit/Detail), die Einstellungs-UI übersetzt ihn live in die
     * aktuelle App-Sprache — so bleibt die Statuszeile bei Sprachwechsel korrekt.
     */
    enum class PushKind { NOT_STARTED, NO_ACCOUNT, NET_CHANGE, CONNECTING, WAITING, PROCESSED, DISCONNECTED, DISCONNECTED_ERROR, STOPPED }
    data class PushState(
        val kind: PushKind,
        val time: String = "",
        val detail: String = "",
        val retrySeconds: Long = 0
    )

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
        const val ACTION_BUILD_INDEX = "com.jakober.klarmail.BUILD_INDEX"
        const val ACTION_SEND_REPLY = "com.jakober.klarmail.SEND_REPLY"
        const val KEY_QUICK_REPLY = "quick_reply"

        /** Startet den Dienst mit einer bestimmten Aktion (Launcher-Shortcuts). */
        fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, MailSyncService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        /**
         * Sichtbarer Zustand des Push-Dienstes für die Einstellungen.
         * Initialwert bewusst leer (kein Context im companion object):
         * Die Einstellungs-UI zeigt bei leerem Wert stattdessen den
         * Ressourcen-Text R.string.svc_push_not_started an.
         */
        val pushStatus = MutableStateFlow(PushState(PushKind.NOT_STARTED))

        fun start(context: Context) {
            // Sparmodus: keine Dauerverbindung — der Wächter-Worker prüft alle
            // ~15 Minuten, ganz ohne dauerhafte Benachrichtigung
            if (Prefs.pushMode == "eco") return
            val intent = Intent(context, MailSyncService::class.java)
            context.startForegroundService(intent)
        }

        fun restart(context: Context) {
            if (Prefs.pushMode == "eco") return
            val intent = Intent(context, MailSyncService::class.java).setAction(ACTION_RESTART)
            context.startForegroundService(intent)
        }
    }
}
