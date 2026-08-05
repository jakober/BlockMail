# Auftrag: Abo-Prüfung im BlockMail-KI-Proxy

Dieser Text kann unverändert an die KI gegeben werden, die den Proxy auf
`blockwerk-orange.de/blockmail` betreut.

---

## Was heute läuft

Der Proxy nimmt unter `POST /v1/messages` Anfragen im Anthropic-Format
entgegen, leitet sie an die Claude-API weiter und gibt die Antwort
unverändert zurück. Authentifiziert wird mit:

- `Authorization: Bearer <install_token>` — eine Zufalls-UUID, die die App
  bei der ersten Installation erzeugt
- `X-App-Package: com.jakober.klarmail`

Derzeit steht `ALLOW_ALL = true`, jede Anfrage mit gültigem Format wird
also durchgelassen (Testphase).

## Was sich ändert

Die App verkauft ab sofort das Abo **BlockMail Pro** über Google Play
(Produkt-ID `blockmail_pro`, Basis-Tarif monatlich, Angebot mit 3 Tagen
kostenloser Testphase). Bei jeder KI-Anfrage schickt die App zusätzlich:

- `X-Purchase-Token: <play_kauf_token>` — nur vorhanden, wenn ein Abo
  gekauft wurde. Fehlt die Kopfzeile, hat der Nutzer kein Abo.

## Aufgabe

Baue eine serverseitige Abo-Prüfung ein:

1. **Kauf-Token prüfen** über die Google Play Developer API:
   `GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/com.jakober.klarmail/purchases/subscriptionsv2/tokens/{purchaseToken}`
   Authentifizierung über ein Google-Cloud-Dienstkonto (Details unten).
2. **Als gültig gilt**, wenn `subscriptionState` einen der Werte
   `SUBSCRIPTION_STATE_ACTIVE` oder `SUBSCRIPTION_STATE_IN_GRACE_PERIOD`
   hat. Alles andere (gekündigt, abgelaufen, pausiert, zurückerstattet)
   gilt als kein Abo.
   Die Testphase zählt als aktiv — Google meldet sie als ACTIVE.
3. **Ergebnis zwischenspeichern**, damit nicht jede KI-Anfrage eine
   Google-Abfrage auslöst: Kauf-Token → Gültigkeit, mindestens 6 Stunden,
   höchstens 24 Stunden. Bei negativem Ergebnis kürzer (etwa 15 Minuten),
   damit ein frischer Kauf schnell greift.
4. **Ablehnen ohne gültiges Abo** mit HTTP 403 und dem Rumpf
   `{"error":"subscription_required"}` — genau diesen Text erwartet die
   App und zeigt dann eine passende Meldung an.
5. **Tageslimits weiterhin je Nutzer** führen, aber künftig am
   Kauf-Token statt am Install-Token festmachen (der Install-Token
   bleibt als Kennung für Protokolle erhalten). Vorschlag für die Limits:
   **40 KI-Anfragen und 500.000 Eingabe-Tokens pro Tag** — großzügig für
   normale Nutzung, deckelt aber den schlimmsten Fall.
6. **Schalter beibehalten:** `ALLOW_ALL` bleibt als Umgebungsvariable
   bestehen. Solange sie auf `true` steht, wird die Prüfung übersprungen
   (für die laufende Testphase). Der Umstieg erfolgt später allein durch
   Umlegen dieser Variable.

## Was du dafür brauchst (vom Betreiber bereitzustellen)

1. In der **Google Play Console**: Einstellungen → API-Zugriff → ein
   Google-Cloud-Projekt verknüpfen.
2. In der **Google Cloud Console**: ein Dienstkonto anlegen, einen
   JSON-Schlüssel erzeugen.
3. In der **Play Console** dem Dienstkonto die Berechtigung
   „Finanzdaten ansehen, Bestellungen und Abos verwalten" geben.
4. Den JSON-Schlüssel auf dem Server ablegen (nicht im Web-Verzeichnis,
   Dateirechte 600) und den Pfad als Umgebungsvariable setzen.

**Wichtig:** Nach dem Verknüpfen dauert es bei Google erfahrungsgemäß
bis zu 48 Stunden, bis das Dienstkonto Käufe abfragen darf. Vorher
kommen 401/403-Fehler zurück, die nichts mit dem Code zu tun haben.

## Anforderungen an die Umsetzung

- Kein Speichern von Mail-Inhalten — das gilt unverändert.
- Protokolliert werden dürfen: Zeitpunkt, Install-Token (gekürzt),
  Abo-Status, verbrauchte Tokens. Keine Inhalte.
- Fällt die Google-Abfrage aus (Netzwerkfehler, Google-Störung), soll
  ein zuvor als gültig zwischengespeichertes Abo **weiter gelten**
  (Kulanz bis 24 Stunden), statt zahlende Nutzer auszusperren.
- Der Endpunkt `GET /health` bleibt unverändert erreichbar.
- Ergänze `GET /v1/quota` um die Felder `subscription_active` (bool) und
  `subscription_expiry` (ISO-Zeitstempel oder null).

## Testen

Google stellt Testkäufe bereit: In der Play Console unter Einstellungen →
Lizenztests können E-Mail-Adressen eingetragen werden, die kostenlos
kaufen. Deren Kauf-Tokens verhalten sich gegenüber der API wie echte,
laufen aber im Zeitraffer ab (ein Monat entspricht wenigen Minuten) —
damit lässt sich auch der Ablauf eines Abos prüfen.
