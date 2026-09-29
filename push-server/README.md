# BlockMail-Push-Server

Kleiner Node.js-Dienst für **echten Push** in der iOS-App: Er hält je Mailkonto eine
IMAP-IDLE-Verbindung und schickt bei jeder neuen Mail sofort eine Apple-Push-Meldung (APNs)
an das iPhone – auch wenn die App geschlossen ist. Ohne diesen Server prüft iOS nur
gelegentlich im Hintergrund (etwa alle 15–60 Minuten).

```
iPhone (BlockMail) ──HTTPS──▶ Caddy/Traefik ──▶ push-server ──IMAP IDLE──▶ Mailserver
       ▲                                            │
       └──────────────── Apple Push (APNs) ◀────────┘
```

> **Sicherheitshinweis:** Der Server bekommt die **Zugangsdaten deiner Mailkonten**
> (App-Passwörter bzw. Google-Refresh-Tokens), weil er sich selbst beim Mailserver anmelden muss.
> Betreibe ihn nur auf einem Rechner, dem du vertraust, ausschließlich über HTTPS, mit gesetztem
> `PUSH_SHARED_SECRET`, und halte `DATA_KEY` geheim. Die Daten liegen AES-256-GCM-verschlüsselt
> auf der Platte – wer aber Zugriff auf den laufenden Server samt `DATA_KEY` hat, kann sie lesen.
> Nutze für Nicht-Google-Konten nach Möglichkeit **App-Passwörter**, die du jederzeit widerrufen kannst.

## Voraussetzungen

- Ein Server (VPS, Raspberry Pi, NAS …) mit Docker + Docker Compose **oder** Node.js ≥ 20
- Eine (Sub-)Domain, die auf den Server zeigt (z. B. `push.example.com`), Ports 80/443 offen
- Ein Apple-Developer-Konto (für den APNs-Schlüssel) – dasselbe Team, mit dem die App signiert wird

## Einrichtung Schritt für Schritt

### 1. APNs-Schlüssel im Apple-Developer-Konto erzeugen

1. <https://developer.apple.com/account> öffnen → **Certificates, Identifiers & Profiles** → **Keys**.
2. **+** (neuen Schlüssel anlegen), Namen vergeben (z. B. „BlockMail Push“),
   Haken bei **Apple Push Notifications service (APNs)** setzen.
   Falls gefragt: Umgebung **Sandbox & Production** wählen.
3. **Continue** → **Register** → **Download**. Du erhältst `AuthKey_XXXXXXXXXX.p8`.
   **Diese Datei lässt sich nur einmal herunterladen** – sicher aufbewahren.
4. Notieren:
   - **Key ID** (10 Zeichen, steht beim Schlüssel und im Dateinamen) → `APNS_KEY_ID`
   - **Team ID** (oben rechts im Konto bzw. unter „Membership“) → `APNS_TEAM_ID`
5. Prüfen, dass die App-ID (Bundle-ID, z. B. `com.jakober.blockmail`) unter **Identifiers**
   die Fähigkeit **Push Notifications** hat (sonst aktivieren und Profile neu erzeugen).

### 2. Server vorbereiten

```sh
git clone <dein-repo> && cd <dein-repo>/push-server
mkdir -p secrets
cp /pfad/zu/AuthKey_XXXXXXXXXX.p8 secrets/AuthKey.p8
chmod 600 secrets/AuthKey.p8
cp .env.example .env
```

`.env` ausfüllen:

```sh
DATA_KEY=$(openssl rand -hex 32)          # Wert eintragen, gut aufheben!
APNS_KEY_ID=XXXXXXXXXX
APNS_TEAM_ID=YYYYYYYYYY
PUSH_SHARED_SECRET=$(openssl rand -base64 24)   # Server-Passwort, kommt auch in die App
PUSH_DOMAIN=push.example.com
APNS_ALLOWED_TOPICS=com.jakober.blockmail       # empfohlen: nur deine App zulassen
```

(`$(…)` einmal im Terminal ausführen und das Ergebnis eintragen – `.env` wertet keine Befehle aus.)

### 3. Mit HTTPS starten

