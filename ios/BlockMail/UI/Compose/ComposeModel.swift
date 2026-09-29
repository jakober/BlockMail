import SwiftUI
import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// Der Editor kodiert Satzzeichen ggf. als benannte HTML-Entities (&period; usw.).
/// Outlook rendert diese nicht — vor dem Senden in normale Zeichen umwandeln
/// (Port von `sanitizeOutgoingHtml`).
fileprivate func sanitizeOutgoingHtml(_ html: String) -> String {
    html
        .replacingOccurrences(of: "&period;", with: ".")
        .replacingOccurrences(of: "&comma;", with: ",")
        .replacingOccurrences(of: "&colon;", with: ":")
        .replacingOccurrences(of: "&semi;", with: ";")
        .replacingOccurrences(of: "&excl;", with: "!")
        .replacingOccurrences(of: "&quest;", with: "?")
        .replacingOccurrences(of: "&lpar;", with: "(")
        .replacingOccurrences(of: "&rpar;", with: ")")
        .replacingOccurrences(of: "&apos;", with: "'")
        .replacingOccurrences(of: "&num;", with: "#")
        .replacingOccurrences(of: "&percnt;", with: "%")
        .replacingOccurrences(of: "&ast;", with: "*")
        .replacingOccurrences(of: "&plus;", with: "+")
        .replacingOccurrences(of: "&equals;", with: "=")
        .replacingOccurrences(of: "&sol;", with: "/")
}

/// Vom Nutzer gewählte Datei (lokale Kopie im temporären Ordner).
struct PickedFile: Identifiable, Equatable {
    let id = UUID()
    /// Lokale, jederzeit lesbare Kopie.
    var url: URL
    var name: String
    var size: Int64
    var mime: String
    /// Ursprüngliche URL (gegen doppeltes Anhängen).
    var source: URL?
}

/// Empfänger-Vorschlag (Adresse + Name).
struct RecipientSuggestion: Identifiable, Hashable {
    var address: String
    var name: String
    var id: String { address }
}

