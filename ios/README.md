# BlockMail für iOS (TestFlight-Testversion)

SwiftUI-Portierung der Android-App (Stand v4.75) — **alle Funktionen freigeschaltet, kein Abo,
keine In-App-Käufe**. Gedacht ausschließlich für die Verteilung über TestFlight.

## Was ist anders als unter Android?

| Android | iOS |
|---|---|
| KI über den BlockMail-Proxy (Pro-Abo) | KI mit **eigenem Claude-API-Schlüssel** (Einstellungen → KI). Ohne Schlüssel: Apple Intelligence auf dem Gerät (iOS 26, unterstützte Geräte) |
| Echtzeit-Push per Dauerverbindung im Hintergrund | Echtzeit per IMAP IDLE, **solange die App offen ist**. Bei geschlossener App: echter Push über den eigenen **Push-Server** (`push-server/`), sonst iOS-Hintergrundabruf (ca. alle 15–60 min, iOS entscheidet) |
| Geplantes Senden per exaktem Wecker | Wird beim nächsten App-Start bzw. Hintergrundabruf nach dem Zeitpunkt verschickt (iOS erlaubt keine exakten Hintergrund-Wecker) |
| Launcher-Shortcuts | Quick Actions (App-Symbol lange drücken) |
| Gemini Nano | Apple Intelligence |
| Abo, Kontingent, Pro-Hinweise, Entwickler-Code | entfällt |
| BlockMail als Standard-Mail-App (mailto:), „Per E-Mail senden“ aus anderen Apps | nicht möglich ohne Apple-Sonderfreigabe (Mail-Client-Berechtigung); PDFs/Bilder lassen sich per „Öffnen in … BlockMail“ in den Editor holen |
| Absender-Logo in der Benachrichtigung | Standard-Mitteilung ohne Logo |

## Projekt bauen (Mac mit Xcode 16+ / 26)

```sh
brew install xcodegen
cd ios
python3 tools/convert_strings.py   # Texte aus den Android-Ressourcen übernehmen
xcodegen generate                  # erzeugt BlockMail.xcodeproj aus project.yml
open BlockMail.xcodeproj
```

Einstellungen stehen in `ios/Config.xcconfig` (Bundle-ID, Team-ID, Google-Client-ID, Version).

Ohne Mac: Die GitHub-Action **„iOS bauen“** prüft bei jedem Push, dass alles kompiliert.

## TestFlight einrichten (einmalig, ohne Mac möglich)

1. **Apple-Developer-Konto** (99 €/Jahr) muss aktiv sein.
2. **API-Schlüssel anlegen:** App Store Connect → Benutzer und Zugriff → Integrationen →
   App Store Connect API → „+“. Rolle **Admin** (nötig, damit Zertifikat und Profile automatisch
   entstehen). `.p8` herunterladen (geht nur einmal!), Key ID und Issuer ID notieren.
3. **Bundle-ID registrieren:** developer.apple.com → Certificates, IDs & Profiles → Identifiers →
   „+“ → App IDs → `com.jakober.blockmail` mit den Fähigkeiten **Push Notifications** und
   **App Groups** (Gruppe `group.com.jakober.blockmail` unter Identifiers → App Groups anlegen und
   zuweisen). Dasselbe für das Widget: `com.jakober.blockmail.widget` mit App Groups.
   (Xcode legt das beim ersten Signieren meist selbst an — manuell ist es verlässlicher.)
4. **App in App Store Connect anlegen:** Meine Apps → „+“ → Neue App → iOS, Name „BlockMail“,
   Bundle-ID `com.jakober.blockmail`, SKU z. B. `blockmail-ios`.
5. **GitHub-Secrets setzen** (Repo → Settings → Secrets and variables → Actions):
   - `APPLE_TEAM_ID` – Team-ID (developer.apple.com → Membership)
   - `ASC_KEY_ID`, `ASC_ISSUER_ID` – aus Schritt 2
   - `ASC_KEY_P8` – kompletter Inhalt der `.p8`-Datei
   - optional `GOOGLE_IOS_CLIENT_ID` (siehe unten)
