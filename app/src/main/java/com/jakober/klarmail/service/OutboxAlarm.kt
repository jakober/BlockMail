package com.jakober.klarmail.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jakober.klarmail.data.Prefs

/**
 * Pünktliches geplantes Senden: Die 10/15-Minuten-Takte von Dienst und
 * Wächter reichen als Sicherheitsnetz, verfehlen die Wunschzeit aber um
 * bis zu eine halbe Stunde (Doze schiebt die Takte zusammen). Deshalb
 * stellt sich die App hier zusätzlich einen ECHTEN Wecker auf die
 * nächste fällige Mail:
 *
 *  - Mit der Berechtigung „Wecker und Erinnerungen“ (Android 12/13+,
 *    auf 12 automatisch erteilt) exakt zur Minute.
 *  - Ohne sie als Fenster-Wecker mit höchstens 10 Minuten Spielraum —
 *    immer noch deutlich näher dran als der reine Takt.
 *
 * Der Wecker wird beim Planen, beim App-Start, nach jedem Versand und
 * vom Wächter neu gestellt; nach einem Neustart übernimmt der erste
 * App-/Wächterlauf.
 */
object OutboxAlarm {

    fun arm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, 4711,
            Intent(context, OutboxAlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val next = Prefs.outbox().minOfOrNull { it.sendAt }
        if (next == null) {
            runCatching { am.cancel(pi) }
            return
        }
        val at = maxOf(next, System.currentTimeMillis() + 1_000)
        runCatching {
            if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                am.setWindow(AlarmManager.RTC_WAKEUP, at, 10 * 60_000L, pi)
            }
        }
    }
}

/** Weckruf: Versand nicht hier erledigen, sondern zuverlässig als Work. */
class OutboxAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val request = OneTimeWorkRequestBuilder<OutboxWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "outbox_now", ExistingWorkPolicy.REPLACE, request
        )
    }
}

/** Verschickt fällige geplante Mails und stellt den Wecker neu. */
class OutboxWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        runCatching { Prefs.init(applicationContext) }
        runCatching { MailChecker.processOutboxNow(applicationContext) }
        runCatching { MailChecker.processDueSnoozes(applicationContext) }
        runCatching { OutboxAlarm.arm(applicationContext) }
        return Result.success()
    }
}
