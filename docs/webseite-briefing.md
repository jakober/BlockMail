# Briefing: Werbe-Webseite für BlockMail

Dieses Dokument enthält alle Informationen, die eine KI oder Agentur
braucht, um eine Landingpage für die Android-App **BlockMail** zu bauen.
Stand: App-Version 3.79 (August 2026).

**Wichtig vorweg:** Abschnitt 10 („Was NICHT behauptet werden darf")
unbedingt lesen — dort stehen Aussagen, die früher galten und heute
falsch wären.

---

## 1. Das Produkt in einem Satz

BlockMail ist eine schnelle, aufgeräumte E-Mail-App für Android mit
eingebautem KI-Assistenten, die jedes IMAP-Postfach anbindet (Gmail,
GMX, WEB.DE, Outlook, T-Online, iCloud und andere) und Ordnung in den
Posteingang bringt, statt ihn nur anzuzeigen.

**Kernversprechen:** „Frag dein Postfach." — statt selbst zu suchen,
stellt man der KI eine Frage in normaler Sprache und bekommt eine
Antwort aus den eigenen Mails.

---

## 2. Eckdaten

| Punkt | Wert |
|---|---|
| Name | BlockMail |
| Plattform | Android (Smartphone und Tablet) |
| Mindestversion | Android 8.0 |
| Play-Store-Link | https://play.google.com/store/apps/details?id=com.jakober.klarmail |
| Paketname | com.jakober.klarmail |
| Sprachen der App | Deutsch, Englisch (automatisch nach Systemsprache) |
| Preis der App | kostenlos |
| Optionales Abo | BlockMail Pro: 3 Tage kostenlos testen, danach 4,90 € pro Monat |
| Anbieter | Mathis Jakober / Blockwerk Orange |
| Kontakt | mat.jakober@gmail.com |
| Datenschutzerklärung | https://jakober.github.io/BlockMail/datenschutz.html |
| Status | derzeit geschlossener Test, Produktionsstart in Vorbereitung |

**Hinweis zum Status:** Solange die App nur im geschlossenen Test ist,
führt der Play-Store-Link für Außenstehende ins Leere. Die Webseite
sollte deshalb entweder einen „Bald verfügbar"-Zustand mit
E-Mail-Benachrichtigung vorsehen oder der Play-Knopf wird erst zum
Launch scharf geschaltet. Bitte beide Varianten vorbereiten.

---

## 3. Zielgruppe

**Primär:** Privatnutzer im deutschsprachigen Raum zwischen 25 und 60,
die täglich mit E-Mails arbeiten, mehrere Postfächer haben und deren
Posteingang unübersichtlich geworden ist. Technisch normal versiert —
sie wollen keine Konfiguration, sondern Ergebnisse.

**Sekundär:** Selbstständige und kleine Betriebe, die geschäftliche und
private Post auf einem Gerät verwalten und dabei auf Datenschutz achten.

**Was sie nervt (Aufhänger für die Texte):**
- Der Posteingang quillt über, Wichtiges geht unter
- Antworten werden vergessen
- Suchen dauert ewig, besonders bei älteren Mails
- Angst vor Phishing und Betrugsmails
- Mehrere Konten bedeuten mehrere Apps oder ständiges Umschalten

---

## 4. Alleinstellungsmerkmale (in dieser Reihenfolge bewerben)

1. **Frag dein Postfach (KI-Suche).** Freie Fragen in normaler Sprache
   statt Stichwortsuche. Beispiele, die man zeigen sollte:
   „Wie viel habe ich diesen Monat bei Amazon ausgegeben?",
   „Wann hat Brigitte mir das letzte Mal geschrieben?",
   „Welche Rechnungen kamen letzten Monat?"
   Die KI durchsucht das ganze Postfach, liest bei Bedarf die passenden
   Mails im Volltext und antwortet mit Quellenangabe (die Treffer-Mails
   erscheinen unter der Antwort).
2. **Echtzeit-Push.** Neue Mails kommen in Sekunden an, nicht erst beim
   nächsten Abrufintervall. Technisch über eine dauerhafte
   IMAP-Verbindung, die auch Neustarts und Netzwechsel übersteht.
3. **Phishing-Wächter.** Prüft verdächtige Absender, Links und
   Formulierungen und warnt mit rotem Hinweis, bevor man klickt. Läuft
   vollständig auf dem Gerät — hierfür verlassen keine Daten das Handy.
4. **Sieht aus, wie man will.** Liste oder Kacheln, hell oder dunkel,
   sechs Farbwelten plus frei wählbare Farbe, schlichtes Design ohne
   Ablenkung, einstellbare Schriftgröße.

---

## 5. Vollständige Funktionsliste

### Kostenlos enthalten

**Postfächer**
- Jedes IMAP-Postfach; fertige Voreinstellungen für Gmail, WEB.DE, GMX,
  Outlook/Hotmail, Yahoo, T-Online, iCloud
- Einrichtungsassistent: Anbieter wählen, Adresse und Passwort eingeben,
  fertig — Server und Ports werden automatisch gesetzt
- Anmeldung mit Google-Konto
- Mehrere Konten gleichzeitig, Sammel-Posteingang, eigene Farbe je Konto

**Posteingang**
- Drei Ansichten: Liste, Kacheln (2 Spalten), kompakte Kacheln (3 Spalten)
- Konversations-Ansicht bündelt zusammengehörige Mails
- Wischgesten frei belegbar: gelesen, erinnern, archivieren, löschen
- Später erinnern (Snooze) mit Auswahlzeiten
- Zweispaltige Ansicht auf Tablets und im Querformat

**Suchen und Finden**
- Sofortfilter beim Tippen
- Lokaler Volltext-Index: findet auch jahrealte Mails in Sekunden,
  Zeitraum wählbar (1, 2, 5 Jahre oder alles)
- Server-Volltextsuche direkt im Postfach

**Schreiben**
- Formatierter Text, Anhänge, Signaturen
- Entwürfe werden automatisch gespeichert
- Textbausteine und Vorlagen
- Geplantes Senden
- Adressvorschläge aus den eigenen Kontakten

**Benachrichtigungen**
- Echtzeit-Push mit Schnellantwort direkt aus der Benachrichtigung
- Aktionen in der Benachrichtigung: gelesen, archivieren, löschen
- VIP-Absender: auf Wunsch nur noch von diesen benachrichtigt werden
- Stumme und blockierte Absender

**Sicherheit und Ordnung**
- Phishing-Wächter (auf dem Gerät)
- Antwort-Radar: erinnert an Mails mit offenen Fragen und daran, wenn
  man selbst zu lange auf Antwort wartet
- Blockierte Absender werden sofort gelöscht

**Extras**
- Anhang-Galerie über alle Mails
- Kontakte mit Absenderbildern
- Statistik: Aufkommen je Wochentag, Top-Absender, Verlauf
- Startbildschirm-Widget
- Backup und Umzug: Einstellungen exportieren und importieren
- Interaktive Einführung beim ersten Start

### BlockMail Pro (3 Tage kostenlos, danach 4,90 € im Monat)

Ein Abo schaltet alle KI-Funktionen zusammen frei:
- **KI-Suche** „Frag dein Postfach" inklusive Volltext-Lesen
- **Mail zusammenfassen** (lange Mails auf den Punkt)
- **Tages-Überblick** und „Ungelesene zusammenfassen"
- **Antworten entwerfen** mit KI
- **Mails verfassen** mit KI
- **Rechtschreibprüfung** beim Schreiben

Nicht im Abo, also dauerhaft kostenlos: Phishing-Wächter, Antwort-Radar
und sämtliche normalen Mail-Funktionen.

---

## 6. Datenschutz — so und nicht anders formulieren

Dieser Abschnitt ist heikel, weil falsche Aussagen rechtlich und im
Play Store Ärger machen. Bitte wörtlich in diesem Sinn:

- **Zugangsdaten** werden verschlüsselt auf dem Gerät gespeichert und
  nirgendwo sonst.
- **Mails** werden direkt zwischen Gerät und dem Postfach-Anbieter
  ausgetauscht (IMAP/SMTP über verschlüsselte Verbindungen).
- **Der Phishing-Wächter** arbeitet vollständig auf dem Gerät.
- **Die KI-Funktionen** verarbeiten Mail-Inhalte über den BlockMail-
  Server (blockwerk-orange.de) und von dort über einen KI-Anbieter.
  Inhalte werden dabei **nicht gespeichert**. Die KI wird nur aktiv,
  wenn der Nutzer sie ausdrücklich startet.
- **Der lokale Suchindex** liegt ausschließlich auf dem Gerät und kann
  in den Einstellungen jederzeit gelöscht werden.
- **Keine Werbung, kein Verkauf von Daten, kein Tracking-Netzwerk.**

Als Formulierung für die Webseite geeignet:
> „Deine Zugangsdaten bleiben verschlüsselt auf deinem Gerät, der
> Phishing-Schutz arbeitet lokal. KI-Anfragen laufen über unseren
> eigenen Server — Inhalte werden dort nicht gespeichert. Keine
> Werbung, kein Datenverkauf."

---

## 7. Aufbau der Webseite (Vorschlag)

1. **Kopfbereich (Hero)**
   Logo, Überschrift, ein Satz Untertitel, Play-Store-Knopf,
   Handy-Screenshot (am besten der Posteingang in Kacheln).
   Überschrift-Vorschlag: „Dein Postfach beantwortet endlich Fragen."
   Untertitel: „BlockMail ist die schnelle E-Mail-App mit KI — für
   Gmail, GMX, WEB.DE, Outlook und jedes IMAP-Postfach."
2. **Das Problem** (3 kurze Punkte, siehe Abschnitt 3)
3. **Frag dein Postfach** — größter Abschnitt, mit Beispielfragen als
   Chat-artige Darstellung und Screenshot der KI-Antwort
4. **Schnell und sicher** — Echtzeit-Push und Phishing-Wächter
5. **Sieht aus, wie du willst** — Farbwelten, hell/dunkel, drei
   Ansichten, Screenshot-Galerie
6. **Alle Funktionen** — die Liste aus Abschnitt 5, aufklappbar oder als
   Raster mit Symbolen
7. **Preise** — zwei Karten: „BlockMail kostenlos" (alle
   Mail-Funktionen) und „BlockMail Pro — 4,90 €/Monat, 3 Tage gratis
   testen" (alle KI-Funktionen). Deutlich sagen: Die App selbst ist und
   bleibt kostenlos.
