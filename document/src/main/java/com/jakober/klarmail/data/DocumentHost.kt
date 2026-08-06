package com.jakober.klarmail.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Anschlussstelle der Gast-App an den gemeinsamen Dokument-Editor.
 *
 * Der Editor läuft in ZWEI Apps (BlockMail und BlockPDF), die sich in genau
 * drei Punkten unterscheiden: Wer darf bearbeiten, wie sieht der
 * Kauf-Hinweis aus, und über welche FileProvider-Autorität werden
 * Ergebnisse geteilt. Genau diese drei Punkte stellt jede App hier beim
 * Start ein — alles andere bleibt identisch.
 */
object DocumentHost {

    /** FileProvider-Autorität der Gast-App (Pflicht, beim App-Start setzen). */
    @Volatile
    var fileProviderAuthority: String = ""

    /** Darf bearbeitet und gespeichert werden? (BlockMail: Pro-Abo.) */
    var editAllowedFlow: StateFlow<Boolean> = MutableStateFlow(true)

    /**
     * Kauf-Hinweis der Gast-App. Bekommt die Schließen-Aktion gereicht und
     * zeigt ihren eigenen Dialog. null = kein Hinweis (alles erlaubt).
     */
    var upsell: (@androidx.compose.runtime.Composable (onDismiss: () -> Unit) -> Unit)? = null
}
