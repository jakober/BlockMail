# Portierungs-Leitfaden Android → iOS (intern)

Ziel: Die iOS-App (SwiftUI, iOS 17+, Swift 5-Sprachmodus) bildet die Android-App
(Kotlin/Compose, Stand v4.75) 1:1 nach — **ohne Abo/Pro-Sperren**: alles ist immer
freigeschaltet (`Prefs.shared.isPro == true`). Keine Upsell-Dialoge, keine Pro-Hinweiskarten,
keine Kontingent-Anzeigen, keine Play-Billing-Reste.

## Grundregeln
- Android-Quelle: `app/src/main/java/com/jakober/klarmail/**`, `document/src/main/java/**`.
- iOS-Code: `ios/BlockMail/**` (App), `ios/BlockMailWidget/**` (Widget).
- Kein lokaler Swift-Compiler: sauberen, konservativen Swift-Code schreiben (keine exotischen APIs,
  keine Makros außer `@Observable`). Kompiliert wird per CI (`.github/workflows/ios-build.yml`, Xcode 26).
- **Keine Dateien unter `ios/BlockMail/Core/**` ändern** (gehört dem Kern). Fehlt etwas im Kern,
  als `extension` in einer eigenen Datei im eigenen Bereich ergänzen oder im Abschlussbericht nennen.
- Keine neuen Pakete/Abhängigkeiten (kein SPM). Nur Apple-Frameworks (SwiftUI, UIKit, PDFKit,
  WebKit, QuickLook, PhotosUI, VisionKit, WidgetKit …).
- Nicht committen – das macht der Hauptagent.

## Texte (Lokalisierung)
- `L("schluessel", args...)` liefert den Text; die Schlüssel sind **identisch** mit den Android-
  Ressourcen `R.string.schluessel` (`stringResource(R.string.x, a)` → `L("x", a)`).
  Platzhalter wurden umgesetzt: `%1$s` → `%1$@` (Swift `String` übergeben), `%1$d` → `%1$ld` (`Int` übergeben!).
- Hart codierte deutsche Texte im Kotlin-Code dürfen so übernommen werden.
- Neue, nur unter iOS nötige Texte: in `ios/tools/extra_strings_<bereich>_de.json` und
  `..._en.json` eintragen (eigene Datei je Bereich, z. B. `extra_strings_inbox_de.json`), dann
  `python3 ios/tools/convert_strings.py`. `python3 ios/tools/check_strings.py` prüft, dass alle
  benutzten Schlüssel existieren — muss grün sein.

## Kern-API (Auszug)
- `Prefs.shared` (`@Observable`, im Environment als `@Environment(Prefs.self)`): alle Einstellungen
  mit Android-Namen (`colorScheme`, `darkMode`, `inboxLayout`, `swipeLeftAction`, `muted`, `blocked`,
  `vip`, `drafts`, `snoozes()`, `outbox()`, `accounts()`, `activateAccount`, `signature`,
  `mailTemplates()`, `knownRecipients()`, `accountColor(_:)`, `extraFolders`, `hiddenFolders`, …).
  Mengen heißen ohne „Flow“: `mutedFlow` → `muted`, `blockedFlow` → `blocked`, `snoozedFlow` → `snoozed`.
  `notifActions` über `setNotifActions(_:)` setzen. Neu: `claudeApiKey`, `pushServerURL`, `pushMode`.
- `MailRepository.shared` (`@MainActor @Observable`, Environment): `messages`, `loading`,
  `loadingMore`, `canLoadMore`, `error`, `currentFolder`, `customFolder`, `unified`, `starred`,
  `refresh()`, `loadMore()`, `switchFolder`, `switchCustomFolder`, `switchStarred`, `setUnified`,
  `switchAccount`, `search(_:)`, `headerIndex`, `searchHeadersFor`, `loadBodyContent(uid, folder:, account:)`
  → `MailBody(html, text, attachments, to, cc, messageId)`, `loadVisibleText`, `setSeen`, `markSeen`,
  `setFlagged`, `setAnswered(Async)`, `deleteMail`, `deleteBatch`, `setSeenBatch`, `moveMail(uid, to:)`,
  `hideLocally`, `restoreLocally`, `archiveInboxByUid`, `send(...)` → Message-ID,
  `getAttachmentData(uid, att, account:, folder:)`, `effectiveMime`, `writeTempFile(name:data:)`,
  `attachmentIndex()`, `prefetchAttachmentBodies()`, `findSentByMessageId`, `listServerFolders`,
  `friendlyError(_:)`, `pendingOpen`, `pendingReplyAll`.
  Konto-Konvention wie Android: `account == ""` = aktives Konto, sonst Adresse in Kleinbuchstaben.
  UIDs sind `Int64`, Zeiten in Millisekunden (`Int64`, `nowMs()`, `Date(ms:)`, `date.ms`).
- `MailFolder` (`.INBOX/.SENT/.DRAFTS/.ARCHIVE/.TRASH`, `.label`).
- `ClaudeClient` (alle KI-Funktionen wie Android: `summarize`, `summarizeDay`, `askMailbox`,
  `answerWithContents`, `classifyMails`, `draftReply`, `composeMail`, `composeDocument`,
  `reviseDocument`, `proofread`, `lastReplyLanguage`, `isAvailable`). Fehlt ein Schlüssel,
  kommt eine verständliche Fehlermeldung — einfach anzeigen.
- `PhishingCheck.analyze(...)`, `MailIndex.shared` (lokaler Suchindex), `MailChecker`,
  `PushService.shared.status` (Push-Statuszeile), `PushRegistration.shared`, `Notifier`.
- `GoogleAuth.signIn()` (async, liefert E-Mail/Tokens), `GoogleAuth.isConfigured`.
- `AppNav.shared` (Environment): `path: [Route]`, `push(.detail(...))`, `compose = ComposeRequest(...)`,
  `showNewPdf`. `AppRouter.shared`: Anforderungen von außen (Benachrichtigung, Widget, Links).
- Theme: `@Environment(\.palette) var palette` mit Material-Rollen (`primary`, `onPrimary`,
  `primaryContainer`, `surface`, `surfaceContainer`, `onSurfaceVariant`, `outlineVariant`,
  `error`, `errorContainer`, `accent` …), `starGold`, `SchemeDef.all/current`, `SenderAvatar(name:address:size:)`.

## Abbildung Compose → SwiftUI (Richtwerte)
- `Scaffold/TopAppBar` → `NavigationStack`-Toolbar; `FloatingActionButton` → schwebender Button unten rechts.
- `LazyColumn` → `List`/`ScrollView+LazyVStack`; `SwipeToDismissBox` → `.swipeActions`.
- `DropdownMenu` → `Menu`/`.contextMenu`; `AlertDialog` → `.alert`/`.sheet`; `Snackbar` → kleines
  Overlay-Banner (Hilfsview selbst bauen oder `.overlay`).
- `WebView` → `WKWebView` (UIViewRepresentable), JavaScript aus, Links in Safari öffnen.
- Anhang öffnen → QuickLook (`QLPreviewController`); „In Downloads speichern“ → Dateien-Export
  (`UIDocumentPickerViewController(forExporting:)`) bzw. Teilen-Blatt.
- Haptik: `UIImpactFeedbackGenerator`/`.sensoryFeedback`.
