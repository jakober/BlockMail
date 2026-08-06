package com.jakober.klarmail.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jakober.klarmail.data.MailIndex
import com.jakober.klarmail.data.Prefs
import java.util.concurrent.TimeUnit

/**
 * Baut den vollen Suchindex automatisch auf — aber nur unter Bedingungen,
 * die niemanden stören: WLAN (unlimitiertes Netz) UND Gerät lädt, typisch
 * also nachts am Ladekabel. So bekommt jeder Nutzer die komplette
 * Mail-Historie in den Index, ohne den Knopf in den Einstellungen zu
 * kennen und ohne Akku- oder Datenverbrauch unterwegs.
 *
 * Läuft einmal durch und merkt sich das ([Prefs.indexAutoBuilt]); nach
 * jeder Kontoänderung wird der Merker zurückgesetzt, damit auch das neue
 * Konto die volle Historie bekommt. Der manuelle Knopf in den
 * Einstellungen bleibt unverändert bestehen.
 */
class IndexBuildWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!Prefs.isConfigured || !Prefs.indexEnabled) return Result.success()
        if (Prefs.indexAutoBuilt) return Result.success()
        val ok = runCatching {
            MailIndex.init(applicationContext)
            MailIndex.fullBuild(applicationContext)
        }.isSuccess
        // Nur bei Erfolg abhaken — sonst probiert es der naechste Lauf
        // (wieder nur bei WLAN + Laden) erneut
        if (ok) Prefs.indexAutoBuilt = true
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<IndexBuildWorker>(
                1, TimeUnit.DAYS
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "index_full_build",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
