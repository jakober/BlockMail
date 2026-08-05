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

Die App verkauft ab sofort das Abo **BlockMail Pro** über Google Play —
EIN Produkt (`blockmail_pro`) mit **zwei monatlichen Basis-Tarifen**, beide
mit 3 Tagen kostenloser Testphase:

| Basis-Tarif | Preis (brutto) | Enthaltene KI-Anfragen |
|-------------|----------------|------------------------|
| `pro-150`   | 4,90 €/Monat   | **150 pro Monat**      |
| `pro-300`   | 9,00 €/Monat   | **300 pro Monat**      |

Bei jeder KI-Anfrage schickt die App zusätzlich:

- `X-Purchase-Token: <play_kauf_token>` — nur vorhanden, wenn ein Abo
  gekauft wurde. Fehlt die Kopfzeile, hat der Nutzer kein Abo.

**Die App zählt bereits selbst mit** und sperrt ihre KI-Funktionen, sobald
das Monatskontingent leer ist. Das ist die erste Schutzschicht und wirkt
sofort. Der Server wird die zweite: Ein Zähler im Gerät ließe sich durch
Löschen der App-Daten zurücksetzen, und wer den Endpunkt aus der APK
ausliest, kann ihn ganz ohne App aufrufen. Nur der Server kann das
abfangen — deshalb muss dort trotzdem gezählt werden.

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
   **Den Tarif** liest du aus derselben Antwort:
   `lineItems[0].offerDetails.basePlanId` ist entweder `pro-150` oder
   `pro-300`. Daraus ergibt sich das Monatskontingent (150 bzw. 300).
3. **Ergebnis zwischenspeichern**, damit nicht jede KI-Anfrage eine
   Google-Abfrage auslöst: Kauf-Token → Gültigkeit, mindestens 6 Stunden,
   höchstens 24 Stunden. Bei negativem Ergebnis kürzer (etwa 15 Minuten),
   damit ein frischer Kauf schnell greift.
4. **Ablehnen ohne gültiges Abo** mit HTTP 403 und dem Rumpf
   `{"error":"subscription_required"}` — genau diesen Text erwartet die
   App und zeigt dann eine passende Meldung an.
5. **Monatskontingent statt Tageslimit** führen — das ist die zentrale
   Änderung. Gezählt wird je Kauf-Token (der Install-Token bleibt nur als
   Kennung für Protokolle):
   - Zählerstand: `used` = Anzahl erfolgreich beantworteter Anfragen im
     laufenden Abrechnungszeitraum. Eine Anfrage = ein `POST /v1/messages`,
     das mit HTTP 200 beantwortet wurde. Fehlgeschlagene Anfragen (4xx/5xx)
     zählen NICHT.
   - Grenze: 150 bei `pro-150`, 300 bei `pro-300`.
   - Zurücksetzen: zum Beginn des nächsten Abrechnungszeitraums. Der
     Zeitpunkt steht in der Google-Antwort als `lineItems[0].expiryTime` —
     das ist zugleich das Verlängerungsdatum. Fehlt er, hilfsweise auf den
     Monatsersten 00:00 UTC setzen. Nicht genutzte Anfragen verfallen.
   - Tarifwechsel mitten im Monat: Grenze sofort auf den neuen Wert setzen,
     `used` unverändert stehen lassen (Wechsel nach oben gibt also sofort
     mehr Luft, Wechsel nach unten kann `remaining` auf 0 drücken).
   - Ist das Kontingent aufgebraucht: HTTP 429 mit dem Rumpf
     `{"error":"quota_exceeded"}`. Die App zeigt dann „Monatliches
     KI-Kontingent aufgebraucht“ an.
   - Zusätzlich als Notbremse gegen Missbrauch: höchstens **60 Anfragen
     pro Stunde** je Kauf-Token (HTTP 429, gleicher Rumpf).
6. **Neuer Endpunkt `GET /v1/quota`** (die App fragt ihn beim Öffnen der
   Einstellungen und nach jedem Kauf ab, Auth-Kopfzeilen identisch zu
   `/v1/messages`). Antwort als JSON:

   ```json
   {
     "plan": "pro-150",
     "limit": 150,
     "used": 37,
     "remaining": 113,
     "resets_at": "2026-09-01T00:00:00Z",
     "subscription_active": true,
     "subscription_expiry": "2026-09-01T00:00:00Z"
   }
   ```

   - `plan`: `pro-150` oder `pro-300` (leer, wenn kein Abo)
   - `limit`/`used`/`remaining`: ganze Zahlen, `remaining = limit - used`
     (nie negativ)
   - `resets_at`: ISO-8601 mit Zeitzone (UTC-`Z` ist in Ordnung)
   - Ohne gültiges Abo: HTTP 403 `{"error":"subscription_required"}`.
   - Die App verträgt es, wenn der Endpunkt fehlt oder einen Fehler
     liefert — dann blendet sie die Kontingent-Anzeige einfach aus. Sie
     darf aber NIE mit einem HTML-Fehlerseiten-Rumpf beantwortet werden.
7. **Schalter beibehalten:** `ALLOW_ALL` bleibt als Umgebungsvariable
   bestehen. Solange sie auf `true` steht, wird die Prüfung übersprungen
   (für die laufende Testphase). Der Umstieg erfolgt später allein durch
   Umlegen dieser Variable. Steht `ALLOW_ALL = true`, soll `/v1/quota`
   ein fiktives Kontingent nach dem Tarif `pro-150` melden, damit die
   Anzeige in der App auch in der Testphase geprüft werden kann.

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
- Der Zählerstand muss einen Neustart des Dienstes überleben (Datei oder
  Datenbank) — ein Zähler nur im Arbeitsspeicher wäre nach jedem Deploy
  wieder auf null.

## Testen

Google stellt Testkäufe bereit: In der Play Console unter Einstellungen →
Lizenztests können E-Mail-Adressen eingetragen werden, die kostenlos
kaufen. Deren Kauf-Tokens verhalten sich gegenüber der API wie echte,
laufen aber im Zeitraffer ab (ein Monat entspricht wenigen Minuten) —
damit lässt sich auch der Ablauf eines Abos prüfen.
