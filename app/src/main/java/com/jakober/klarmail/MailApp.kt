package com.jakober.klarmail

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.jakober.klarmail.data.MailRepository
import com.jakober.klarmail.data.Prefs

class MailApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        MailRepository.init(this)
        createChannels()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_MAIL,
                "Neue E-Mails",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Benachrichtigungen bei neuen E-Mails" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEWSLETTER,
                "Newsletter-Aufräumen",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Bericht des täglichen Newsletter-Aufräumens" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                "Hintergrund-Synchronisierung",
                NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Dauerhafte Verbindung für Echtzeit-Push"; setShowBadge(false) }
        )
    }

    companion object {
        const val CHANNEL_NEW_MAIL = "new_mail"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_NEWSLETTER = "newsletter"
    }
}
