# BlockMail

Schlichte, moderne Android-Mail-App für Gmail (Kotlin + Jetpack Compose, Material 3).

## Funktionen
- Gmail-Anbindung per Google-Anmeldung (OAuth) oder App-Passwort
- Echtzeit-Push (IMAP IDLE) mit „Als gelesen"-Aktion in der Benachrichtigung
- Neue Mails werden beim Empfang komplett vorgeladen — inklusive extern verlinkter Bilder (Öffnen ohne Wartezeit, auch offline); Cache wird nach einer Woche automatisch geleert
- In der App gelesene Mails schließen ihre noch offene Benachrichtigung
- Moderner Posteingang in Karten-Optik: Textvorschau je Mail, Akzentstreifen für Ungelesene, sanfte Listen-Animationen
- Ungelesene oben („Neu"), gelesene nach Zeit gruppiert (Heute/Gestern/Diese Woche/Älter); Wischgesten (rechts: gelesen/ungelesen, links: löschen)
- Endlos-Scrollen: am Listenende werden automatisch die nächsten 100 älteren Mails nachgeladen
- HTML-Mailansicht inkl. eingebetteter Bilder; Anhänge öffnen/speichern; Anhänge mitsenden
- Volltext-Suche (Absender/Betreff) über alle Mails
- Ordner: Posteingang, Gesendet, Entwürfe, Archiv, Papierkorb
- Rich-Text-Editor mit CC/BCC und Empfänger-Vorschlägen
- Claude-KI (eigener API-Key): Antwort entwerfen, Mail formulieren, Rechtschreibprüfung
- Tägliches KI-Newsletter-Aufräumen mit Protokoll und Abmelde-Links
- Stumm-/Blockier-Listen pro Absender; Mehrfachauswahl
- Mehrere Farbschemas; Tablet-/Querformat-Zweispaltenansicht

## Build
Voraussetzungen: JDK 17+ (getestet mit JDK 21), Android SDK (Platform 35, Build-Tools).

```
git clone <repo-url>
cd BlockMail
```

`local.properties` mit dem Pfad zum Android SDK anlegen (oder Umgebungsvariable `ANDROID_HOME` setzen):

```
sdk.dir=C:\\Users\\<name>\\AppData\\Local\\Android\\Sdk
```

Debug-APK bauen:

```
./gradlew assembleDebug
```

Die APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.

## Hinweis
Für die Google-Anmeldung ist eine OAuth-Client-ID (Android) in der Google Cloud Console hinterlegt,
gebunden an Paketname `com.jakober.klarmail` und den Signatur-Fingerabdruck. Bei einer anderen
Signierung muss der SHA-1-Fingerabdruck dort ergänzt werden.

Alle Builds (lokal und GitHub Actions) signieren mit dem festen Schlüssel
`keystore/blockmail-debug.keystore`, damit Updates ohne Neuinstallation möglich sind.
Sein SHA-1-Fingerabdruck (für die Google Cloud Console):
`0F:F9:2E:B8:A9:EA:48:FA:DF:B3:32:0F:DC:9D:CB:E0:A9:6A:CA:FA`
