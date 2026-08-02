> **VERALTET (nicht mehr verwenden).** Beschreibt den früheren Stand mit
> KI auf dem Gerät. Aktueller Store-Eintrag: `docs/store-eintrag-aso.md`.

# BlockMail in den Play Store bringen (Option B)

Gestaffelter Start: zuerst mit Einrichtungsassistent (App-Passwörter,
funktioniert sofort für Web.de/GMX/T-Online mit normalem Passwort),
später — wenn die App Nutzer findet — Google-Überprüfung (CASA) nachrüsten,
damit „Mit Google anmelden“ für alle freigeschaltet wird.

## Einmalige Schritte (macht der Entwickler, ~1 Stunde)

1. **Play-Console-Konto** anlegen: https://play.google.com/console
   (einmalig 25 US-Dollar).
2. **GitHub Pages einschalten**, damit die Datenschutzerklärung eine
   öffentliche Adresse hat: Repo → Settings → Pages → Branch `main`,
   Ordner `/docs` → Save. Die Adresse ist dann:
   `https://jakober.github.io/BlockMail/datenschutz.html`
3. **Upload-Schlüssel als Secrets hinterlegen** (Repo → Settings →
   Secrets and variables → Actions → "New repository secret"):
   - `UPLOAD_KEYSTORE_BASE64`: kompletter Inhalt der Datei
     `blockmail-upload.keystore.base64.txt` (kam per Chat)
   - `UPLOAD_KEYSTORE_PASSWORD`: das zugehörige Passwort (kam per Chat)

   Wichtig: Keystore-Datei und Passwort privat aufbewahren (z. B.
   Passwort-Manager). Sie kommen NICHT ins Repo.

## Bundle bauen und hochladen

1. GitHub → Actions → **„Play-Store-Bundle bauen“** → „Run workflow“.
2. Nach dem Lauf liegt das signierte AAB im Release `store-v<Version>`
   (und als Workflow-Artefakt).
3. In der Play Console eine neue App anlegen:
   - Name: BlockMail, Sprache Deutsch, App (kein Spiel), kostenlos
   - **Play App Signing aktivieren** (Standard): Google verwaltet den
     App-Signaturschlüssel, unser Schlüssel ist nur der Upload-Schlüssel
   - AAB unter „Produktion“ (oder erst „Interner Test“) hochladen
4. Store-Eintrag ausfüllen: Beschreibung, Screenshots (Smartphone),
   App-Symbol 512×512, Feature-Grafik 1024×500.
5. **Datenschutz**: URL aus Schritt 2 eintragen.
   Formular „Datensicherheit“: keine Datenerhebung durch den Entwickler;
   Daten (Zugangsdaten, Mails) bleiben auf dem Gerät bzw. gehen direkt an
   den Mail-Anbieter; optionale Claude-KI nur mit eigenem API-Schlüssel.
6. Inhaltsfreigaben (Altersfreigabe-Fragebogen, Zielgruppe „Erwachsene“)
   ausfüllen und zur Prüfung einreichen.

## Wichtig für den ersten Store-Build

- Die OAuth-Client-ID in `app/build.gradle.kts` gehört zum Debug-Zertifikat.
  Für den Store-Build mit Play App Signing muss in der Google Cloud Console
  ein zusätzlicher Android-OAuth-Client mit dem **SHA-1 des
  App-Signaturschlüssels von Google** (Play Console → Einrichtung →
  App-Signatur) angelegt werden — sonst schlägt „Mit Google anmelden“ in der
  Store-Version fehl. Solange die Google-Anmeldung nur für Testnutzer
  freigeschaltet ist, betrifft das nur dich.

## Später: Google-Anmeldung für alle (CASA)

1. In der Google Cloud Console den OAuth-Zustimmungsbildschirm auf
   „In Produktion“ stellen und die App zur Überprüfung einreichen.
2. Google verlangt für den Gmail-Vollzugriff (IMAP/SMTP-Scope
   `https://mail.google.com/`) ein jährliches CASA-Tier-2-Assessment
   (zugelassene Prüflabore, ab ca. 500–600 € pro Jahr).
3. Nach bestandener Prüfung funktioniert „Mit Google anmelden“ für alle
   Nutzer — per App-Update ohne weitere Änderungen.
