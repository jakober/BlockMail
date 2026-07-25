package com.jakober.klarmail

import android.app.Activity
import android.os.Bundle
import com.jakober.klarmail.service.MailSyncService

/**
 * Unsichtbares Sprungbrett für Launcher-Shortcuts, die im Hintergrund laufen
 * sollen: stößt nur den Sync-Dienst an und beendet sich sofort wieder —
 * die App-Oberfläche öffnet sich dabei nicht (Theme.NoDisplay).
 */
class ShortcutActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (intent?.action) {
            "com.jakober.klarmail.SHORTCUT_REFRESH" ->
                MailSyncService.startWithAction(this, MailSyncService.ACTION_CHECK_NOW)
            "com.jakober.klarmail.SHORTCUT_NEWSLETTER" ->
                MailSyncService.startWithAction(this, MailSyncService.ACTION_NEWSLETTER)
        }
        finish()
    }
}
