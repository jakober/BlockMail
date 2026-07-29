package com.jakober.klarmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Findet eine brauchbare Logo-/Favicon-URL zu einer Absender-Domain.
 *
 * Der Google-Favicon-Dienst wird bewusst NICHT mehr genutzt: Er liefert
 * für ihm unbekannte Domains statt eines Fehlers eine unscharfe
 * Standard-Weltkugel als "Erfolg" aus — die lässt sich clientseitig nicht
 * von einem echten Logo unterscheiden und sah in der Mail-Ansicht
 * entsprechend schlecht aus. Die Quellen hier antworten für unbekannte
 * Domains mit einem Fehler, sodass sauber auf den Initialen-Kreis
 * zurückgefallen werden kann.
 */
object SenderIcon {

    /** Quellen in Prioritätsreihenfolge — auch vom Listen-Avatar genutzt. */
    fun candidatesFor(domain: String): List<String> = listOf(
        // Marken-Logo-Dienst (hochauflösende Firmenlogos)
        "https://logo.clearbit.com/$domain?size=256",
        // DuckDuckGo löst auch per <link rel="icon"> deklarierte Favicons auf
        "https://icons.duckduckgo.com/ip3/$domain.ico",
        // Klassischer Standardpfad direkt auf der Domain
        "https://$domain/favicon.ico"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Domain -> gefundene URL ("" = nichts gefunden). Nur für die Laufzeit —
    // die Bilddaten selbst cacht ohnehin der Bild-Lader bzw. die WebView.
    private val cache = ConcurrentHashMap<String, String>()

    /**
     * Liefert die erste funktionierende Icon-URL der Domain oder null
     * (dann zeigt der Aufrufer den Initialen-Kreis). Ergebnis wird pro
     * Domain gemerkt, damit jede Domain nur einmal geprüft wird.
     */
    suspend fun resolve(domain: String): String? {
        if (domain.isBlank()) return null
        cache[domain]?.let { return it.ifBlank { null } }
        val found = withContext(Dispatchers.IO) {
            candidatesFor(domain).firstOrNull { probe(it) }
        }
        cache[domain] = found ?: ""
        return found
    }

    /** true, wenn die URL wirklich ein Bild liefert (kein Fehler, kein HTML). */
    private fun probe(url: String): Boolean = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            r.isSuccessful &&
                (r.header("Content-Type") ?: "").startsWith("image") &&
                r.body?.contentLength() != 0L
        }
    }.getOrDefault(false)
}
