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
    var lastPushUid: Long
        get() = sp.getLong("last_push_uid", 0L)
        set(v) = sp.edit().putLong("last_push_uid", v).apply()

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

    fun snoozes(): List<Snooze> = try {
        val arr = org.json.JSONArray(sp.getString("snoozes", "[]") ?: "[]")
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
        sp.edit().putString("snoozes", arr.toString()).apply()
        snoozedFlow.value = list.map { it.uid }.toSet()
    }

    fun addSnooze(s: Snooze) = saveSnoozes(snoozes().filter { it.uid != s.uid } + s)

    fun removeSnooze(uid: Long) = saveSnoozes(snoozes().filter { it.uid != uid })

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
