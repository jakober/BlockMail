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
 * Gezählt wird an ZWEI Stellen, und das mit Absicht:
 *
 *  1. **Auf dem Gerät** ([Prefs.aiUsedLocal]) — sofort, ohne Netz, und die
 *     App sperrt die KI-Funktionen selbst, sobald das Kontingent leer ist.
 *     Damit funktioniert die Deckelung auch, bevor der Server sie kann.
 *  2. **Auf dem BlockMail-Server** — der zählt verbindlich, weil nur er
 *     alle Geräte eines Abos sieht und weil ein Zähler im Gerät sich durch
 *     Löschen der App-Daten zurücksetzen ließe. Antwortet der Server, gilt
 *     sein Stand und der lokale Zähler wird darauf nachgeführt.
 *
 * Der Stand kommt über `GET /v1/quota`; die Antwort sieht so aus:
 *
 * ```json
 * { "plan": "pro-150", "limit": 150, "used": 37,
 *   "remaining": 113, "resets_at": "2026-09-01T00:00:00Z" }
 * ```
 *
 * Auth wie beim KI-Aufruf selbst (Bearer-Installations-Token, X-App-Package,
 * X-Purchase-Token). Antwortet der Server nicht oder kennt er den Endpunkt
 * noch nicht, gilt einfach der Zähler im Gerät — die App funktioniert dann
 * unverändert weiter, nur eben ohne geräteübergreifende Zählung.
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
        val resetsAt: Long,
        /** true = vom Server gemeldet, false = im Gerät mitgezählt. */
        val fromServer: Boolean
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

    /** Setzt den Stand auf den lokalen Zähler zurück (Abo beendet). */
    fun clear() {
        _info.value = localInfo()
    }

    /** Laufender Abrechnungsmonat als "yyyy-MM". */
    private fun currentPeriod(): String {
        val c = java.util.Calendar.getInstance()
        return "%04d-%02d".format(
            c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1
        )
    }

    /** Erster Tag des Folgemonats, 0 Uhr — dann gibt es neues Kontingent. */
    private fun nextMonthStart(): Long {
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.MONTH, 1)
        c.set(java.util.Calendar.DAY_OF_MONTH, 1)
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /**
     * Stand aus dem Zähler im Gerät. Ist ein neuer Monat angebrochen, wird
     * dabei zurückgesetzt — das ist die einzige Stelle, an der das passiert.
     */
    private fun localInfo(): Info {
        val period = currentPeriod()
        if (Prefs.aiQuotaPeriod != period) {
            Prefs.aiQuotaPeriod = period
            Prefs.aiUsedLocal = 0
        }
        val plan = Prefs.proPlan
        val limit = BillingManager.requestsFor(plan)
        val used = Prefs.aiUsedLocal.coerceAtMost(limit)
        return Info(
            plan = plan,
            limit = limit,
            used = used,
            remaining = (limit - used).coerceAtLeast(0),
            resetsAt = nextMonthStart(),
            fromServer = false
        )
    }

    /**
     * Sorgt dafür, dass ein Stand vorliegt (lokal, falls der Server noch
     * nichts geliefert hat) — und dass ein Monatswechsel greift.
     */
    fun ensureLoaded(): Info {
        val cur = _info.value
        val local = localInfo()
        // Server-Stand behalten, solange er zum laufenden Monat passt
        if (cur != null && cur.resetsAt > System.currentTimeMillis() &&
            Prefs.aiQuotaPeriod == currentPeriod() && cur.limit == local.limit
        ) return cur
        _info.value = local
        return local
    }

    /**
     * Sind im laufenden Monat noch Anfragen übrig? Die Gates in der
     * Oberfläche fragen hier, bevor sie eine KI-Aktion starten.
     */
    fun hasRequestsLeft(): Boolean = ensureLoaded().remaining > 0

    /**
     * Eine Anfrage ist durchgelaufen: Zähler im Gerät hochsetzen und die
     * Anzeige sofort mitdrehen, damit sie nicht bis zur nächsten
     * Server-Abfrage nachhinkt.
     */
    fun noteUsed() {
        val cur = ensureLoaded()
        Prefs.aiUsedLocal = Prefs.aiUsedLocal + 1
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
        // Ohne Kauf-Token kann der Server nur ablehnen (403
        // kein_kauf_token) — die Anfrage waere reiner Protokoll-Laerm.
        // Der Zaehler im Geraet gilt dann ohnehin.
        // Mit Entwickler-Schlüssel darf die Abfrage trotzdem raus — der
        // Server erkennt den Schlüssel und meldet das Dev-Kontingent
        val devKey = if (Prefs.devPro) Prefs.devKey else ""
        if (Prefs.purchaseToken.isBlank() && devKey.isBlank()) {
            ensureLoaded()
            return@withContext false
        }
        _loading.value = true
        try {
            val request = Request.Builder()
                .url(URL)
                .header("Authorization", "Bearer ${Prefs.installToken}")
                .header("X-App-Package", "com.jakober.klarmail")
                .apply {
                    val token = Prefs.purchaseToken
                    if (token.isNotBlank()) header("X-Purchase-Token", token)
                    if (devKey.isNotBlank()) header("X-Dev-Key", devKey)
                }
                .get()
                .build()
            val parsed = runCatching {
                http.newCall(request).execute().use { resp ->
                    if (resp.code == 403) {
                        // Der Server lehnt ab (subscription_required) —
                        // aber NUR bei Play gegenpruefen, nicht den Token
                        // ueberfuehren: Der Server kann voruebergehend falsch
                        // liegen (15-Minuten-Negativ-Cache, Google-Ruckler
                        // an einer Verlaengerungsgrenze). Ein einziges
                        // falsches 403 haette sonst ein LAUFENDES Abo fuer
                        // 48 Stunden ausgesperrt. Ist das Abo wirklich weg,
                        // raeumt die Play-Abfrage bzw. der Kaufdialog auf —
                        // und die KI bleibt serverseitig ohnehin gesperrt.
                        BillingManager.refreshPurchases()
                        return@use null
                    }
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
                        resetsAt = parseReset(json.optString("resets_at")),
                        fromServer = true
                    )
                }
            }.getOrNull() ?: run {
                // Kein Server-Stand: der Zähler im Gerät gilt weiter
                ensureLoaded()
                return@withContext false
            }
            // Welcher Tarif gebucht ist, weiß Google Play — nicht der
            // Server: In der Testphase (ALLOW_ALL) meldet dieser für JEDEN
            // ein fiktives „pro-150“. Weichen beide ab, gilt der Kauf; nur
            // der Verbrauch kommt vom Server, der zählt ihn verbindlich.
            val localPlan = Prefs.proPlan
            val reconciled = if (localPlan.isNotBlank() && parsed.plan != localPlan) {
                val limit = BillingManager.requestsFor(localPlan)
                parsed.copy(
                    plan = localPlan,
                    limit = limit,
                    remaining = (limit - parsed.used).coerceAtLeast(0)
                )
            } else {
                parsed
            }
            _info.value = reconciled
            // Zähler im Gerät auf den Server-Stand ziehen
            Prefs.aiQuotaPeriod = currentPeriod()
            Prefs.aiUsedLocal = reconciled.used
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
