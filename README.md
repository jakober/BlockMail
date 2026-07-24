# BlockMail

Schlichte, moderne Android-Mail-App für Gmail (Kotlin + Jetpack Compose, Material 3).

## Funktionen
- Gmail-Anbindung per Google-Anmeldung (OAuth) oder App-Passwort
- Echtzeit-Push (IMAP IDLE) mit „Als gelesen"-Aktion in der Benachrichtigung
- Neue Mails werden beim Empfang komplett vorgeladen (Öffnen ohne Wartezeit); Cache wird nach einer Woche automatisch geleert
- In der App gelesene Mails schließen ihre noch offene Benachrichtigung
- Ungelesene oben, gelesene darunter; Wischgesten (rechts: gelesen/ungelesen, links: löschen)
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
