package com.jakober.klarmail.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jakober.klarmail.data.Prefs

/**
 * Wächter für den Push-Dienst: läuft alle 15 Minuten über WorkManager.
 * Wurde der Dienst von Android beendet (Akku-Optimierung, Doze,
 * Hersteller-Energiesparen), meldet der Wächter verpasste Mails sofort als
 * Benachrichtigung und versucht, den Dienst wiederzubeleben. Läuft der
 * Dienst normal, tut der Wächter nichts.
 */
class SyncGuardWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.isConfigured) return Result.success()

        // Fällige zurückgestellte Mails auch dann wecken, wenn der Dienst tot ist
        runCatching { MailChecker.processDueSnoozes(applicationContext) }
        // Fällige geplante Mails auch bei totem Dienst verschicken
        runCatching { MailChecker.processOutbox(applicationContext) }
        // Täglicher Newsletter-Lauf und Cache-Aufräumen — wichtig im Sparmodus,
        // wo der Dienst-Planer nicht läuft (doppelte Läufe verhindert das Datum)
        runCatching {
            val cal = java.util.Calendar.getInstance()
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            if (Prefs.newsletterAutoEnabled &&
                cal.get(java.util.Calendar.HOUR_OF_DAY) >= 20 &&
                Prefs.lastNewsletterRunDay != today
            ) {
                Prefs.lastNewsletterRunDay = today
                com.jakober.klarmail.data.NewsletterCleaner.run(applicationContext)
            }
        }
        runCatching { com.jakober.klarmail.data.MailRepository.cleanupBodyCache() }
        // Antwort-Radar (läuft intern höchstens einmal pro Tag)
        runCatching { MailChecker.runReplyRadar(applicationContext) }

        // Sparmodus: bewusst keine Dauerverbindung — direkt auf neue Mails prüfen
        if (Prefs.pushMode == "eco") {
            runCatching { MailChecker.checkOnce(applicationContext) }
            return Result.success()
        }

        // Frisches Lebenszeichen (< 13 min) → Push-Verbindung ist gesund
        val alive = MailSyncService.lastAliveMs
        if (alive > 0 && System.currentTimeMillis() - alive < 13 * 60_000) {
            return Result.success()
        }

        // Dienst wiederbeleben. Auf Android 12+ kann der Start aus dem
        // Hintergrund ohne Akku-Ausnahme scheitern — dann fängt trotzdem
        // die direkte Prüfung unten die verpassten Mails ab.
        runCatching { MailSyncService.start(applicationContext) }

        // Verpasste Mails sofort nachmelden (eigene Verbindung, postet die
        // üblichen Benachrichtigungen mit Absender-Avatar)
        runCatching { MailChecker.checkOnce(applicationContext) }

        return Result.success()
    }

    companion object {
        /** Plant den Wächter einmalig (bestehende Planung bleibt erhalten). */
        fun schedule(context: Context) {
            val request = androidx.work.PeriodicWorkRequestBuilder<SyncGuardWorker>(
                15, java.util.concurrent.TimeUnit.MINUTES
            ).setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build()
            ).build()
            androidx.work.WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sync_guard",
                androidx.work.ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
