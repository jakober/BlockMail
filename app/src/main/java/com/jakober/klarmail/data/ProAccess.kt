package com.jakober.klarmail.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Zentrale Schaltstelle für „BlockMail Pro“.
 *
 * ALLE KI-Funktionen der App sind Pro-Funktionen und hängen ausschließlich
 * an diesem Schalter:
 *
 *  - KI-Suche („Frag dein Postfach“ in der Suchleiste des Posteingangs)
 *  - KI-Zusammenfassen einzelner Mails (Detail-Ansicht)
 *  - Tages-Überblick / Ungelesenes zusammenfassen (KI-Knopf im Posteingang)
 *  - Antwort entwerfen und Mail formulieren mit KI (Verfassen-Fenster)
 *  - Rechtschreibprüfung per KI (Verfassen-Fenster)
 *
 * OHNE Pro bleibt die App voll benutzbar, nur eben ohne KI: Die Suchleiste
 * arbeitet dann als reine Textsuche (Live-Filter plus Server-Volltextsuche —
 * derselbe Weg wie der Chip „Volltext (Server)“), statt der KI-Funktionen
 * erscheint ein Hinweis-Dialog (ProUpsellDialog). Der Antwort-Radar und der
 * Phishing-Wächter sind bewusst KEINE Pro-Funktionen — das sind lokale
 * Heuristiken ohne KI und bleiben immer frei.
 *
 * [TEST_PHASE_UNLOCK] schaltet Pro pauschal für alle frei. Der Schalter
 * steht seit v3.87 auf false: Ab jetzt zählt ausschließlich das über Google
 * Play gekaufte Abo, das der [BillingManager] meldet. Tester kaufen über
 * die Lizenztests der Play Console kostenlos.
 */
object ProAccess {

    /**
     * Pro pauschal für alle freischalten. Steht auf false — es zählt
     * ausschließlich das über Play gekaufte Abo (siehe
     * [com.jakober.klarmail.data.BillingManager]). Nur wieder auf true
     * stellen, wenn Pro absichtlich verschenkt werden soll.
     */
    const val TEST_PHASE_UNLOCK = false

    /** Laufendes Play-Abo vorhanden? Wird vom BillingManager gesetzt. */
    private var subscribed = false

    private val _isPro = MutableStateFlow(TEST_PHASE_UNLOCK)

    /** Beobachtbarer Pro-Status für Composables (`collectAsState`). */
    val isProFlow: StateFlow<Boolean> = _isPro

    /** Momentaner Pro-Status für Nicht-Compose-Pfade (Services, Worker). */
    val isPro: Boolean get() = _isPro.value

    /**
     * Zeigt an, ob ein echtes Abo läuft — unabhängig von der Testphase.
     * Die Einstellungen unterscheiden damit „Pro über Abo“ von „Pro, weil
     * gerade Testphase“.
     */
    val hasSubscription: Boolean get() = subscribed

    /** Vom BillingManager aufgerufen, sobald der Abo-Status feststeht. */
    fun setSubscribed(active: Boolean) {
        subscribed = active
        _isPro.value = active || TEST_PHASE_UNLOCK
    }

    /** Setzt den Pro-Status neu aus Testphasen-Schalter und Abo-Status. */
    fun refresh() {
        _isPro.value = subscribed || TEST_PHASE_UNLOCK
    }
}
