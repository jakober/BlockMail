# R8-Schutzregeln für BlockMail.
#
# Grundsatz: R8 entfernt und verschleiert alles, was es nicht als benutzt
# erkennt. Bibliotheken, die Klassen per Reflexion oder über
# META-INF-Dienstlisten laden, brechen dann stumm — die Regeln hier halten
# genau diese Teile unangetastet.

# --- Jakarta Mail (IMAP/SMTP) -------------------------------------------
# Protokoll-Provider (imaps/smtp) und Datentyp-Handler werden zur Laufzeit
# über Dienstlisten und Reflexion aufgelöst. Ohne Keep: "no provider for
# imaps" — kein Mail-Laden, kein Versand.
-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class com.sun.activation.** { *; }
# Desktop-Java-Verweise, die auf Android nie erreicht werden
-dontwarn java.awt.**
-dontwarn java.beans.**
-dontwarn javax.security.sasl.**

# --- PDFBox (Android-Port, Entsperren geschützter PDFs) ------------------
# Schrift-, Codec- und Filterklassen teils über Reflexion instanziiert
-keep class com.tom_roush.** { *; }
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**

# --- Google-OAuth (AppAuth) ----------------------------------------------
# Zustände werden über JSON-Feldnamen serialisiert (App-Neustart mitten im
# Anmeldefluss) — Verschleierung würde gespeicherte Zustände entwerten
-keep class net.openid.appauth.** { *; }