8. **Datenschutz** — Abschnitt 6, ruhig mit Schloss-Symbolik
9. **Häufige Fragen** — siehe Abschnitt 9
10. **Abschluss-Aufruf** — Play-Store-Knopf noch einmal groß
11. **Fußzeile** — Impressum, Datenschutzerklärung, Kontakt

---

## 8. Ton und Gestaltung

**Ton:** Deutsch, per Du, klar und unaufgeregt. Kurze Sätze. Keine
Werbefloskeln wie „revolutionär" oder „bahnbrechend". Konkrete Beispiele
schlagen Adjektive: nicht „mächtige Suche", sondern „Wie viel habe ich
bei Amazon ausgegeben?".

**Farben (aus der App):**
- Akzent/Orange: `#EE5F0F` (Hauptfarbe, Knöpfe, Hervorhebungen)
- Dunkles Orange für Verläufe: `#D9530A`
- Dunkler Hintergrund: `#101012`
- Heller Hintergrund: `#FFFFFF` bis `#F7F4F6`
- Textfarbe dunkel: `#1A1A1A`, auf Dunkel: `#F2F2F2`

**Logo/Marke:** Das App-Symbol besteht aus vier Blöcken (zwei dunkle,
zwei orange) — dieses Blockmotiv kann als gestalterisches Element für
Abschnittstrenner oder Aufzählungspunkte dienen.

