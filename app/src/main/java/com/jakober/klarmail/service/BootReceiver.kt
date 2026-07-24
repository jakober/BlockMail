package com.jakober.klarmail.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jakober.klarmail.data.Prefs

/** Startet den Push-Service nach Geräteneustart und nach App-Updates automatisch. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val relevant = intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (relevant && Prefs.isConfigured) {
            try {
                MailSyncService.start(context)
            } catch (_: Exception) {
            }
        }
    }
}
