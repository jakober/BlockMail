package com.jakober.klarmail.data

/**
 * Übergabe von außen ans Verfassen-Fenster: Wer in einer anderen App auf
 * eine Mail-Adresse tippt (mailto:-Link) oder „per E-Mail senden“ wählt,
 * landet in MainActivity — die legt Adresse, Betreff und Text hier ab,
 * ComposeScreen liest den Merker einmalig aus und leert ihn.
 */
object ComposePrefill {

    data class Data(
        val to: String = "",
        val cc: String = "",
        val bcc: String = "",
        val subject: String = "",
        val body: String = ""
    )

    @Volatile
    var pending: Data? = null
}
