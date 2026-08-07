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
        // Ersten Start merken (steuert, ab wann die Pro-Hinweiskarte darf)
        if (Prefs.firstStartAt == 0L) Prefs.firstStartAt = System.currentTimeMillis()
        // Entwickler-Freischaltung aus den Einstellungen uebernehmen
        runCatching { com.jakober.klarmail.data.ProAccess.refresh() }
        MailRepository.init(this)
        // PDFBox braucht seine Schriftdaten aus den App-Ressourcen, bevor
        // ein geschuetztes PDF aufgeschlossen werden kann
        runCatching {
            com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        }
        // Anschlussstelle des gemeinsamen Dokument-Editors: In BlockMail
        // entscheidet das Pro-Abo ueber Bearbeiten, der Kauf-Hinweis ist der
        // bekannte Pro-Dialog
        com.jakober.klarmail.data.DocumentHost.fileProviderAuthority =
            "com.jakober.klarmail.fileprovider"
        // Editor-Freischaltung: Pro-Abo ODER Einmalkauf "PDF-Editor fuer immer"
        com.jakober.klarmail.data.DocumentHost.editAllowedFlow =
            com.jakober.klarmail.data.ProAccess.editorAllowedFlow
        com.jakober.klarmail.data.DocumentHost.upsell = { onDismiss ->
            // Im Editor zusaetzlich den Einmalkauf anbieten
            com.jakober.klarmail.ui.ProUpsellDialog(
                onDismiss = onDismiss,
                showLifetime = true
            )
        }
        // Voller Editor-Umfang auch in BlockMail: Text, Bilder, Formen,
        // Suche, Formulare, Scannen, Auszug, Passwort, Verkleinern,
        // Nachtmodus, Seiten- und Inhaltsuebersicht
        com.jakober.klarmail.data.DocumentHost.extendedFeatures = true
        // Frei-Stufe: Unterschreiben + als Antwort senden geht ohne Abo,
        // alle uebrigen Werkzeuge zeigen "(Pro)" und den Kauf-Hinweis
        com.jakober.klarmail.data.DocumentHost.freeSignatureAndSend = true
        com.jakober.klarmail.data.MailIndex.init(this)
        createChannels()
        com.jakober.klarmail.service.SyncGuardWorker.schedule(this)
        // Voll-Index automatisch aufbauen — aber nur bei WLAN + Laden
        com.jakober.klarmail.service.IndexBuildWorker.schedule(this)
        // Wecker fuer geplante Mails wiederherstellen (z. B. nach Neustart)
        runCatching { com.jakober.klarmail.service.OutboxAlarm.arm(this) }
        cleanupSenderShortcuts()
        // Arbeitsdateien des Dokument-Editors aufraeumen: Abbrueche und
        // Abstuerze lassen dort sonst dauerhaft Kopien liegen
        runCatching { com.jakober.klarmail.data.AttachmentEditing.cleanupOldFiles(this) }
        // Play-Abo prüfen: stellt Pro nach einer Neuinstallation von selbst
        // wieder her (läuft in der Testphase mit, ändert dort aber nichts)
        runCatching { com.jakober.klarmail.data.BillingManager.init(this) }
        // Kontingent-Stand aus dem Gerät laden (setzt bei Monatswechsel
        // zurück), damit die Gates von Anfang an den richtigen Wert sehen
        runCatching { com.jakober.klarmail.data.AiQuota.ensureLoaded() }
        // …und den verbindlichen Stand beim Server nachfragen (der zählt
        // geräteübergreifend, siehe AiQuota). Schlägt es fehl, bleibt der
        // Zähler aus dem Gerät stehen.
        runCatching { com.jakober.klarmail.data.AiQuota.refreshSoon() }
    }

    /**
     * Frühere Versionen legten je Absender eine dynamische Verknüpfung an
     * (für die Konversations-Benachrichtigung). Die tauchten sichtbar im
     * Startbildschirm-Menü auf ("Amazon.de", "ING", …). Sie werden hier
     * einmalig entfernt; neue Verknüpfungen sind vom Menü ausgeschlossen.
     */
    private fun cleanupSenderShortcuts() {
        if (Prefs.senderShortcutsCleaned) return
        runCatching {
            val stale = androidx.core.content.pm.ShortcutManagerCompat
                .getDynamicShortcuts(this)
                .map { it.id }
                .filter { it.startsWith("sender_") }
            if (stale.isNotEmpty()) {
                androidx.core.content.pm.ShortcutManagerCompat
                    .removeDynamicShortcuts(this, stale)
            }
        }
        Prefs.senderShortcutsCleaned = true
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
    }
}
