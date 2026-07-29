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
        com.jakober.klarmail.data.MailIndex.init(this)
        createChannels()
        com.jakober.klarmail.service.SyncGuardWorker.schedule(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_MAIL,
                getString(R.string.svc_channel_new_mail),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = getString(R.string.svc_channel_new_mail_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEWSLETTER,
                getString(R.string.svc_channel_newsletter),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = getString(R.string.svc_channel_newsletter_desc) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_SYNC,
                getString(R.string.svc_channel_sync),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = getString(R.string.svc_channel_sync_desc)
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CHANNEL_NEW_MAIL = "new_mail"
        const val CHANNEL_SYNC = "sync"
        const val CHANNEL_NEWSLETTER = "newsletter"
    }
}