6. **Hochladen:** GitHub → Actions → „iOS → TestFlight“ → *Run workflow*. Nach ca. 5–30 Minuten
   Verarbeitung erscheint der Build in App Store Connect → TestFlight.
7. **Tester einladen:** TestFlight → Interne Tester (bis 100 Teammitglieder, sofort) oder Externe
   Gruppe (bis 10 000, erster Build braucht eine kurze Beta-Prüfung durch Apple).
   Tester installieren die App **TestFlight** und nehmen die Einladung an.

Hinweis: TestFlight-Builds laufen 90 Tage; danach einfach neu hochladen.

## Google-Anmeldung (optional)

Gmail funktioniert auch mit **App-Passwort** (myaccount.google.com/apppasswords). Für
„Mit Google anmelden“ braucht iOS eine eigene OAuth-Client-ID:

1. Google Cloud Console → dasselbe Projekt wie die Android-App → APIs & Dienste → Anmeldedaten →
   Anmeldedaten erstellen → OAuth-Client-ID → Anwendungstyp **iOS**, Bundle-ID
   `com.jakober.blockmail`.
2. Die Client-ID (`…apps.googleusercontent.com`) als Secret `GOOGLE_IOS_CLIENT_ID` hinterlegen
   (oder in `ios/Config.xcconfig` eintragen).
3. Solange der OAuth-Zustimmungsbildschirm im Status „Test“ ist, funktionieren nur eingetragene
   Testnutzer, und Google-Anmeldungen laufen nach 7 Tagen ab — wie bei der Android-Testversion.

## Echter Push (optional)

Siehe `push-server/README.md`: kleiner Dienst (Docker), der je Konto eine IMAP-IDLE-Verbindung hält
und neue Mails per Apple Push meldet. Dafür im Apple-Developer-Konto einen **APNs-Schlüssel**
(Keys → „+“ → Apple Push Notifications service) anlegen. In der App: Einstellungen → Push →
Server-URL eintragen und anmelden. **Achtung:** Der Server bekommt die Zugangsdaten der Konten —
nur auf einem eigenen, vertrauenswürdigen Server betreiben.

## Noch ungetestet

Die App kompiliert, und die Unit-Tests der IMAP/MIME-Schicht laufen grün. Auf einem echten Gerät
und gegen echte Postfächer lief sie aber noch nicht. Deshalb bitte beim ersten TestFlight-Build
gezielt prüfen: Anmeldung (App-Passwort und Google), Posteingang laden, Senden mit Anhang, Push,
PDF-Editor und Widget.

## Aufbau

```
ios/
  project.yml           XcodeGen-Projekt (App + Widget)
  Config.xcconfig       Bundle-ID, Team, Google-Client-ID, Version
  BlockMail/
    App/                Einstieg, AppDelegate (Push, Aktionen, Quick Actions), Navigation
    Core/Net/           Eigener IMAP-/SMTP-Client (Network.framework), MIME, HTML→Text
    Core/Data/          Prefs (Schlüsselbund + App-Gruppe), MailRepository, Suchindex (SQLite FTS4),
                        Google-OAuth, Phishing-Wächter
    Core/AI/            Claude (eigener Schlüssel) + Apple Intelligence
    Core/Services/      Mail-Prüfung, IDLE, Benachrichtigungen, Hintergrundaufgaben, Push-Registrierung
    UI/                 Bildschirme (Posteingang, Detail, Verfassen, Einstellungen, PDF-Editor …)
    Resources/          Icons, Localizable.strings (aus den Android-Texten erzeugt)
  BlockMailWidget/      Homescreen-Widget
  tools/                Text-Übernahme aus Android + Prüfskript
```
