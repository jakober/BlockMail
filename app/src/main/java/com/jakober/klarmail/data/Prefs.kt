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
    val customColorFlow = MutableStateFlow(0xFFEE5F0F.toInt())
    val darkModeFlow = MutableStateFlow("dark")
    val conversationViewFlow = MutableStateFlow(false)
    val inboxLayoutFlow = MutableStateFlow("blocks")
    val aiEngineFlow = MutableStateFlow("auto")
    val swipeLeftFlow = MutableStateFlow("delete")
    val swipeRightFlow = MutableStateFlow("read")
    val devModeFlow = MutableStateFlow(false)
    val pushModeFlow = MutableStateFlow("push")

    /** Aktions-Knöpfe der Mail-Benachrichtigung (Android zeigt max. 3). */
    val notifActionsFlow = MutableStateFlow(listOf("reply", "read", "delete"))

    /** Automatisch gespeicherte Entwürfe (neueste zuerst). */
    val draftsFlow = MutableStateFlow<List<Draft>>(emptyList())

    /** Vom Phishing-Wächter markierte Mails ("konto:uid"). */
    val phishingFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Antwort-Radar aktiv? */
    val radarFlow = MutableStateFlow(true)

    /** Fokus-Blöcke im Posteingang aktiv? */
    val focusModeFlow = MutableStateFlow(false)

    /** Tägliches Newsletter-Aufräumen aktiv? */
    val newsletterAutoFlow = MutableStateFlow(true)

    /** Lokalen Suchindex automatisch im Hintergrund aufbauen? */
    val indexEnabledFlow = MutableStateFlow(true)

    /** Zeitraum des Suchindex in Jahren (0 = alles indexieren). */
    val indexYearsFlow = MutableStateFlow(1)

    /** Schlichtes Design: Mail-Zeilen/-Kacheln ohne farbige Verläufe. */
    val plainDesignFlow = MutableStateFlow(false)

    /** Änderungszähler der Konto-Farben (löst Neuzeichnen der Listen aus). */
    val accountColorsFlow = MutableStateFlow(0)

    /** Änderungszähler der ausgeblendeten Ordner je Konto. */
    val hiddenFoldersFlow = MutableStateFlow(0)

    /** Stumm geschaltete Absender: als gelesen markieren, keine Benachrichtigung. */
    val mutedFlow = MutableStateFlow<Set<String>>(emptySet())

    /** Blockierte Absender: nach Ankunft direkt löschen, keine Benachrichtigung. */
    val blockedFlow = MutableStateFlow<Set<String>>(emptySet())

    /** VIP-Absender: bei „Nur VIP benachrichtigen“ die einzigen mit Benachrichtigung. */
    val vipFlow = MutableStateFlow<Set<String>>(emptySet())
    val vipOnlyFlow = MutableStateFlow(false)

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
        customColorFlow.value = customColor
        darkModeFlow.value = darkMode
        snoozedFlow.value = snoozes().map { it.uid }.toSet()
        conversationViewFlow.value = conversationView
        inboxLayoutFlow.value = inboxLayout
        aiEngineFlow.value = aiEngine
        swipeLeftFlow.value = swipeLeftAction
        swipeRightFlow.value = swipeRightAction
        devModeFlow.value = devMode
        pushModeFlow.value = pushMode
        // Aktives Konto in der Kontenliste sichern (für den Konten-Wechsler)
        snapshotActiveAccount()
        mutedFlow.value = loadSet("muted_senders")
        blockedFlow.value = loadSet("blocked_senders")
        vipFlow.value = loadSet("vip_senders")
        vipOnlyFlow.value = vipOnlyNotifications
        notifActionsFlow.value = notifActions
        draftsFlow.value = drafts()
        phishingFlow.value = loadSet("phishing_mails")
        radarFlow.value = radarEnabled
        focusModeFlow.value = focusMode
        newsletterAutoFlow.value = newsletterAutoEnabled
        indexEnabledFlow.value = indexEnabled
        indexYearsFlow.value = indexYears
        plainDesignFlow.value = plainDesign
    }

    // ---- Backup & Umzug: Einstellungen als JSON sichern/wiederherstellen ----
    // Bewusst OHNE Zugangsdaten (Passwörter, Tokens, API-Schlüssel, Konten).

    private val backupKeys = setOf(
        "color_scheme", "custom_color", "dark_mode", "inbox_layout", "plain_design",
        "conversation_view", "swipe_left", "swipe_right", "signature",
        "mail_templates", "muted_senders", "blocked_senders", "vip_senders",
        "vip_only_notif", "notif_actions", "push_mode", "default_send_account",
        "ai_engine", "known_recipients", "not_newsletter"
    )
    private val backupPrefixes = listOf("account_color_", "hidden_folders_", "newsletter")

    fun exportSettingsJson(): String {
        val values = JSONObject()
        sp.all.forEach { (k, v) ->
            if (k in backupKeys || backupPrefixes.any { k.startsWith(it) }) {
                val o = JSONObject()
                when (v) {
                    is String -> { o.put("t", "s"); o.put("v", v) }
                    is Boolean -> { o.put("t", "b"); o.put("v", v) }
                    is Int -> { o.put("t", "i"); o.put("v", v) }
                    is Long -> { o.put("t", "l"); o.put("v", v) }
                    else -> return@forEach
                }
                values.put(k, o)
            }
        }
        return JSONObject().apply {
            put("app", "BlockMail")
            put("backupVersion", 1)
            put("exportedAt", System.currentTimeMillis())
            put("values", values)
        }.toString(2)
    }

    /** Spielt eine Sicherung ein und liefert die Zahl übernommener Einstellungen. */
    fun importSettingsJson(json: String): Int {
        val root = JSONObject(json)
        if (root.optString("app") != "BlockMail") {
            throw IllegalArgumentException("Das ist keine BlockMail-Sicherungsdatei")
        }
        val values = root.getJSONObject("values")
        val ed = sp.edit()
        var count = 0
        values.keys().forEach { k ->
            // Nur bekannte Einstellungs-Schlüssel übernehmen — niemals Zugangsdaten
            if (k !in backupKeys && backupPrefixes.none { k.startsWith(it) }) return@forEach
            val o = values.optJSONObject(k) ?: return@forEach
            when (o.optString("t")) {
                "s" -> ed.putString(k, o.optString("v"))
                "b" -> ed.putBoolean(k, o.optBoolean("v"))
                "i" -> ed.putInt(k, o.optInt("v"))
                "l" -> ed.putLong(k, o.optLong("v"))
                else -> return@forEach
            }
            count++
        }
        ed.apply()
        refreshFlowsAfterImport()
        return count
    }

    private fun refreshFlowsAfterImport() {
        colorSchemeFlow.value = colorScheme
        customColorFlow.value = customColor
        darkModeFlow.value = darkMode
        conversationViewFlow.value = conversationView
        inboxLayoutFlow.value = inboxLayout
        aiEngineFlow.value = aiEngine
        swipeLeftFlow.value = swipeLeftAction
        swipeRightFlow.value = swipeRightAction
        pushModeFlow.value = pushMode
        mutedFlow.value = loadSet("muted_senders")
        blockedFlow.value = loadSet("blocked_senders")
        vipFlow.value = loadSet("vip_senders")
        vipOnlyFlow.value = vipOnlyNotifications
        notifActionsFlow.value = notifActions
        accountColorsFlow.value++
        hiddenFoldersFlow.value++
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

    fun addVip(address: String) {
        val key = address.trim().lowercase()
        if (key.isBlank() || !key.contains("@")) return
        val set = vipFlow.value + key
        saveSet("vip_senders", set); vipFlow.value = set
    }

    fun removeVip(address: String) {
        val set = vipFlow.value - address.trim().lowercase()
        saveSet("vip_senders", set); vipFlow.value = set
    }

    fun isVip(address: String) = address.trim().lowercase() in vipFlow.value

    /** Nur VIP-Absender lösen Benachrichtigungen aus (Rest kommt lautlos an). */
    var vipOnlyNotifications: Boolean
        get() = sp.getBoolean("vip_only_notif", false)
        set(v) {
            sp.edit().putBoolean("vip_only_notif", v).apply()
            vipOnlyFlow.value = v
        }

    /**
     * Aktions-Knöpfe der Mail-Benachrichtigung, in fester Reihenfolge
     * (reply/read/archive/delete). Android zeigt höchstens 3 Aktionen an,
     * deshalb werden maximal 3 gespeichert.
     */
    var notifActions: List<String>
        get() = try {
            val a = org.json.JSONArray(
                sp.getString("notif_actions", null) ?: """["reply","read","delete"]"""
            )
            (0 until a.length()).map { a.getString(it) }
                .filter { it in listOf("reply", "read", "archive", "delete") }
                .take(3)
        } catch (e: Exception) {
            listOf("reply", "read", "delete")
        }
        set(v) {
            // Reihenfolge des Nutzers beibehalten (per Drag & Drop änderbar)
            val cleaned = v
                .filter { it in listOf("reply", "read", "archive", "delete") }
                .distinct()
                .take(3)
            sp.edit().putString("notif_actions", org.json.JSONArray(cleaned).toString()).apply()
            notifActionsFlow.value = cleaned
        }

    /**
     * Standard-Absender für NEUE Mails ("" = aktives Konto). Antworten gehen
     * unabhängig davon immer über das Konto, in dem die Mail ankam.
     */
    var defaultSendAccount: String
        get() = sp.getString("default_send_account", "") ?: ""
        set(v) = sp.edit().putString("default_send_account", v.trim()).apply()

    /** Wurde der Willkommens-Bildschirm beim ersten Start bereits gezeigt? */
    var welcomeShown: Boolean
        get() = sp.getBoolean("welcome_shown", false)
        set(v) = sp.edit().putBoolean("welcome_shown", v).apply()

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit().putString("email", v.trim()).apply()

    var appPassword: String
        get() = sp.getString("app_password", "") ?: ""
        set(v) = sp.edit().putString("app_password", v.replace(" ", "")).apply()

    /** Eigener Claude-API-Schlüssel (Altbestand): wird derzeit nicht mehr
     *  genutzt — die KI läuft ausschließlich über den BlockMail-Proxy.
     *  Getter/Setter bleiben für Migration/Altdaten erhalten. */
    var claudeApiKey: String
        get() = sp.getString("claude_key", "") ?: ""
        set(v) = sp.edit().putString("claude_key", v.trim()).apply()

    /**
     * Installations-Token: wird beim ersten Zugriff als zufällige UUID
     * erzeugt und dauerhaft gespeichert. Dient als Bearer-Token beim
     * KI-Proxy des App-Betreibers (blockwerk-orange.de/blockmail) und wird
     * später durch das Play-Abo-Token ersetzt.
     */
    val installToken: String
        get() {
            val existing = sp.getString("install_token", null)
            if (!existing.isNullOrBlank()) return existing
            val token = java.util.UUID.randomUUID().toString()
            sp.edit().putString("install_token", token).apply()
            return token
        }

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

    fun removeKnownRecipient(address: String) {
        try {
            val o = JSONObject(sp.getString("known_recipients", "{}") ?: "{}")
            o.remove(address.trim().lowercase())
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

    /** Lokaler Suchindex: automatisch im Hintergrund indexieren? Standard: an. */
    var indexEnabled: Boolean
        get() = sp.getBoolean("index_enabled", true)
        set(v) {
            sp.edit().putBoolean("index_enabled", v).apply()
            indexEnabledFlow.value = v
        }

    /**
     * Lokaler Suchindex: nur Mails indexieren, die jünger sind als so viele
     * Jahre (0 = keine Zeitgrenze, alles indexieren). Standard: 1 Jahr.
     */
    var indexYears: Int
        get() = sp.getInt("index_years", 1)
        set(v) {
            sp.edit().putInt("index_years", v).apply()
            indexYearsFlow.value = v
        }

    /** Schlichtes Design: keine Verläufe/Hintergründe in den Mail-Listen. */
    var plainDesign: Boolean
        get() = sp.getBoolean("plain_design", false)
        set(v) {
            sp.edit().putBoolean("plain_design", v).apply()
            plainDesignFlow.value = v
        }

    /** Tägliches Newsletter-Aufräumen (20 Uhr) aktiv? Standard: an. */
    var newsletterAutoEnabled: Boolean
        get() = sp.getBoolean("newsletter_auto", true)
        set(v) {
            sp.edit().putBoolean("newsletter_auto", v).apply()
            newsletterAutoFlow.value = v
        }

    /** Datum (yyyy-MM-dd) des letzten Newsletter-Aufräumlaufs. */
    var lastNewsletterRunDay: String
        get() = sp.getString("newsletter_last_run", "") ?: ""
        set(v) = sp.edit().putString("newsletter_last_run", v).apply()

    /** Höchste bereits per Push verarbeitete Mail-UID (für Lücken-Nachholung). */
    // Pro Konto eigene Merkliste (Fallback auf den alten globalen Schlüssel)
    private fun pushUidKey() = "last_push_uid_" + email.trim().lowercase()

    // KEIN Rückfall auf den alten globalen Schlüssel: Der kann die UID eines
    // anderen Kontos enthalten — dann gilt jede neue Mail als "schon gemeldet"
    // und der Push schweigt dauerhaft. Ohne Eintrag (0) wird beim ersten
    // Verbinden still der aktuelle Stand als Ausgangspunkt gemerkt.
    var lastPushUid: Long
        get() = sp.getLong(pushUidKey(), 0L)
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
        val html: String?,
        /** Absender-Konto ("" = aktives Konto beim Senden). */
        val account: String = ""
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
                html = if (o.has("html")) o.getString("html") else null,
                account = o.optString("account")
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
                if (m.account.isNotBlank()) put("account", m.account)
            })
        }
        sp.edit().putString("outbox", arr.toString()).apply()
    }

    fun addOutbox(m: ScheduledMail) = saveOutbox(outbox() + m)

    fun removeOutbox(id: Long) = saveOutbox(outbox().filter { it.id != id })

    /** Automatisch gespeicherter Entwurf aus dem Verfassen-Fenster. */
    data class Draft(
        val id: Long,
        val savedAt: Long,
        val to: String,
        val cc: String,
        val bcc: String,
        val subject: String,
        val html: String,
        val account: String = ""
    )

    fun drafts(): List<Draft> = try {
        val arr = org.json.JSONArray(sp.getString("drafts", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Draft(
                id = o.getLong("id"),
                savedAt = o.getLong("savedAt"),
                to = o.optString("to"),
                cc = o.optString("cc"),
                bcc = o.optString("bcc"),
                subject = o.optString("subject"),
                html = o.optString("html"),
                account = o.optString("account")
            )
        }.sortedByDescending { it.savedAt }
    } catch (e: Exception) {
        emptyList()
    }

    private fun persistDrafts(list: List<Draft>) {
        val arr = org.json.JSONArray()
        // Neueste zuerst, höchstens 20 Entwürfe aufheben
        list.sortedByDescending { it.savedAt }.take(20).forEach { d ->
            arr.put(org.json.JSONObject().apply {
                put("id", d.id); put("savedAt", d.savedAt)
                put("to", d.to); put("cc", d.cc); put("bcc", d.bcc)
                put("subject", d.subject); put("html", d.html)
                put("account", d.account)
            })
        }
        sp.edit().putString("drafts", arr.toString()).apply()
        draftsFlow.value = drafts()
    }

    fun saveDraft(d: Draft) = persistDrafts(drafts().filter { it.id != d.id } + d)

    fun removeDraft(id: Long) = persistDrafts(drafts().filter { it.id != id })

    // ---- Phishing-Wächter: Markierungen für die Kachel-/Listenansicht ----

    fun phishingKey(account: String, uid: Long) = "${account.trim().lowercase()}:$uid"

    fun markPhishing(account: String, uid: Long, suspicious: Boolean) {
        val key = phishingKey(account, uid)
        val current = phishingFlow.value
        val updated = if (suspicious) current + key else current - key
        if (updated == current) return
        // Liste begrenzen, damit sie nicht endlos wächst
        val capped = if (updated.size > 200) updated.drop(updated.size - 200).toSet() else updated
        saveSet("phishing_mails", capped)
        phishingFlow.value = capped
    }

    fun isPhishing(account: String, uid: Long) =
        phishingKey(account, uid) in phishingFlow.value

    /** Nutzer-Freigabe: „Das ist kein Phishing“ — Warnung dauerhaft entfernen. */
    fun markNotPhishing(account: String, uid: Long) {
        val key = phishingKey(account, uid)
        val set = loadSet("phishing_ok") + key
        val capped = if (set.size > 300) set.drop(set.size - 300).toSet() else set
        saveSet("phishing_ok", capped)
        markPhishing(account, uid, false)
    }

    fun isPhishingCleared(account: String, uid: Long) =
        phishingKey(account, uid) in loadSet("phishing_ok")

    // ---- Antwort-Radar: offene Fragen und ausbleibende Antworten ----

    /** Fokus-Blöcke: Posteingang nach Wichtigkeit gruppieren statt nach Zeit. */
    var focusMode: Boolean
        get() = sp.getBoolean("focus_mode", false)
        set(v) {
            sp.edit().putBoolean("focus_mode", v).apply()
            focusModeFlow.value = v
        }

    /** Antwort-Radar aktiv? Erinnert täglich an Unbeantwortetes. */
    var radarEnabled: Boolean
        get() = sp.getBoolean("radar_enabled", true)
        set(v) {
            sp.edit().putBoolean("radar_enabled", v).apply()
            radarFlow.value = v
        }

    var lastRadarRunDay: String
        get() = sp.getString("last_radar_day", "") ?: ""
        set(v) = sp.edit().putString("last_radar_day", v).apply()

    /** Merkt: auf diese Mail wurde aus der App geantwortet. */
    fun addReplied(account: String, uid: Long) {
        val set = loadSet("replied_mails") + phishingKey(account, uid)
        val capped = if (set.size > 300) set.drop(set.size - 300).toSet() else set
        saveSet("replied_mails", capped)
    }

    fun isReplied(account: String, uid: Long) =
        phishingKey(account, uid) in loadSet("replied_mails")

    /** Gesendete Mails (Empfänger, Betreff, Zeit) für „wartet auf Antwort“. */
    data class SentEntry(val to: String, val subject: String, val at: Long)

    fun sentLog(): List<SentEntry> = try {
        val arr = org.json.JSONArray(sp.getString("sent_log", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SentEntry(o.optString("to"), o.optString("subject"), o.optLong("at"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun addSentLog(to: String, subject: String) {
        val key = to.trim().lowercase()
        if (!key.contains("@")) return
        val list = (sentLog() + SentEntry(key, subject, System.currentTimeMillis()))
            .takeLast(50)
        val arr = org.json.JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("to", e.to); put("subject", e.subject); put("at", e.at)
            })
        }
        sp.edit().putString("sent_log", arr.toString()).apply()
    }

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

    /** Frei gewählte Akzentfarbe (ARGB) für das Farbschema „Eigene Farbe“. */
    var customColor: Int
        get() = sp.getInt("custom_color", 0xFFEE5F0F.toInt())
        set(v) {
            sp.edit().putInt("custom_color", v).apply()
            customColorFlow.value = v
        }

    /** Posteingangs-Darstellung: "blocks" (Raster, Standard) oder "list". */
    var inboxLayout: String
        get() = sp.getString("inbox_layout", "blocks") ?: "blocks"
        set(v) {
            sp.edit().putString("inbox_layout", v).apply()
            inboxLayoutFlow.value = v
        }

    /** Posteingang: Mails mit gleichem Betreff als Konversation bündeln. */
    var conversationView: Boolean
        get() = sp.getBoolean("conversation_view", false)
        set(v) {
            sp.edit().putBoolean("conversation_view", v).apply()
            conversationViewFlow.value = v
        }

    /** KI-Wahl: "auto" (Claude wenn Schlüssel da, sonst Geräte-KI), "claude", "gemini". */
    var aiEngine: String
        get() = sp.getString("ai_engine", "auto") ?: "auto"
        set(v) {
            sp.edit().putString("ai_engine", v).apply()
            aiEngineFlow.value = v
        }

    /**
     * Entwicklermodus (7-mal auf die Versionsnummer tippen): zeigt die
     * Google-Anmeldung, die ohne Google-Überprüfung nur für Testnutzer
     * des Entwicklers funktioniert.
     */
    var devMode: Boolean
        get() = sp.getBoolean("dev_mode", false)
        set(v) {
            sp.edit().putBoolean("dev_mode", v).apply()
            devModeFlow.value = v
        }

    /**
     * Benachrichtigungs-Modus: "push" = Dauerverbindung (Echtzeit, mit stiller
     * Dienst-Benachrichtigung), "eco" = Sparmodus (Prüfung alle ~15 Minuten
     * über den Wächter-Worker, ohne Dauer-Benachrichtigung).
     */
    var pushMode: String
        get() = sp.getString("push_mode", "push") ?: "push"
        set(v) {
            sp.edit().putString("push_mode", v).apply()
            pushModeFlow.value = v
        }

    /** Wisch-Aktion nach links: "delete", "archive", "read" oder "snooze". */
    var swipeLeftAction: String
        get() = sp.getString("swipe_left", "delete") ?: "delete"
        set(v) {
            sp.edit().putString("swipe_left", v).apply()
            swipeLeftFlow.value = v
        }

    /** Wisch-Aktion nach rechts: "delete", "archive", "read" oder "snooze". */
    var swipeRightAction: String
        get() = sp.getString("swipe_right", "read") ?: "read"
        set(v) {
            sp.edit().putString("swipe_right", v).apply()
            swipeRightFlow.value = v
        }

    /** Farbe eines Kontos (ARGB) oder null, wenn keine gewählt wurde. */
    fun accountColor(accountEmail: String): Int? {
        val v = sp.getInt("account_color_" + accountEmail.trim().lowercase(), 0)
        return if (v == 0) null else v
    }

    fun setAccountColor(accountEmail: String, color: Int?) {
        val key = "account_color_" + accountEmail.trim().lowercase()
        if (color == null) sp.edit().remove(key).apply()
        else sp.edit().putInt(key, color).apply()
        accountColorsFlow.value++
    }

    /**
     * Im Ordner-Menü ausgeblendete Ordner eines Kontos (MailFolder-Namen,
     * z. B. "ARCHIVE"). Standard: leer — alle Ordner sichtbar wie bisher.
     */
    fun hiddenFolders(accountEmail: String): Set<String> = try {
        val a = org.json.JSONArray(
            sp.getString("hidden_folders_" + accountEmail.trim().lowercase(), "[]") ?: "[]"
        )
        (0 until a.length()).map { a.getString(it) }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    fun setHiddenFolders(accountEmail: String, hidden: Set<String>) {
        sp.edit().putString(
            "hidden_folders_" + accountEmail.trim().lowercase(),
            org.json.JSONArray(hidden.toList()).toString()
        ).apply()
        hiddenFoldersFlow.value++
    }

    /** Erscheinungsbild: "dark" (Standard), "light" oder "system" (Gerät folgt). */
    var darkMode: String
        get() = sp.getString("dark_mode", "dark") ?: "dark"
        set(v) {
            sp.edit().putString("dark_mode", v).apply()
            darkModeFlow.value = v
        }

    val isConfigured: Boolean
        get() = email.isNotBlank() && (
            (authMethod == "oauth" && refreshToken.isNotBlank()) || appPassword.isNotBlank()
        )
}