**Bildmaterial** (liegt vor und wird separat geliefert):
- App-Symbol 512×512 (`blockmail-app-symbol-512.png`)
- Vorstellungsgrafik 1024×500 (`blockmail-vorstellungsgrafik-1024x500.png`)
- 7 Handy-Screenshots (Posteingang in Kacheln, KI-Zusammenfassung,
  KI-Antworten, Listenansicht, Farbwelten)
- 2 Tablet-Screenshots (geteilte Ansicht)

**Technische Wünsche:** Eine einzelne, schnell ladende Seite; für Handys
gebaut (die Zielgruppe kommt vom Handy); Bilder in modernen Formaten;
keine Cookie-Banner-Notwendigkeit erzeugen, also keine externen
Tracker, keine eingebetteten Schriften von fremden Servern (Schriften
selbst hosten — das ist in Deutschland auch datenschutzrechtlich
sauberer).

---

## 9. Häufige Fragen (Inhalte für den FAQ-Abschnitt)

**Welche E-Mail-Anbieter funktionieren?**
Alle mit IMAP: Gmail, GMX, WEB.DE, Outlook/Hotmail, Yahoo, T-Online,
iCloud, Firmen- und eigene Adressen. Fertige Voreinstellungen für die
gängigen Anbieter sind eingebaut.