**Variante A – Docker Compose mit Caddy (empfohlen).** Caddy besorgt das TLS-Zertifikat
(Let's Encrypt) automatisch; der Push-Dienst selbst ist von außen nicht erreichbar.

```sh
docker compose up -d --build
docker compose logs -f push
curl https://push.example.com/health
# {"ok":true,"uptime":12,"registrations":0,"accounts":0,"connected":0,"failing":0}
```

**Variante B – eigener Reverse-Proxy (Traefik, nginx, vorhandenes Caddy).** Nur den Dienst `push`
starten und den Proxy auf Port 8787 zeigen lassen. Beispiel Traefik-Labels:

```yaml
  push:
    build: .
    env_file: .env
    environment: { DATA_DIR: /data, APNS_KEY_FILE: /run/secrets/apns.p8 }
    volumes: [ "push-data:/data", "./secrets/AuthKey.p8:/run/secrets/apns.p8:ro" ]
    labels:
      - traefik.enable=true
      - traefik.http.routers.blockmail-push.rule=Host(`push.example.com`)
      - traefik.http.routers.blockmail-push.entrypoints=websecure
      - traefik.http.routers.blockmail-push.tls.certresolver=letsencrypt
      - traefik.http.services.blockmail-push.loadbalancer.server.port=8787
```

Beispiel für ein vorhandenes Caddy: `push.example.com { reverse_proxy 127.0.0.1:8787 }`.

**Variante C – ohne Docker.**

```sh
npm ci --omit=dev
set -a; . ./.env; set +a
APNS_KEY_FILE=./secrets/AuthKey.p8 npm start
```

(Dauerhaft z. B. als systemd-Dienst; der Dienst lauscht nur per HTTP und gehört hinter einen HTTPS-Proxy.)

### 4. URL und Passwort in der App eintragen

In BlockMail: **Einstellungen → Benachrichtigungen**, Abschnitt **„Echtzeit-Push“**:

1. **Push-Server (URL):** `https://push.example.com` (ohne `/v1/…`).
2. **Server-Passwort:** den Wert von `PUSH_SHARED_SECRET`.
3. Mitteilungen erlauben und **„Jetzt anmelden“** tippen. Die Zeile
   „Beim Push-Server angemeldet (seit …)“ bestätigt die Registrierung.

Die App meldet sich danach beim Start (neues APNs-Token) sowie nach Änderungen an Konten, Stumm-/Blockiert-/VIP-Listen
selbst neu an. Im Sparmodus meldet sie sich ab.

**Sandbox oder Produktion?** Debug-Builds (aus Xcode) melden `sandbox: true` und werden über
`api.sandbox.push.apple.com` beliefert, TestFlight-/App-Store-Builds über `api.push.apple.com`.
Der Server entscheidet das pro Gerät automatisch.

## Verhalten

- Je Konto (E-Mail + IMAP-Server + Login) läuft **eine** IMAP-Verbindung, auch wenn mehrere Geräte
  dasselbe Konto registrieren. Das Postfach wird nur lesend geöffnet (`EXAMINE`) – Flags bleiben unverändert.
- **IDLE** wird alle 25 Minuten erneuert (`IDLE_RENEW_MINUTES`), tote Verbindungen per NOOP erkannt.
- **Reconnect** mit exponentiellem Backoff (5 s … 5 min, mit Zufallsanteil). Bei falschem Passwort
  wird nur alle 30 min erneut versucht (Schutz vor Kontosperre), bei widerrufenem Google-Token stündlich.
- **Google-Konten:** Anmeldung per XOAUTH2; das Access-Token wird mit dem Refresh-Token und der
  iOS-Client-ID der App (ohne Client-Secret) bei `https://oauth2.googleapis.com/token` erneuert.
- **Neue Mail** = UID größer als die zuletzt gesehene. Beim allerersten Verbinden wird nur der Stand
  gemerkt (keine Meldung). Nach einer Unterbrechung werden höchstens 5 verpasste Mails der letzten
  24 Stunden nachgemeldet (`MAX_BACKLOG_NOTIFICATIONS`). Ändert sich `UIDVALIDITY`, beginnt die Zählung neu.
- **Stumm / Blockiert / Nur VIP** aus der App werden beachtet (keine Meldung).
- Meldet APNs `410` oder `BadDeviceToken`/`Unregistered`, wird die Registrierung gelöscht.
- Registrierungen, die 90 Tage nicht erneuert wurden, werden entfernt (`REGISTRATION_TTL_DAYS`).
- IMAP-Server im internen Netz (10.x, 192.168.x, localhost …) werden abgelehnt
  (`ALLOW_PRIVATE_IMAP_HOSTS=1` erlaubt sie, z. B. für einen eigenen Mailserver im LAN).

## Protokoll

Alle Anfragen: `Content-Type: application/json`, Antwort JSON; Fehler als `{"error": "…"}`.

| Header | Pflicht | Inhalt |
|---|---|---|
| `Authorization` | ja | `Bearer <installToken>` (zufällige UUID der App-Installation) |
| `X-Push-Secret` | wenn `PUSH_SHARED_SECRET` gesetzt | Server-Passwort; falsch/fehlend → `401` |

### `POST /v1/register`

```json
{
  "deviceToken": "a1b2…(hex)",
  "bundleId": "com.jakober.blockmail",
  "sandbox": false,
  "language": "de",
  "accounts": [
    { "email": "ich@gmail.com", "authMethod": "oauth", "imapHost": "imap.gmail.com", "imapPort": 993,
      "loginUser": "ich@gmail.com", "refreshToken": "1//…", "googleClientId": "123-abc.apps.googleusercontent.com" },
    { "email": "ich@web.de", "authMethod": "password", "imapHost": "imap.web.de", "imapPort": 993,
      "loginUser": "ich@web.de", "password": "app-passwort" }
  ],
  "mutedSenders": ["newsletter@example.com"],
  "blockedSenders": [],
  "vipSenders": ["chefin@example.com"],
  "vipOnly": false,
  "showAccount": true
}
```

Gespeichert wird je `installToken` + `deviceToken` (der installToken selbst nur als Hash).
Eine erneute Registrierung ersetzt die vorige vollständig; eine andere Registrierung mit demselben
Geräte-Token (z. B. nach Neuinstallation) wird entfernt. Antwort: `{"ok": true, "accounts": 2}`.

### `POST /v1/unregister`

`{"deviceToken": "…"}` → löscht die Registrierung dieser Installation für dieses Gerät
(ohne `deviceToken`: alle Geräte der Installation). Antwort: `{"ok": true, "removed": 1}`.

### `GET /health`

`{"ok": true, "uptime": 123, "registrations": 1, "accounts": 2, "connected": 2, "failing": 0}` –
ohne personenbezogene Daten, für Monitoring/Healthchecks.

### APNs-Meldung

```json
{
  "aps": {
    "alert": { "title": "Anna Berger", "subtitle": "ich@web.de", "body": "Projekt-Update" },
    "sound": "default",
    "category": "NEW_MAIL",
    "thread-id": "ich@web.de",
    "mutable-content": 1
  },
  "uid": 4711,
  "account": "ich@web.de",
  "address": "anna@example.com",
  "subject": "Projekt-Update",
  "from": "Anna Berger"
}
```

- `title` = Absendername, sonst Adresse; `subtitle` (Konto) nur bei `showAccount`; `body` = Betreff.
- `account` = Konto-Adresse in Kleinbuchstaben, **aber `""` für das erste Konto der Registrierung**
  (entspricht dem aktiven Konto der App, vgl. `MailChecker.accountTag`).
- Die Kategorie `NEW_MAIL` liefert die Aktions-Knöpfe (Antworten, Gelesen, Archivieren, Löschen) der App.

## Betrieb

- **Daten:** `/data/store.enc` (Docker-Volume `push-data`). Enthält Registrierungen inkl. Zugangsdaten
  und UID-Stände, verschlüsselt mit `DATA_KEY`. Geht der Schlüssel verloren: Datei löschen und in der
  App „Jetzt anmelden“ tippen.
- **Logs:** JSON-Zeilen auf stdout/stderr; Adressen werden gekürzt, Passwörter/Tokens nie geloggt.
  `LOG_LEVEL=debug` zeigt jede zugestellte Meldung.
- **Aktualisieren:** `git pull && docker compose up -d --build`.
- **Begrenzungen:** 30 API-Anfragen pro Minute und IP, max. 10 Konten je Gerät, max. 500 Registrierungen
  (`MAX_REGISTRATIONS`).

## Fehlersuche

| Symptom | Ursache / Abhilfe |
|---|---|
| App meldet „Server-Passwort fehlt oder ist falsch“ | `PUSH_SHARED_SECRET` und Feld „Server-Passwort“ in der App vergleichen |
| Log `APNs lehnt ab … InvalidProviderToken` | Key ID / Team ID falsch oder Schlüssel ohne APNs-Recht |
| Log `… DeviceTokenNotForTopic` | Bundle-ID stimmt nicht (`APNS_TOPIC` prüfen) |
| Log `… BadDeviceToken` | Sandbox/Produktion vertauscht – Debug-Build vs. TestFlight |
| Log `IMAP-Verbindung fehlgeschlagen … Authentication failed` | Passwort/App-Passwort prüfen; in der App neu anmelden |
| Log `Google-Token-Erneuerung fehlgeschlagen (invalid_grant)` | Google-Zugang widerrufen → in der App Google neu verbinden |
