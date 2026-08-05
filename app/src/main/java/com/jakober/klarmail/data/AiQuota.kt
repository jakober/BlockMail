package com.jakober.klarmail.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Monatliches KI-Kontingent des laufenden „BlockMail Pro“-Abos.
 *
 * Gezählt wird ausschließlich auf dem BlockMail-Server — nur der weiß, wie
 * viele Anfragen wirklich durchgelaufen sind (auch von einem zweiten Gerät).
 * Die App fragt den Stand über `GET /v1/quota` ab; die Antwort sieht so aus:
 *
 * ```json
 * { "plan": "pro-150", "limit": 150, "used": 37,
 *   "remaining": 113, "resets_at": "2026-09-01T00:00:00Z" }
 * ```
 *
 * Auth wie beim KI-Aufruf selbst (Bearer-Installations-Token, X-App-Package,
 * X-Purchase-Token). Antwortet der Server nicht oder kennt er den Endpunkt
 * noch nicht, bleibt [info] leer und die Anzeige blendet die Zeile aus —
 * die App funktioniert dann unverändert weiter.
 */
object AiQuota {

    private const val URL = "https://blockwerk-orange.de/blockmail/v1/quota"

    /** Ein abgerufener Kontingent-Stand. */
    data class Info(
        val plan: String,
        val limit: Int,
        val used: Int,
        val remaining: Int,
        /** Zeitpunkt des nächsten Kontingents (ms seit 1970), 0 = unbekannt. */
        val resetsAt: Long
    )

    private val _info = MutableStateFlow<Info?>(null)
    val info: StateFlow<Info?> = _info

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /** Löscht den Stand (Abo beendet). */
    fun clear() {
        _info.value = null
    }

    /**
     * Eine Anfrage ist durchgelaufen: Zähler sofort lokal weiterdrehen,
     * damit die Anzeige nicht bis zur nächsten Server-Abfrage nachhinkt.
     */
    fun noteUsed() {
        val cur = _info.value ?: return
        _info.value = cur.copy(
            used = cur.used + 1,
            remaining = (cur.remaining - 1).coerceAtLeast(0)
        )
    }

    /** Abruf im Hintergrund anstoßen (kein Warten, keine Fehleranzeige). */
    fun refreshSoon() {
        scope.launch { runCatching { refresh() } }
    }

    /**
     * Fragt den Stand beim Server ab. Wirft nicht — bei Problemen bleibt der
     * bisherige Stand stehen und es wird false zurückgegeben.
     */
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        _loading.value = true
        try {
            val request = Request.Builder()
                .url(URL)
                .header("Authorization", "Bearer ${Prefs.installToken}")
                .header("X-App-Package", "com.jakober.klarmail")
                .apply {
                    val token = Prefs.purchaseToken
                    if (token.isNotBlank()) header("X-Purchase-Token", token)
                }
                .get()
                .build()
            val parsed = runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) return@use null
                    val json = JSONObject(body)
                    val limit = json.optInt("limit", -1)
                    if (limit < 0) return@use null
                    val used = json.optInt("used", 0)
                    Info(
                        plan = json.optString("plan", Prefs.proPlan),
                        limit = limit,
                        used = used,
                        remaining = json.optInt("remaining", (limit - used).coerceAtLeast(0)),
                        resetsAt = parseReset(json.optString("resets_at"))
                    )
                }
            }.getOrNull() ?: return@withContext false
            _info.value = parsed
            if (parsed.plan.isNotBlank()) BillingManager.planFromServer(parsed.plan)
            true
        } finally {
            _loading.value = false
        }
    }

    /** ISO-8601-Zeitstempel des Servers in Millisekunden. */
    private fun parseReset(raw: String): Long {
        if (raw.isBlank()) return 0L
        runCatching { return java.time.Instant.parse(raw).toEpochMilli() }
        runCatching {
            return java.time.OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        }
        runCatching {
            return java.time.LocalDate.parse(raw)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli()
        }
        return 0L
    }

    /** Zurücksetz-Datum als kurzer Text in der Gerätesprache ("" = unbekannt). */
    fun formatReset(at: Long): String {
        if (at <= 0L) return ""
        return java.text.DateFormat
            .getDateInstance(java.text.DateFormat.MEDIUM)
            .format(java.util.Date(at))
    }
}
