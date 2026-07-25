package com.jakober.klarmail.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

object Prefs {

    private lateinit var sp: SharedPreferences

    val colorSchemeFlow = MutableStateFlow("klarmail")
    val darkModeFlow = MutableStateFlow("system")
    val conversationViewFlow = MutableStateFlow(false)

    /** Stumm geschaltete Absender: als gelesen markieren, keine Benachrichtigung. */
    val mutedFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Blockierte Absender: nach Ankunft direkt löschen, keine Benachrichtigung. */
    val blockedFlow = MutableStateFlow<Set<String>>(emptySet())

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "klarmail_secure",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback, falls das Keystore-Backend auf dem Gerät nicht verfügbar ist
            context.getSharedPreferences("klarmail_prefs", Context.MODE_PRIVATE)
        }
        colorSchemeFlow.value = colorScheme
        darkModeFlow.value = darkMode
        snoozedFlow.value = snoozes().map { it.uid }.toSet()
        conversationViewFlow.value = conversationView
        // Aktives Konto in der Kontenliste sichern (für den Konten-Wechsler)
        snapshotActiveAccount()
        mutedFlow.value = loadSet("muted_senders")
        blockedFlow.value = loadSet("blocked_senders")
    }

    private fun loadSet(key: String): Set<String> = try {
        val a = org.json.JSONArray(sp.getString(key, "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it).lowercase() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun saveSet(key: String, set: Set<String>) {
        sp.edit().putString(key, org.json.JSONArray(set.toList()).toString()).apply()
    }

    fun addMuted(address: String) {
        val key = address.trim().lowercase()
        if (key.isBlank() || !key.contains("@")) return
        val set = mutedFlow.value + key
        saveSet("muted_senders", set); mutedFlow.value = set
    }

    fun removeMuted(address: String) {
        val set = mutedFlow.value - address.trim().lowercase()
        saveSet("muted_senders", set); mutedFlow.value = set
    }

    fun addBlocked(address: String) {
        val key = address.trim().lowercase()
        if (key.isBlank() || !key.contains("@")) return
        val set = blockedFlow.value + key
        saveSet("blocked_senders", set); blockedFlow.value = set
    }

    fun removeBlocked(address: String) {
        val set = blockedFlow.value - address.trim().lowercase()
        saveSet("blocked_senders", set); blockedFlow.value = set
    }

    fun isMuted(address: String) = address.trim().lowercase() in mutedFlow.value
    fun isBlocked(address: String) = address.trim().lowercase() in blockedFlow.value

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit().putString("email", v.trim()).apply()

    var appPassword: String
        get() = sp.getString("app_password", "") ?: ""
        set(v) = sp.edit().putString("app_password", v.replace(" ", "")).apply()

    var claudeApiKey: String
        get() = sp.getString("claude_key", "") ?: ""
        set(v) = sp.edit().putString("claude_key", v.trim()).apply()

    /** "oauth" = Google-Anmeldung, "password" = App-Passwort */
    var authMethod: String
        get() = sp.getString("auth_method", "password") ?: "password"
        set(v) = sp.edit().putString("auth_method", v).apply()

    var refreshToken: String
        get() = sp.getString("g_refresh_token", "") ?: ""
        set(v) = sp.edit().putString("g_refresh_token", v).apply()

    var accessToken: String
        get() = sp.getString("g_access_token", "") ?: ""
        set(v) = sp.edit().putString("g_access_token", v).apply()

    var accessTokenExpiry: Long
        get() = sp.getLong("g_access_token_expiry", 0L)
        set(v) = sp.edit().putLong("g_access_token_expiry", v).apply()

    /** Bekannte Empfänger (Adresse → Anzeigename) für Vorschläge beim Verfassen. */
    fun knownRecipients(): Map<String, String> = try {
        val o = JSONObject(sp.getString("known_recipients", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optString(it) }
    } catch (e: Exception) {
        emptyMap()
    }

    fun addKnownRecipients(entries: List<Pair<String, String>>) {
        if (entries.isEmpty()) return
        try {
            val o = JSONObject(sp.getString("known_recipients", "{}") ?: "{}")
            entries.forEach { (address, name) ->
                val key = address.trim().lowercase()
                if (key.isNotBlank() && key.contains("@")) {
                    val existing = o.optString(key)
                    o.put(key, name.ifBlank { existing })
                }
            }
            sp.edit().putString("known_recipients", o.toString()).apply()
        } catch (e: Exception) {
        }
    }

    /** Absender, die der Nutzer als "kein Newsletter" markiert hat (KI-Lernen). */
    fun notNewsletterSenders(): Set<String> = try {
        val a = org.json.JSONArray(sp.getString("not_newsletter", "[]") ?: "[]")
        (0 until a.length()).map { a.getString(it).lowercase() }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun addNotNewsletter(address: String) {
        val key = address.trim().lowercase()
        if (key.isBlank()) return
        val set = notNewsletterSenders().toMutableSet()
        if (set.add(key)) {
            sp.edit().putString("not_newsletter", org.json.JSONArray(set.toList()).toString()).apply()
        }
    }

    /** Datum (yyyy-MM-dd) des letzten Newsletter-Aufräumlaufs. */
    var lastNewsletterRunDay: String
        get() = sp.getString("newsletter_last_run", "") ?: ""
        set(v) = sp.edit().putString("newsletter_last_run", v).apply()

    /** Höchste bereits per Push verarbeitete Mail-UID (für Lücken-Nachholung). */
    // Pro Konto eigene Merkliste (Fallback auf den alten globalen Schlüssel)
    private fun pushUidKey() = "last_push_uid_" + email.trim().lowercase()

    var lastPushUid: Long
        get() = sp.getLong(pushUidKey(), sp.getLong("last_push_uid", 0L))
        set(v) = sp.edit().putLong(pushUidKey(), v).apply()

    // Mail-Server des aktiven Kontos (Standard: Gmail)
    var imapHost: String
        get() = sp.getString("imap_host", "imap.gmail.com") ?: "imap.gmail.com"
        set(v) = sp.edit().putString("imap_host", v.trim()).apply()

    var imapPort: Int
        get() = sp.getInt("imap_port", 993)
        set(v) = sp.edit().putInt("imap_port", v).apply()

    var smtpHost: String
        get() = sp.getString("smtp_host", "smtp.gmail.com") ?: "smtp.gmail.com"
        set(v) = sp.edit().putString("smtp_host", v.trim()).apply()

    var smtpPort: Int
        get() = sp.getInt("smtp_port", 465)
        set(v) = sp.edit().putInt("smtp_port", v).apply()

    /** Gespeichertes Mail-Konto (Profil) für den Konten-Wechsler. */
    data class Account(
        val email: String,
        val authMethod: String,
        val appPassword: String,
        val refreshToken: String,
        val imapHost: String = "imap.gmail.com",
        val imapPort: Int = 993,
        val smtpHost: String = "smtp.gmail.com",
        val smtpPort: Int = 465
    )

    fun accounts(): List<Account> = try {
        val arr = org.json.JSONArray(sp.getString("accounts", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Account(
                email = o.optString("email"),
                authMethod = o.optString("authMethod", "password"),
                appPassword = o.optString("appPassword"),
                refreshToken = o.optString("refreshToken"),
                imapHost = o.optString("imapHost", "imap.gmail.com"),
                imapPort = o.optInt("imapPort", 993),
                smtpHost = o.optString("smtpHost", "smtp.gmail.com"),
                smtpPort = o.optInt("smtpPort", 465)
            )
        }.filter { it.email.isNotBlank() }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveAccounts(list: List<Account>) {
        val arr = org.json.JSONArray()
        list.forEach { a ->
            arr.put(org.json.JSONObject().apply {
                put("email", a.email); put("authMethod", a.authMethod)
                put("appPassword", a.appPassword); put("refreshToken", a.refreshToken)
                put("imapHost", a.imapHost); put("imapPort", a.imapPort)
                put("smtpHost", a.smtpHost); put("smtpPort", a.smtpPort)
            })
        }
        sp.edit().putString("accounts", arr.toString()).apply()
    }

    /** Sichert die aktuellen Zugangsdaten als Konto in der Kontenliste (Upsert). */
    fun snapshotActiveAccount() {
        if (email.isBlank() || !isConfigured) return
        val acc = Account(
            email, authMethod, appPassword, refreshToken,
            imapHost, imapPort, smtpHost, smtpPort
        )
        saveAccounts(accounts().filter { !it.email.equals(acc.email, ignoreCase = true) } + acc)
    }

    fun removeAccount(accountEmail: String) =
        saveAccounts(accounts().filter { !it.email.equals(accountEmail, ignoreCase = true) })

    /** Aktiviert ein gespeichertes Konto (Zugangsdaten in die aktiven Felder). */
    fun activateAccount(acc: Account) {
        snapshotActiveAccount()
        email = acc.email
        authMethod = acc.authMethod
        appPassword = acc.appPassword
        refreshToken = acc.refreshToken
        imapHost = acc.imapHost
        imapPort = acc.imapPort
        smtpHost = acc.smtpHost
        smtpPort = acc.smtpPort
        accessToken = ""
        accessTokenExpiry = 0
        snoozedFlow.value = snoozes().map { it.uid }.toSet()
    }

    /** Dateiname des Posteingangs-Caches für das aktive Konto. */
    fun inboxCacheFileName(): String {
        val safe = email.trim().lowercase().replace(Regex("[^a-z0-9@._-]"), "_")
        return if (safe.isBlank()) "inbox_cache.json" else "inbox_cache_$safe.json"
    }

    /** Zurückgestellte Mail (Snooze): bis wann versteckt + Daten für die Erinnerung. */
    data class Snooze(
        val uid: Long,
        val until: Long,
        val from: String,
        val address: String,
        val subject: String
    )

    /** UIDs aller aktuell zurückgestellten Mails (für die Posteingangs-Filterung). */
    val snoozedFlow = MutableStateFlow<Set<Long>>(emptySet())

    // Pro Konto eigene Snooze-Liste (Fallback auf den alten globalen Schlüssel)
    private fun snoozeKey() = "snoozes_" + email.trim().lowercase()

    fun snoozes(): List<Snooze> = try {
        val arr = org.json.JSONArray(
            sp.getString(snoozeKey(), sp.getString("snoozes", "[]")) ?: "[]"
        )
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Snooze(
                uid = o.getLong("uid"),
                until = o.getLong("until"),
                from = o.optString("from"),
                address = o.optString("address"),
                subject = o.optString("subject")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveSnoozes(list: List<Snooze>) {
        val arr = org.json.JSONArray()
        list.forEach { s ->
            arr.put(org.json.JSONObject().apply {
                put("uid", s.uid); put("until", s.until)
                put("from", s.from); put("address", s.address); put("subject", s.subject)
            })
        }
        sp.edit().putString(snoozeKey(), arr.toString()).apply()
        snoozedFlow.value = list.map { it.uid }.toSet()
    }

    fun addSnooze(s: Snooze) = saveSnoozes(snoozes().filter { it.uid != s.uid } + s)

    fun removeSnooze(uid: Long) = saveSnoozes(snoozes().filter { it.uid != uid })

    /** Geplante Mail in der Ausgangs-Warteschlange. */
    data class ScheduledMail(
        val id: Long,
        val sendAt: Long,
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val body: String,
        val html: String?
    )

    fun outbox(): List<ScheduledMail> = try {
        val arr = org.json.JSONArray(sp.getString("outbox", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ScheduledMail(
                id = o.getLong("id"),
                sendAt = o.getLong("sendAt"),
                to = o.optString("to"),
                cc = o.optString("cc"),
                bcc = o.optString("bcc"),
                subject = o.optString("subject"),
                body = o.optString("body"),
                html = if (o.has("html")) o.getString("html") else null
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveOutbox(list: List<ScheduledMail>) {
        val arr = org.json.JSONArray()
        list.forEach { m ->
            arr.put(org.json.JSONObject().apply {
                put("id", m.id); put("sendAt", m.sendAt)
                put("to", m.to); put("cc", m.cc); put("bcc", m.bcc)
                put("subject", m.subject); put("body", m.body)
                m.html?.let { put("html", it) }
            })
        }
        sp.edit().putString("outbox", arr.toString()).apply()
    }

    fun addOutbox(m: ScheduledMail) = saveOutbox(outbox() + m)

    fun removeOutbox(id: Long) = saveOutbox(outbox().filter { it.id != id })

    /** Signatur, die unter neue Mails gesetzt wird (leer = keine). */
    var signature: String
        get() = sp.getString("signature", "") ?: ""
        set(v) = sp.edit().putString("signature", v).apply()

    /** Wiederverwendbare Textvorlagen fürs Verfassen-Fenster (Titel + Text). */
    fun mailTemplates(): List<Pair<String, String>> = try {
        val arr = org.json.JSONArray(sp.getString("mail_templates", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.optString("title") to o.optString("text")
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun saveMailTemplates(list: List<Pair<String, String>>) {
        val arr = org.json.JSONArray()
        list.forEach { (title, text) ->
            arr.put(org.json.JSONObject().apply { put("title", title); put("text", text) })
        }
        sp.edit().putString("mail_templates", arr.toString()).apply()
    }

    /**
     * Version des Vorschau-Algorithmus. Steigt sie, werden alle gespeicherten
     * Vorschauen einmalig verworfen und aus dem Inhalte-Cache neu aufgebaut.
     */
    var snippetVersion: Int
        get() = sp.getInt("snippet_version", 0)
        set(v) = sp.edit().putInt("snippet_version", v).apply()

    var colorScheme: String
        get() = sp.getString("color_scheme", "klarmail") ?: "klarmail"
        set(v) {
            sp.edit().putString("color_scheme", v).apply()
            colorSchemeFlow.value = v
        }

    /** Posteingang: Mails mit gleichem Betreff als Konversation bündeln. */
    var conversationView: Boolean
        get() = sp.getBoolean("conversation_view", false)
        set(v) {
            sp.edit().putBoolean("conversation_view", v).apply()
            conversationViewFlow.value = v
        }

    /** Erscheinungsbild: "system" (Gerät folgt), "light" oder "dark". */
    var darkMode: String
        get() = sp.getString("dark_mode", "system") ?: "system"
        set(v) {
            sp.edit().putString("dark_mode", v).apply()
            darkModeFlow.value = v
        }

    val isConfigured: Boolean
        get() = email.isNotBlank() && (
            (authMethod == "oauth" && refreshToken.isNotBlank()) || appPassword.isNotBlank()
        )
}