struct ComposeError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Zustand und Logik des Verfassen-Fensters (Port der State-Variablen und
/// Funktionen aus `ComposeScreen.kt`).
@MainActor
final class ComposeModel: ObservableObject {

    enum Field: Hashable { case to, cc, bcc, subject }

    let request: ComposeRequest
    /// Mail, auf die geantwortet wird.
    let original: MailMessage?
    /// Mail, die weitergeleitet wird.
    let forwardOriginal: MailMessage?
    /// Fortgesetzter Entwurf.
    let draft: Prefs.Draft?
    let prefill: ComposePrefill?
    /// Ordner der Originalmail (nil = aktueller Ordner).
    let sourceFolder: MailFolder?
    /// Bekannte Kontakte für die Vorschläge.
    let contacts: [RecipientSuggestion]
    /// Gespeicherte Konten (für den Absender-Wähler).
    let accounts: [Prefs.Account]

    let editor = RichTextController()
    let snackbar = SnackbarState()

    @Published var to: String
    @Published var cc: String
    @Published var bcc: String
    @Published var showCcBcc: Bool
    @Published var subject: String
    @Published var fromAccount: String
    @Published var busy = false
    @Published var busyLabel = ""
    @Published var sending = false
    @Published var lastLanguage: String?
    @Published var pickedFiles: [PickedFile] = []
    @Published var fwdAttachments: [MailRepository.MailAttachment] = []

    /// Dialoge
    @Published var showPromptDialog = false
    @Published var promptText = ""
    @Published var showScheduleDialog = false
    @Published var showDiscardDialog = false
    @Published var showFileImporter = false
    @Published var showPhotoPicker = false
    @Published var photoItems: [PhotosPickerItem] = []

    /// Id, unter der dieser Entwurf gespeichert wird.
    let draftKey: Int64
    /// Wurde der Entwurf (schon) gespeichert — dann beim Senden/Verwerfen entfernen.
    private var draftStored: Bool
    /// Fenster abgeschlossen (gesendet, geplant, verworfen oder gespeichert).
    @Published private(set) var finished = false
    private var started = false
    /// Message-ID der Originalmail (für In-Reply-To/References).
    private var originalMessageId: String?

    private var prefs: Prefs { Prefs.shared }
    private var repo: MailRepository { MailRepository.shared }

    init(request: ComposeRequest) {
        self.request = request
        let prefs = Prefs.shared
        let repo = MailRepository.shared
        let original = request.replyTo
        let fwd = request.forward
        self.original = original
        self.forwardOriginal = fwd
        let draft = request.draftId.flatMap { id in prefs.drafts.first { $0.id == id } }
        self.draft = draft
        self.prefill = request.prefill

        // Ordner der Originalmail: angefordert, sonst Öffnen-Merker, sonst aktueller Ordner
        var folder = request.sourceFolder
        if folder == nil, let base = original ?? fwd, let p = repo.pendingOpen,
           p.1.uid == base.uid, p.1.account.lowercased() == base.account.lowercased() {
            folder = p.0
        }
        self.sourceFolder = folder

        // „Allen antworten“: vorbereitete An-/CC-Zeile
        var replyAll: (String, String)? = nil
        if original != nil {
            replyAll = request.replyAll ?? repo.pendingReplyAll
        }
        repo.pendingReplyAll = nil

        let pre = request.prefill
        func nonBlank(_ s: String?) -> String? {
            guard let s, !s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
            return s
        }
        _to = Published(initialValue: draft?.to ?? replyAll?.0 ?? original?.fromAddress ?? nonBlank(pre?.to) ?? "")
        let ccInit = draft?.cc ?? replyAll?.1 ?? nonBlank(pre?.cc) ?? ""
        _cc = Published(initialValue: ccInit)
        let bccInit = draft?.bcc ?? nonBlank(pre?.bcc) ?? ""
        _bcc = Published(initialValue: bccInit)
        let showCc = (draft != nil && (!(draft?.cc ?? "").isBlankText || !(draft?.bcc ?? "").isBlankText)) ||
            !(replyAll?.1 ?? "").isBlankText ||
            nonBlank(pre?.cc) != nil || nonBlank(pre?.bcc) != nil
        _showCcBcc = Published(initialValue: showCc)

        var subj = ""
        if let d = draft {
            subj = d.subject
        } else if let o = fwd {
            let s = o.subject
            let lower = s.lowercased()
            if lower.hasPrefix("wg:") || lower.hasPrefix("fwd:") || lower.hasPrefix("fw:") {
                subj = s
            } else {
                subj = (deviceIsGerman ? "Wg: " : "Fwd: ") + s
            }
        } else if let o = original {
            subj = o.subject.lowercased().hasPrefix("re:") ? o.subject : "Re: \(o.subject)"
        } else if let s = nonBlank(pre?.subject) {
            subj = s
        }
        _subject = Published(initialValue: subj)

        // Absender-Konto: bei Antworten immer das Konto, in dem die Mail ankam;
        // bei neuen Mails der eingestellte Standard-Absender (sonst aktives Konto)
        let accounts = prefs.accounts()
        self.accounts = accounts
        func known(_ a: String) -> Bool {
            accounts.contains { $0.email.caseInsensitiveCompare(a) == .orderedSame }
        }
        let def = prefs.defaultSendAccount
        let from: String
        if let o = original {
            from = o.account.isEmpty ? prefs.email : o.account
        } else if let d = draft, !d.account.isBlankText, known(d.account) {
            from = d.account
        } else if let o = fwd {
            from = o.account.isEmpty ? prefs.email : o.account
        } else if !def.isBlankText, known(def) {
            from = def
        } else {
            from = prefs.email
        }
        _fromAccount = Published(initialValue: from)

        // Kontakte: Absender der geladenen Mails, dann bekannte Empfänger
        var order: [String] = []
        var names: [String: String] = [:]
        for m in repo.messages {
            let a = m.fromAddress.trimmingCharacters(in: .whitespaces).lowercased()
            guard a.contains("@") else { continue }
            if names[a] == nil { order.append(a); names[a] = m.from }
        }
        for (a, n) in prefs.knownRecipients() {
            if names[a] == nil { order.append(a) }
            names[a] = n.isBlankText ? (names[a] ?? "") : n
        }
        self.contacts = order.map { RecipientSuggestion(address: $0, name: names[$0] ?? "") }

        self.draftKey = draft?.id ?? nowMs()
        self.draftStored = draft != nil
    }

    // MARK: Abgeleitete Werte

    var titleText: String {
        if original != nil { return L("compose_title_reply") }
        if forwardOriginal != nil { return L("compose_title_forward") }
        return L("compose_title_new")
    }

    /// Absender wählbar? (Antworten laufen immer über das Konto der Originalmail.)
    var canChooseAccount: Bool { accounts.count > 1 && original == nil }

    var canSend: Bool {
        !sending && !to.isBlankText && (!subject.isBlankText || !editor.plainText.isBlankText)
    }

    /// Hat das Fenster Inhalt, der als Entwurf aufgehoben werden sollte?
    var isMeaningful: Bool {
        let bodyText = bodyWithoutSignature
        return !to.isBlankText || !subject.isBlankText || !bodyText.isEmpty
    }

    private var bodyWithoutSignature: String {
        var text = editor.plainText
        let sig = prefs.signature.trimmingCharacters(in: .whitespacesAndNewlines)
        if !sig.isEmpty { text = text.replacingOccurrences(of: sig, with: "") }
        return text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: Start

    /// Entwurfstext wiederherstellen, weitergeleitete Mail zitieren oder
    /// Signatur unter den (leeren) Text setzen. Einmalig beim Erscheinen.
    func start() async {
        guard !started else { return }
        started = true

        // Aus dem Dokument-Editor zurück: das bearbeitete Dokument hängt sofort dran
        if let r = DocumentEditing.pendingResult {
            DocumentEditing.pendingResult = nil
            let mime = Self.mimeFor(name: r.name)
            pickedFiles.append(PickedFile(url: r.url, name: r.name, size: r.size, mime: mime, source: r.url))
        }
        // Von außen hereingereichte Anhänge (Teilen, „Per Mail senden“)
        if let urls = prefill?.attachments, !urls.isEmpty {
            addFiles(urls)
        }

        let sig = prefs.signature
        if let d = draft, !d.html.isBlankText {
            editor.setHtml(d.html)
        } else if let fwd = forwardOriginal {
            let body = try? await repo.loadBodyContent(fwd.uid, folder: sourceFolder ?? repo.currentFolder,
                                                       account: fwd.account)
            let f = DateFormatter()
            f.locale = Locale(identifier: deviceIsGerman ? "de_DE" : "en_US")
            f.dateFormat = deviceIsGerman ? "EEEE, d. MMMM yyyy, HH:mm" : "EEEE, MMMM d, yyyy, HH:mm"
            let dateText = f.string(from: fwd.dateValue)
            let header = L("compose_forward_header", fwd.from, fwd.fromAddress, dateText, fwd.subject)
            let sigPart = sig.isBlankText ? "" : "\(HTMLText.plainToHtml(sig))<br><br>"
            editor.setHtml("<br><br>\(sigPart)\(HTMLText.plainToHtml(header + (body?.text ?? "")))")
            fwdAttachments = body?.attachments ?? []
        } else {
            let prefillBody = prefill?.body ?? ""
            if !prefillBody.isBlankText {
                // Text aus dem mailto:-Link ÜBER die Signatur setzen
                let sigPart = sig.isBlankText ? "" : "<br><br>\(HTMLText.plainToHtml(sig))"
                editor.setHtml("\(HTMLText.plainToHtml(prefillBody))\(sigPart)")
            } else if !sig.isBlankText && editor.plainText.isBlankText {
                editor.setHtml("<br><br>\(HTMLText.plainToHtml(sig))")
            }
        }

        // Message-ID der Originalmail vorab holen (für „In-Reply-To“)
        if let o = original {
            originalMessageId = (try? await repo.loadBodyContent(o.uid, folder: sourceFolder, account: o.account))?.messageId
        }
    }

    // MARK: Schließen & Entwürfe

    private func close() {
        finished = true
        AppNav.shared.compose = nil
    }

    /// Entwurf speichern (ohne das Fenster zu schließen).
    func storeDraft() {
        guard isMeaningful else { return }
        prefs.saveDraft(Prefs.Draft(
            id: draftKey, savedAt: nowMs(),
            to: to, cc: cc.trimmingCharacters(in: .whitespaces), bcc: bcc.trimmingCharacters(in: .whitespaces),
            subject: subject,
            html: sanitizeOutgoingHtml(editor.toHtml()),
            account: fromAccount))
        draftStored = true
    }

    private func removeStoredDraft() {
        if draftStored { prefs.removeDraft(draftKey) }
        draftStored = false
    }

    /// X-Knopf: bei Inhalt nachfragen (speichern/verwerfen), sonst direkt schließen.
    /// Liefert true, wenn das Fenster jetzt geschlossen werden soll.
    func requestClose() -> Bool {
        if isMeaningful {
            showDiscardDialog = true
            return false
        }
        // Wieder geöffneter, jetzt leerer Entwurf: verwerfen
        removeStoredDraft()
        close()
        return true
    }

    func closeSavingDraft() {
        storeDraft()
        close()
    }

    func discard() {
        removeStoredDraft()
        close()
    }

    /// Fenster verschwindet ohne Abschluss (z. B. von außen ersetzt): Entwurf sichern.
    func onDisappear() {
        guard !finished else { return }
        storeDraft()
    }

    // MARK: Anhänge

    static func mimeFor(name: String) -> String {
        let ext = (name as NSString).pathExtension
        if !ext.isEmpty, let t = UTType(filenameExtension: ext), let m = t.preferredMIMEType { return m }
        return MailRepository.effectiveMime(name, "")
    }

    private static func tempDir() -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("compose", isDirectory: true)
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Dateien (auch security-scoped aus der Dateien-App) als lokale Kopie anhängen.
    func addFiles(_ urls: [URL]) {
        for url in urls {
            if pickedFiles.contains(where: { $0.source == url }) { continue }
            let access = url.startAccessingSecurityScopedResource()
            defer { if access { url.stopAccessingSecurityScopedResource() } }
            let name = url.lastPathComponent.isEmpty ? L("compose_attachment") : url.lastPathComponent
            let dest = Self.tempDir().appendingPathComponent(name)
            do {
                try? FileManager.default.removeItem(at: dest)
                try FileManager.default.copyItem(at: url, to: dest)
                let size = (try? dest.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0
                pickedFiles.append(PickedFile(url: dest, name: name, size: Int64(size),
                                              mime: Self.mimeFor(name: name), source: url))
            } catch {
                snackbar.show(L("compose_attachment_unreadable", name))
            }
        }
    }

    func handleImport(_ result: Result<[URL], Error>) {
        switch result {
        case .success(let urls): addFiles(urls)
        case .failure(let e): snackbar.show(e.localizedDescription)
        }
    }

    /// Ausgewählte Fotos laden und als Dateien anhängen.
    func loadPhotos(_ items: [PhotosPickerItem]) {
        guard !items.isEmpty else { return }
        photoItems = []
        Task { @MainActor in
            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd_HHmmss"
            let stamp = f.string(from: Date())
            for (i, item) in items.enumerated() {
                let type = item.supportedContentTypes.first
                let ext = type?.preferredFilenameExtension ?? "jpg"
                let name = items.count > 1 ? "IMG_\(stamp)_\(i + 1).\(ext)" : "IMG_\(stamp).\(ext)"
                do {
                    guard let data = try await item.loadTransferable(type: Data.self) else {
                        throw ComposeError(message: L("compose_attachment_unreadable", name))
                    }
                    let dest = Self.tempDir().appendingPathComponent(name)
                    try data.write(to: dest, options: .atomic)
                    let mime = type?.preferredMIMEType ?? Self.mimeFor(name: name)
                    pickedFiles.append(PickedFile(url: dest, name: name, size: Int64(data.count),
                                                  mime: mime, source: nil))
                } catch {
                    snackbar.show(L("compose_attachment_unreadable", name))
                }
            }
        }
    }

    func removePicked(_ f: PickedFile) {
        pickedFiles.removeAll { $0.id == f.id }
    }

    func removeForwarded(_ att: MailRepository.MailAttachment) {
        if let i = fwdAttachments.firstIndex(of: att) { fwdAttachments.remove(at: i) }
    }

    // MARK: Vorlagen

    func insertTemplate(_ text: String) {
        let current = editor.toHtml()
        let addition = HTMLText.plainToHtml(text)
        editor.setHtml(editor.plainText.isBlankText ? addition : "\(current)<br>\(addition)")
    }

    // MARK: KI

    private func runAi(_ label: String, showLanguage: Bool = false,
                       _ block: @escaping @MainActor () async throws -> String) {
        Task { @MainActor in
            busy = true
            busyLabel = label
            do {
                let result = try await block().trimmingCharacters(in: .whitespacesAndNewlines)
                let html = (result.contains("<") && result.contains(">")) ? result : HTMLText.plainToHtml(result)
                editor.setHtml(html)
                if showLanguage { lastLanguage = ClaudeClient.lastReplyLanguage }
            } catch {
                snackbar.show(L("compose_ai_error", error.localizedDescription))
            }
            busy = false
        }
    }

    func aiDraftReply() {
        guard let o = original else { return }
        // Die vorbefüllte Signatur ist KEINE Anweisung an die KI — sonst entstehen Floskel-Antworten
        let instruction = bodyWithoutSignature
        let folder = sourceFolder
        let repo = self.repo
        runAi(L("compose_ai_drafting_reply"), showLanguage: true) {
            let origBody = (try? await repo.loadVisibleText(o.uid, account: o.account, folder: folder)) ?? ""
            if origBody.isBlankText {
                throw ComposeError(message: L("compose_ai_mail_load_failed"))
            }
            return try await ClaudeClient.draftReply(original: o, originalBody: origBody, instruction: instruction)
        }
    }

    func aiComposeMail() {
        let prompt = promptText
        showPromptDialog = false
        runAi(L("compose_ai_composing")) {
            try await ClaudeClient.composeMail(prompt)
        }
    }

    func aiProofread() {
        if editor.plainText.isBlankText {
            snackbar.show(L("compose_ai_no_text"))
            return
        }
        let html = editor.toHtml()
        runAi(L("compose_ai_proofreading")) {
            try await ClaudeClient.proofread(html)
        }
    }

    // MARK: Senden

    func send() {
        guard !sending else { return }
        Task { @MainActor in
            sending = true
            do {
                var out: [OutgoingMail.Attachment] = []
                for f in pickedFiles {
                    guard let data = try? Data(contentsOf: f.url) else {
                        throw ComposeError(message: L("compose_attachment_unreadable", f.name))
                    }
                    out.append(OutgoingMail.Attachment(name: f.name, mime: f.mime, data: data))
                }
                // Beim Weiterleiten: Original-Anhänge vom Server laden und anhängen
                if let fwd = forwardOriginal {
                    for att in fwdAttachments {
                        let data = try await repo.getAttachmentData(fwd.uid, att, account: fwd.account,
                                                                     folder: sourceFolder)
                        out.append(OutgoingMail.Attachment(
                            name: att.name, mime: MailRepository.effectiveMime(att.name, att.mime), data: data))
                    }
                }
                var inReplyTo: String? = nil
                if let o = original {
                    if originalMessageId == nil {
                        originalMessageId = (try? await repo.loadBodyContent(o.uid, folder: sourceFolder,
                                                                             account: o.account))?.messageId
                    }
                    inReplyTo = originalMessageId
                }
                let sentId = try await repo.send(
                    to: to, subject: subject, body: editor.plainText,
                    html: sanitizeOutgoingHtml(editor.toHtml()),
                    cc: cc.trimmingCharacters(in: .whitespaces),
                    bcc: bcc.trimmingCharacters(in: .whitespaces),
                    attachments: out, account: fromAccount, inReplyTo: inReplyTo)
                removeStoredDraft()
                // Antwort-Radar füttern: Antwort registrieren bzw. gesendete
                // Mail für „wartet auf Antwort“ vormerken
                if let o = original {
                    prefs.addReplied(o.account, o.uid)
                    prefs.addReplyRecord(account: o.account, uid: o.uid, at: nowMs(), messageId: sentId ?? "")
                    repo.setAnsweredAsync(o.uid, account: o.account)
                }
                let firstTo = AddressParser.parse(to).first?.email
                    ?? to.components(separatedBy: CharacterSet(charactersIn: ",;"))
                        .map { $0.trimmingCharacters(in: .whitespaces) }
                        .first { $0.contains("@") }
                if let firstTo { prefs.addSentLog(to: firstTo, subject: subject) }
                sending = false
                close()
            } catch {
                sending = false
                snackbar.show(L("compose_send_failed", repo.friendlyError(error)))
            }
        }
    }

    // MARK: Später senden

    /// Auswahlzeiten für „Später senden“ (Label + Zeitpunkt in ms).
    nonisolated static func scheduleChoices() -> [(String, Int64)] {
        let now = nowMs()
        let cal = Calendar.current
        func at(_ days: Int, _ hour: Int) -> Int64 {
            let base = cal.date(byAdding: .day, value: days, to: Date()) ?? Date()
            let d = cal.date(bySettingHour: hour, minute: 0, second: 0, of: base) ?? base
            return d.ms
        }
        var choices: [(String, Int64)] = [(L("compose_schedule_in_1_hour"), now + 60 * 60 * 1000)]
        let eveningToday = at(0, 18)
        if eveningToday > now + 15 * 60 * 1000 {
            choices.append((L("compose_schedule_tonight"), eveningToday))
        }
        choices.append((L("compose_schedule_tomorrow_morning"), at(1, 8)))
        choices.append((L("compose_schedule_tomorrow_evening"), at(1, 18)))
        return choices
    }

    func schedule(at sendAt: Int64) {
        showScheduleDialog = false
        if !pickedFiles.isEmpty || !fwdAttachments.isEmpty {
            snackbar.show(L("compose_schedule_attachments_unsupported"))
            return
        }
        prefs.addOutbox(Prefs.ScheduledMail(
            id: nowMs(), sendAt: sendAt,
            to: to, cc: cc.trimmingCharacters(in: .whitespaces), bcc: bcc.trimmingCharacters(in: .whitespaces),
            subject: subject, body: editor.plainText,
            html: sanitizeOutgoingHtml(editor.toHtml()),
            account: fromAccount))
        // Hintergrundabruf auf die Wunschzeit anfordern
        BackgroundScheduler.scheduleOutboxReminder()
        removeStoredDraft()
        close()
    }
}

extension String {
    /// Leer oder nur Leerzeichen (Kotlin `isBlank()`).
    var isBlankText: Bool { trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