**Kostet die App etwas?**
Nein. Alle Mail-Funktionen sind dauerhaft kostenlos. Nur die
KI-Funktionen sind im Abo BlockMail Pro zusammengefasst: 3 Tage
kostenlos testen, danach 4,90 € im Monat, jederzeit kündbar.

**Was passiert mit meinen Mails?**
Sie liegen bei deinem Anbieter und auf deinem Gerät. Zugangsdaten
werden verschlüsselt lokal gespeichert. Nur wenn du eine KI-Funktion
startest, werden die betroffenen Mail-Inhalte über unseren Server
verarbeitet — gespeichert werden sie dort nicht.

**Funktioniert Push wirklich in Echtzeit?**
Ja. BlockMail hält eine dauerhafte Verbindung zum Postfach und meldet
neue Mails binnen Sekunden — auch nach einem Geräteneustart.

**Gibt es die App für iPhone?**
Derzeit nicht, BlockMail ist eine Android-App.

**Kann ich mehrere Konten nutzen?**
Ja, beliebig viele — einzeln oder gemeinsam im Sammel-Posteingang, mit
eigener Farbe je Konto.

---

## 10. Was NICHT behauptet werden darf

Diese Punkte sind wichtig — teils weil sie schlicht falsch sind, teils
weil sie im Play Store oder rechtlich Probleme machen:

- ❌ **„KI läuft auf dem Gerät" / „KI ohne Server"** — falsch. Die
  KI-Funktionen laufen über den BlockMail-Server. Nur der
  Phishing-Wächter arbeitet lokal.
- ❌ **„Fokus-Blöcke"** und ❌ **„Newsletter-Erkennung /
  Newsletter-Aufräumen"** — diese Funktionen wurden entfernt, sie dürfen
  nicht mehr beworben werden.
- ❌ **„Exchange-Konten"** — wird derzeit nicht unterstützt (nur IMAP).
- ❌ **„Kein Datenverkehr an Dritte"** — die KI-Anfragen gehen über den
  eigenen Server an einen KI-Anbieter.
- ❌ **Vergleiche mit konkreten Konkurrenzprodukten** („besser als
  Gmail") — rechtlich heikel, lieber eigene Stärken zeigen.
- ❌ **Feste Nutzerzahlen oder Bewertungen erfinden** — die App startet
  gerade erst.
- ⚠️ **Impressum und Datenschutzerklärung sind in Deutschland Pflicht.**
  Beides muss von jeder Seite aus erreichbar sein. Die
  Datenschutzerklärung der Seite muss zusätzlich erklären, was die
  Webseite selbst erhebt (Server-Logs des Hosters).

---

## 11. Suchbegriffe für die Seite

Natürlich in Fließtext und Überschriften einbauen, nicht aufreihen:

E-Mail App Android, Mail App Deutsch, IMAP App, Gmail Alternative,
E-Mail mit KI, Posteingang aufräumen,
Phishing Schutz E-Mail, mehrere E-Mail Konten Android, Push Mail
Echtzeit, E-Mail Suche, Mail App ohne Werbung, Datenschutz E-Mail App,
E-Mail Assistent, Postfach durchsuchen

---

## 12. Aufrufe zum Handeln (CTAs)

- Hauptknopf: **„Bei Google Play laden"** (offizielles Play-Badge
  verwenden, gibt es bei Google zum Herunterladen)
- Vor dem Launch alternativ: **„Benachrichtige mich zum Start"** mit
  E-Mail-Feld
- Zweitrangig: **„Alle Funktionen ansehen"** (springt zur Liste)
