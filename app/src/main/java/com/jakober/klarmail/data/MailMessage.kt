package com.jakober.klarmail.data

import org.json.JSONArray
import org.json.JSONObject

data class MailMessage(
    val uid: Long,
    val subject: String,
    val from: String,
    val fromAddress: String,
    val date: Long,
    val seen: Boolean,
    val hasAttachments: Boolean = false,
    /**
     * Als wichtig markiert. Entspricht dem IMAP-Kennzeichen \Flagged, also
     * demselben Stern, den Gmail, Thunderbird und Outlook benutzen — die
     * Markierung ist damit auf allen Geräten und Programmen dieselbe.
     */
    val flagged: Boolean = false,
    /**
     * Schon beantwortet? Entspricht dem IMAP-Kennzeichen \Answered — wird
     * gesetzt, wenn aus BlockMail geantwortet wird, und kommt auch von
     * anderen Programmen (Gmail, Thunderbird …) über den Server mit.
     */
    val answered: Boolean = false,
    val snippet: String? = null,
    /** Konto-Zuordnung im Sammel-Posteingang ("" = aktives Konto). */
    val account: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("uid", uid)
        put("subject", subject)
        put("from", from)
        put("fromAddress", fromAddress)
        put("date", date)
        put("seen", seen)
        put("hasAttachments", hasAttachments)
        if (flagged) put("flagged", true)
        if (answered) put("answered", true)
        snippet?.let { put("snippet", it) }
        if (account.isNotBlank()) put("account", account)
    }

    companion object {
        fun fromJson(o: JSONObject) = MailMessage(
            uid = o.getLong("uid"),
            subject = o.optString("subject"),
            from = o.optString("from"),
            fromAddress = o.optString("fromAddress"),
            date = o.optLong("date"),
            seen = o.optBoolean("seen", true),
            hasAttachments = o.optBoolean("hasAttachments", false),
            flagged = o.optBoolean("flagged", false),
            answered = o.optBoolean("answered", false),
            // has()-Check statt optString: "" würde als "Vorschau vorhanden" gelten
            snippet = if (o.has("snippet")) o.getString("snippet") else null,
            account = o.optString("account")
        )

        fun listToJson(list: List<MailMessage>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun listFromJson(s: String): List<MailMessage> = try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
