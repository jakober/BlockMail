import SwiftUI
import UIKit
import QuickLook

/// Mail-Detailansicht (Port von `ui/DetailScreen.kt`).
///
/// `folder` nil = aktuell angezeigter Ordner (dann mit Menü und automatischer
/// Gelesen-Markierung); `fallback` für Treffer außerhalb der geladenen Liste
/// (Suche/KI/„Antwort ansehen“).
struct DetailScreen: View {
    let uid: Int64
    let account: String
    let folder: MailFolder?
    let fallback: MailMessage?

    init(uid: Int64, account: String, folder: MailFolder?, fallback: MailMessage?) {
        self.uid = uid
        self.account = account
        self.folder = folder
        self.fallback = fallback
    }

    @Environment(AppNav.self) private var nav
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    @State private var snackbar = SnackbarState()
    @State private var mailBody: MailRepository.MailBody?
    @State private var loadError: String?
    /// Ordner, aus dem der Inhalt geladen wurde (für Anhänge).
    @State private var loadFolder: MailFolder = .INBOX
    /// Sofortige Antwort auf das Antippen des Sterns (auch bei eingefrorener Kopie).
    @State private var starOverride: Bool?
    @State private var summary: String?
    @State private var summarizing = false
    @State private var phishing: PhishingCheck.Result?
    @State private var phishingChecked = false
    @State private var attachmentSheet: DetailAttachmentItem?
    @State private var attsExpanded = false
    @State private var replyMenuOpen = false
    @State private var showSnooze = false
    @State private var previewURL: URL?
    /// Absender-Logo als data:-URI für den Kopf der HTML-Seite.
    @State private var senderIconURI: String?
    @State private var senderIconFor: String?
    /// Bereits geladene Mail (Rückkehr aus Editor o. Ä. lädt nicht neu).
    @State private var loadedKey: String?

    private var repo: MailRepository { MailRepository.shared }
    private var prefs: Prefs { Prefs.shared }

    // MARK: Abgeleitete Werte

    private func acctKey(_ a: String) -> String {
        let t = DetailFormat.norm(a)
        return t.isEmpty ? DetailFormat.norm(prefs.email) : t
    }

    /// Die Live-Liste hat Vorrang (Stern, Gelesen …); der Rückfall greift nur,
    /// wenn die Mail außerhalb des geladenen Fensters liegt. UIDs sind je Konto
    /// vergeben — deshalb zählt nur ein Treffer mit passendem Konto.
    private var mail: MailMessage? {
        let wanted = acctKey(fallback?.account ?? account)
        let liveApplies = folder == nil || (folder == repo.currentFolder && repo.customFolder == nil && !repo.starred)
        if liveApplies,
           let live = repo.messages.first(where: { $0.uid == uid && acctKey($0.account) == wanted }) {
            return live
        }
        return fallback
    }

    /// Konto der Mail ("" = aktives Konto).
    private var mailAccount: String {
        let a = mail?.account ?? ""
        return a.isEmpty ? account : a
    }

    private var loadKey: String { "\(acctKey(account)):\(uid):\(folder?.rawValue ?? "-")" }

    private var ownAddresses: Set<String> {
        Set((prefs.accounts().map { $0.email } + [prefs.email]).map { DetailFormat.norm($0) })
    }

    // MARK: Body

    var body: some View {
        content
            .background(palette.surface.ignoresSafeArea())
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .overlay(alignment: .bottomTrailing) { replyButtons }
            .snackbar(snackbar)
            .task(id: loadKey) { await loadMail() }
            .task(id: mail?.fromAddress ?? "") { await loadSenderIcon() }
            .sheet(item: $attachmentSheet) { item in
                DetailAttachmentSheet(
                    att: item.att,
                    editable: DocumentEditing.isEditable(
                        mime: MailRepository.effectiveMime(item.att.name, item.att.mime),
                        name: item.att.name)
                ) { action in
                    attachmentSheet = nil
                    attachmentAction(item.att, action, fromSheet: true)
                }
                .environment(\.palette, palette)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
            }
            .confirmationDialog(L("detail_snooze_title"), isPresented: $showSnooze, titleVisibility: .visible) {
                ForEach(snoozeChoices()) { c in
                    Button(c.label) { snooze(until: c.until) }
                }
                Button(L("detail_cancel"), role: .cancel) {}
            } message: {
                Text(L("detail_snooze_description"))
            }
            .alert(L("detail_summary_title"), isPresented: summaryAlertBinding) {
                Button(L("detail_ok")) { summary = nil }
            } message: {
                Text(summary ?? "")
            }
            .quickLookPreview($previewURL)
    }

    /// Bei HTML-Mails liegt der Kopf IM Seiteninhalt — das KI-Ergebnis kommt
    /// deshalb als Dialog (bei Text-Mails als Karte im Kopf).
    private var summaryAlertBinding: Binding<Bool> {
        Binding(
            get: { mailBody?.html != nil && summary != nil },
            set: { if !$0 { summary = nil } }
        )
    }

    @ViewBuilder
    private var content: some View {
        if let m = mail {
            if let err = loadError {
                VStack {
                    Text(L("detail_load_error", err))
                        .foregroundStyle(palette.error)
                        .padding(20)
                    Spacer()
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            } else if let b = mailBody, phishingChecked {
                if b.html != nil {
                    htmlContent(m, b)
                } else {
                    textContent(m, b)
                }
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            Text(L("detail_message_not_found"))
                .foregroundStyle(palette.onSurface)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: Toolbar

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if let m = mail {
                // Stern: setzt das IMAP-Kennzeichen (gilt auch in anderen Programmen)
                let starred = starOverride ?? m.flagged
                Button {
                    toggleStar(m, starred: starred)
                } label: {
                    Image(systemName: starred ? "star.fill" : "star")
                        .foregroundStyle(starred ? starGold : palette.onSurfaceVariant)
                }
                .accessibilityLabel(L(starred ? "detail_unstar" : "detail_star"))

                Menu {
                    menuItems(m)
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
                .accessibilityLabel(L("detail_menu"))
            }
        }
    }

    @ViewBuilder
    private func menuItems(_ m: MailMessage) -> some View {
        let addrKey = DetailFormat.norm(m.fromAddress)
        let isMuted = !addrKey.isEmpty && prefs.muted.contains(addrKey)
        let isBlocked = !addrKey.isEmpty && prefs.blocked.contains(addrKey)
        let isVip = !addrKey.isEmpty && prefs.vip.contains(addrKey)

        Button {
            forward(m)
        } label: {
            Label(L("detail_menu_forward"), systemImage: "arrowshape.turn.up.right")
        }
        // Mail-Aktionen nur in der normalen Mailansicht (wie Android)
        if folder == nil {
            Button(role: .destructive) {
                Task {
                    await repo.deleteMail(uid, account: mailAccount)
                    goBack()
                }
            } label: {
                Label(L("detail_menu_delete"), systemImage: "trash")
            }
            if repo.currentFolder != .ARCHIVE || repo.customFolder != nil {
                Button {
                    Task {
                        await repo.moveMail(uid, to: .ARCHIVE, account: mailAccount)
                        goBack()
                    }
                } label: {
                    Label(L("detail_menu_archive"), systemImage: "archivebox")
                }
            }
            Menu {
                ForEach(moveTargets, id: \.self) { target in
                    Button(target.label) {
                        Task {
                            await repo.moveMail(uid, to: target, account: mailAccount)
                            goBack()
                        }
                    }
                }
            } label: {
                Label(L("detail_menu_move"), systemImage: "folder")
            }
            Button {
                Task {
                    await repo.setSeen(uid, false, account: mailAccount)
                    goBack()
                }
            } label: {
                Label(L("detail_menu_mark_unread"), systemImage: "envelope.badge")
            }
            Button {
                showSnooze = true
            } label: {
                Label(L("detail_snooze_title"), systemImage: "clock")
            }
            Divider()
            // Stumm schalten / wieder erlauben (Toggle)
            Button {
                if isMuted {
                    prefs.removeMuted(m.fromAddress)
                    snackbar.show(L("detail_snackbar_unmuted", m.from))
                } else {
                    prefs.addMuted(m.fromAddress)
                    snackbar.show(L("detail_snackbar_muted", m.from))
                    Task { await repo.setSeen(uid, true, account: mailAccount) }
                }
            } label: {
                Label(L(isMuted ? "detail_menu_unmute" : "detail_menu_mute"),
                      systemImage: isMuted ? "bell.fill" : "bell.slash")
            }
            // VIP-Absender (Toggle)
            Button {
                if isVip {
                    prefs.removeVip(m.fromAddress)
                    snackbar.show(L("detail_snackbar_vip_removed", m.from))
                } else {
                    prefs.addVip(m.fromAddress)
                    snackbar.show(L("detail_snackbar_vip_added", m.from))
                }
            } label: {
                Label(L(isVip ? "detail_menu_vip_remove" : "detail_menu_vip_add"),
                      systemImage: isVip ? "star.fill" : "star")
            }
            // Blockieren / entsperren (Toggle)
            Button(role: isBlocked ? nil : .destructive) {
                if isBlocked {
                    prefs.removeBlocked(m.fromAddress)
                    snackbar.show(L("detail_snackbar_unblocked", m.from))
                } else {
                    prefs.addBlocked(m.fromAddress)
                    Task {
                        await repo.deleteMail(uid, account: mailAccount)
                        goBack()
                    }
                }
            } label: {
                Label(L(isBlocked ? "detail_menu_unblock" : "detail_menu_block"),
                      systemImage: isBlocked ? "lock.open" : "nosign")
            }
            Divider()
        }
        Button {
            shareMail(m)
        } label: {
            Label(L("detail_menu_share"), systemImage: "square.and.arrow.up")
        }
        Button {
            printMail(m)
        } label: {
            Label(L("detail_menu_print"), systemImage: "printer")
        }
    }

    private var moveTargets: [MailFolder] {
        MailFolder.allCases.filter { $0 != repo.currentFolder || repo.customFolder != nil }
    }

    // MARK: Antworten-Knöpfe

    /// Platzsparender Antworten-Knopf: Gibt es weitere Empfänger, klappt ein
    /// Tipp „Allen antworten“ und „Antwort an <Absender>“ darüber auf.
    @ViewBuilder
    private var replyButtons: some View {
        // Gesendete Mail („Antwort ansehen“): kein Antworten-Knopf
        if let m = mail, folder != .SENT {
            let r = recipients(m)
            VStack(alignment: .trailing, spacing: 12) {
                if replyMenuOpen, !r.others.isEmpty {
                    extendedButton(icon: "arrowshape.turn.up.left.2",
                                   text: L("detail_reply_all_to", (r.allTo + r.allCc).joined(separator: ", ")),
                                   lines: 2) {
                        replyMenuOpen = false
                        reply(m, all: (r.allTo.joined(separator: ", "), r.allCc.joined(separator: ", ")))
                    }
                    extendedButton(icon: "arrowshape.turn.up.left",
                                   text: L("detail_reply_to", m.fromAddress),
                                   lines: 1) {
                        replyMenuOpen = false
                        reply(m, all: nil)
                    }
                }
                Button {
                    if r.others.isEmpty {
                        reply(m, all: nil)
                    } else {
                        withAnimation(.easeInOut(duration: 0.15)) { replyMenuOpen.toggle() }
                    }
                } label: {
                    Image(systemName: replyMenuOpen ? "xmark" : "arrowshape.turn.up.left.fill")
                        .font(.title2)
                        .foregroundStyle(palette.onPrimaryContainer)
                        .frame(width: 56, height: 56)
                        .background(RoundedRectangle(cornerRadius: 16).fill(palette.primaryContainer))
                        .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
                }
                .accessibilityLabel(L("detail_reply"))
            }
            .padding(.trailing, 16)
            .padding(.bottom, 16)
        }
    }

    private func extendedButton(icon: String, text: String, lines: Int, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Image(systemName: icon)
                Text(text)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(lines)
                    .truncationMode(.tail)
                    .multilineTextAlignment(.leading)
            }
            .foregroundStyle(palette.onSecondaryContainer)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .frame(maxWidth: 300, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16).fill(palette.secondaryContainer))
            .shadow(color: .black.opacity(0.2), radius: 3, y: 2)
        }
        .buttonStyle(.plain)
        .fixedSize(horizontal: false, vertical: true)
    }

    /// Weitere Empfänger (ohne eigene Adressen und Absender) sowie An/CC für „Allen antworten“.
    private func recipients(_ m: MailMessage) -> (others: [String], allTo: [String], allCc: [String]) {
        guard folder == nil, let b = mailBody else { return ([], [], []) }
        let mine = ownAddresses
        let sender = DetailFormat.norm(m.fromAddress)
        func distinct(_ list: [String]) -> [String] {
            var seen = Set<String>()
            return list.filter { seen.insert($0.lowercased()).inserted }
        }
        let others = distinct((b.to + b.cc)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !mine.contains($0.lowercased()) && $0.lowercased() != sender })
        let allTo = distinct([m.fromAddress] + b.to
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !mine.contains($0.lowercased()) && $0.lowercased() != sender })
        let toKeys = Set(allTo.map { $0.lowercased() })
        let allCc = b.cc
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !mine.contains($0.lowercased()) && !toKeys.contains($0.lowercased()) }
        return (others, allTo, allCc)
    }

    // MARK: HTML-Mail

    private func htmlContent(_ m: MailMessage, _ b: MailRepository.MailBody) -> some View {
        let n = b.attachments.count
        let texts = DetailPageTexts(
            summarize: L("detail_summarize_short"),
            phishingWarning: L("detail_phishing_warning"),
            phishingAdvice: L("detail_page_phishing_advice"),
            notPhishingLink: L("detail_page_not_phishing"),
            attachmentsLabel: n == 1 ? L("detail_attachments_one") : L("detail_attachments_many", n),
            viewReply: L("detail_view_reply"))
        let replied = repliedInfo(m)
        let page = DetailPageBuilder.build(
            mail: m, body: b, phishing: phishing, aiAvailable: true, dark: palette.dark,
            texts: texts, senderIcon: senderIconURI, accent: accentHex,
            repliedText: replied.text, showReplyLink: replied.hasLink)
        return DetailHTMLView(html: page, fontScale: prefs.fontScalePercent) { url in
            handleAppLink(url)
        }
        .ignoresSafeArea(edges: .bottom)
    }

    private var accentHex: String {
        let c = UIColor(palette.accent).rgba
        func byte(_ v: Double) -> Int { Int(max(0, min(255, (v * 255).rounded()))) }
        return String(format: "#%02X%02X%02X", byte(c.0), byte(c.1), byte(c.2))
    }

    private func handleAppLink(_ url: URL) {
        let host = (url.host ?? "").lowercased()
        switch host {
        case "summarize":
            runSummarize()
        case "openreply":
            openReply()
        case "notphishing":
            markNotPhishing()
        case "att":
            if let idx = Int(url.lastPathComponent), let b = mailBody, b.attachments.indices.contains(idx) {
                attachmentSheet = DetailAttachmentItem(att: b.attachments[idx])
            }
        default:
            break
        }
    }

    // MARK: Text-Mail

    /// Kopf und Inhalt in EINER Scroll-Spalte.
    private func textContent(_ m: MailMessage, _ b: MailRepository.MailBody) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    Text(m.subject)
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(palette.onSurface)
                        .textSelection(.enabled)
                        .padding(.top, 8)
                    HStack(spacing: 12) {
                        SenderAvatar(name: m.from, address: m.fromAddress, size: 40)
                        VStack(alignment: .leading, spacing: 1) {
                            Text(m.from)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(palette.onSurface)
                            Text(m.fromAddress)
                                .font(.caption)
                                .foregroundStyle(palette.onSurfaceVariant)
                                .textSelection(.enabled)
                            Text(DetailFormat.longDate(m.date))
                                .font(.caption)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.top, 10)
                    let replied = repliedInfo(m)
                    if let rt = replied.text {
                        HStack(spacing: 4) {
                            Text("↩ " + rt)
                                .foregroundStyle(palette.onSurfaceVariant)
                            if replied.hasLink {
                                Text("·").foregroundStyle(palette.onSurfaceVariant)
                                Button(L("detail_view_reply")) { openReply() }
                                    .fontWeight(.semibold)
                                    .foregroundStyle(palette.primary)
                            }
                        }
                        .font(.footnote)
                        .padding(.top, 8)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 10)
                Divider()
                if let p = phishing, p.suspicious {
                    phishingCard(p)
                }
                summarySection
                if !b.attachments.isEmpty {
                    attachmentsSection(b)
                }
                DetailSelectableText(text: b.text, color: UIColor(palette.onSurface))
                    .padding(.horizontal, 20)
                    .padding(.top, 12)
                    .padding(.bottom, 96)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private func phishingCard(_ p: PhishingCheck.Result) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 8) {
                Image(systemName: "exclamationmark.triangle.fill")
                    .foregroundStyle(palette.error)
                Text(L("detail_phishing_warning"))
                    .font(.subheadline.bold())
                    .foregroundStyle(palette.onErrorContainer)
            }
            ForEach(Array(p.reasons.prefix(3).enumerated()), id: \.offset) { _, reason in
                Text("• " + reason)
                    .font(.caption)
                    .foregroundStyle(palette.onErrorContainer)
            }
            Button(L("detail_phishing_not_phishing")) { markNotPhishing() }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(palette.onErrorContainer)
                .padding(.top, 2)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12).fill(palette.errorContainer))
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    }

    @ViewBuilder
    private var summarySection: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let sum = summary {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("detail_summary_title"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(palette.primary)
                    Text(sum)
                        .font(.callout)
                        .foregroundStyle(palette.onSurface)
                        .textSelection(.enabled)
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(palette.secondaryContainer.opacity(0.5)))
            } else {
                Button {
                    runSummarize()
                } label: {
                    HStack(spacing: 6) {
                        if summarizing {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "sparkles")
                        }
                        Text(L(summarizing ? "detail_summarizing" : "detail_summarize_ai"))
                            .font(.subheadline)
                    }
                    .foregroundStyle(palette.onSurface)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 7)
                    .overlay(RoundedRectangle(cornerRadius: 8).stroke(palette.outlineVariant, lineWidth: 1))
                }
                .buttonStyle(.plain)
                .disabled(summarizing)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
    }

    private func attachmentsSection(_ b: MailRepository.MailBody) -> some View {
        let n = b.attachments.count
        return VStack(alignment: .leading, spacing: 0) {
            // Zugeklappt nur die Zahl mit Pfeil — die Chips erscheinen beim Antippen
            Button {
                withAnimation(.easeInOut(duration: 0.15)) { attsExpanded.toggle() }
            } label: {
                HStack(spacing: 8) {
                    Image(systemName: "paperclip")
                    Text(n == 1 ? L("detail_attachments_one") : L("detail_attachments_many", n))
                        .font(.headline)
                    Image(systemName: attsExpanded ? "chevron.up" : "chevron.down")
                        .font(.subheadline)
                    Spacer(minLength: 0)
                }
                .foregroundStyle(palette.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if attsExpanded {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(Array(b.attachments.enumerated()), id: \.offset) { _, att in
                            attachmentChip(att)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
    }

    private func attachmentChip(_ att: MailRepository.MailAttachment) -> some View {
        Button {
            attachmentSheet = DetailAttachmentItem(att: att)
        } label: {
            HStack(spacing: 6) {
                Image(systemName: DetailFormat.icon(MailRepository.effectiveMime(att.name, att.mime)))
                    .font(.footnote)
                Text("\(att.name) (\(DetailFormat.size(att.size)))")
                    .font(.footnote)
                    .lineLimit(1)
            }
            .foregroundStyle(palette.onSurface)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(palette.outlineVariant, lineWidth: 1))
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { attachmentAction(att, .open, fromSheet: false) } label: {
                Label(L("detail_attachment_open"), systemImage: "eye")
            }
            Button { attachmentAction(att, .share, fromSheet: false) } label: {
                Label(L("detail_attachment_share"), systemImage: "square.and.arrow.up")
            }
            Button { attachmentAction(att, .save, fromSheet: false) } label: {
                Label(L("detail_attachment_save_files"), systemImage: "folder")
            }
        }
    }

    // MARK: Beantwortet

    /// Beantwortet-Zeile: mit Datum aus dem lokalen Antwort-Gedächtnis; ohne
    /// (nur \Answered vom Server) nur das Wort.
    private func repliedInfo(_ m: MailMessage) -> (text: String?, hasLink: Bool) {
        let rec = prefs.replyRecord(account: mailAccount, uid: uid)
        let text: String?
        if let rec, rec.at > 0 {
            text = L("detail_replied_at", DetailFormat.repliedDate(rec.at))
        } else if m.answered || rec != nil {
            text = L("detail_replied")
        } else {
            text = nil
        }
        return (text, !(rec?.messageId ?? "").trimmingCharacters(in: .whitespaces).isEmpty)
    }

    /// Gesendete Antwort über die gemerkte Message-ID im Gesendet-Ordner suchen.
    private func openReply() {
        let acc = mailAccount
        let mid = prefs.replyRecord(account: acc, uid: uid)?.messageId ?? ""
        Task {
            var sent: MailMessage?
            if !mid.isEmpty {
                sent = await repo.findSentByMessageId(mid, account: acc)
            }
            if let sent {
                nav.push(.detail(uid: sent.uid, account: sent.account, folder: .SENT, fallback: sent))
            } else {
                snackbar.show(L("detail_reply_not_found"))
            }
        }
    }

    // MARK: Laden

    private func loadMail() async {
        let key = loadKey
        if loadedKey == key && mailBody != nil { return }
        // Zustand zurücksetzen (Zweispalten-Ansicht: gleiche View, neue Mail)
        mailBody = nil
        loadError = nil
        starOverride = nil
        summary = nil
        summarizing = false
        phishing = nil
        phishingChecked = false
        attsExpanded = false
        replyMenuOpen = false
        let f = folder ?? repo.currentFolder
        loadFolder = f
        let acc = mailAccount
        // Nur im normalen Ordner automatisch als gelesen markieren — parallel,
        // damit die Server-Meldung die Anzeige nicht verzögert
        if folder == nil, let m = mail, !m.seen {
            let u = uid
            Task { await repo.markSeen(u, account: acc) }
        }
        do {
            let b = try await repo.loadBodyContent(uid, folder: f, account: acc)
            if Task.isCancelled { return }
            mailBody = b
            loadedKey = key
            await checkPhishing(b, account: acc)
        } catch {
            if Task.isCancelled { return }
            loadError = repo.friendlyError(error)
        }
    }

    /// Phishing-Wächter: Prüfung im Hintergrund; der Inhalt erscheint erst
    /// danach, damit das Layout von Anfang an steht.
    private func checkPhishing(_ b: MailRepository.MailBody, account acc: String) async {
        // Vom Nutzer freigegeben („kein Phishing“)? Dann nie mehr warnen
        if prefs.isPhishingCleared(acc, uid) {
            phishing = nil
            prefs.markPhishing(acc, uid, false)
            phishingChecked = true
            return
        }
        guard let m = mail else {
            phishingChecked = true
            return
        }
        // Eigene Mails nie als Phishing werten
        if ownAddresses.contains(DetailFormat.norm(m.fromAddress)) {
            phishing = nil
            prefs.markPhishing(acc, uid, false)
            phishingChecked = true
            return
        }
        let from = m.from, addr = m.fromAddress, subject = m.subject
        let html: String? = b.html.map { String($0.prefix(300_000)) }
        let text = String(b.text.prefix(20_000))
        let result = await Task.detached(priority: .userInitiated) {
            PhishingCheck.analyze(fromName: from, fromAddress: addr, subject: subject, html: html, text: text)
        }.value
        if Task.isCancelled { return }
        phishing = result
        prefs.markPhishing(acc, uid, result.suspicious)
        phishingChecked = true
    }

    /// Absender-Logo vorab laden und als data:-URI in die HTML-Seite geben
    /// (die WebView kann ohne JavaScript keine Fallback-Kette abarbeiten).
    private func loadSenderIcon() async {
        let addr = mail?.fromAddress ?? ""
        if senderIconFor == addr { return }
        senderIconURI = nil
        senderIconFor = nil
        let domain = addr.split(separator: "@").last.map { DetailFormat.norm(String($0)) } ?? ""
        guard addr.contains("@"), !domain.isEmpty else { return }
        guard let img = await SenderIconLoader.shared.image(for: domain),
              let png = img.pngData() else { return }
        if Task.isCancelled { return }
        senderIconURI = "data:image/png;base64," + png.base64EncodedString()
        senderIconFor = addr
    }

    // MARK: Aktionen

    private func goBack() {
        if let last = nav.path.last, case .detail(let u, _, _, _) = last, u == uid {
            nav.pop()
        } else if nav.selected != nil {
            nav.selected = nil
        } else {
            dismiss()
        }
    }

    private func reply(_ m: MailMessage, all: (String, String)?) {
        repo.pendingReplyAll = all
        nav.compose = ComposeRequest(replyTo: m, replyAll: all, sourceFolder: folder)
    }

    private func forward(_ m: MailMessage) {
        nav.compose = ComposeRequest(forward: m, sourceFolder: folder)
    }

    private func toggleStar(_ m: MailMessage, starred: Bool) {
        let next = !starred
        starOverride = next
        snackbar.show(L(next ? "detail_starred" : "detail_unstarred"))
        let acc = m.account
        let u = m.uid
        Task { await repo.setFlagged(u, next, account: acc) }
    }

    private func markNotPhishing() {
        prefs.markNotPhishing(mailAccount, uid)
        phishing = nil
        snackbar.show(L("detail_snackbar_not_phishing"))
    }

    private func runSummarize() {
        guard let b = mailBody, let m = mail, !summarizing else { return }
        summarizing = true
        snackbar.show(L("detail_summarizing"))
        Task {
            var text = b.text
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let h = b.html {
                text = HTMLText.visibleText(h)
            }
            do {
                summary = try await ClaudeClient.summarize(
                    from: "\(m.from) <\(m.fromAddress)>", subject: m.subject, body: text)
            } catch {
                summary = L("detail_summarize_failed", error.localizedDescription)
            }
            summarizing = false
        }
    }

    private func attachmentAction(_ att: MailRepository.MailAttachment, _ action: DetailAttachmentAction,
                                  fromSheet: Bool) {
        let acc = mailAccount
        let f = loadFolder
        let u = uid
        snackbar.show(L("detail_attachment_loading", att.name))
        Task {
            do {
                let data = try await repo.getAttachmentData(u, att, account: acc, folder: f)
                let mime = MailRepository.effectiveMime(att.name, att.mime)
                // Blatt erst ganz schließen lassen, bevor etwas Neues erscheint
                if fromSheet { try? await Task.sleep(nanoseconds: 350_000_000) }
                switch action {
                case .sign:
                    // Anhang an den Editor übergeben — der schickt ihn danach
                    // direkt als Antwort raus
                    DocumentEditing.pending = DocumentEditing.Source(
                        name: att.name, mime: mime, data: data, url: nil,
                        replyUid: u, account: acc, origin: .mail)
                    snackbar.current = nil
                    nav.push(.editor)
                case .open:
                    let url = try MailRepository.writeTempFile(name: att.name, data: data)
                    snackbar.current = nil
                    previewURL = url
                case .share:
                    let url = try MailRepository.writeTempFile(name: att.name, data: data)
                    snackbar.current = nil
                    DetailPresenter.share([url])
                case .save:
                    let url = try MailRepository.writeTempFile(name: att.name, data: data)
                    snackbar.current = nil
                    DetailPresenter.export(url) { saved in
                        if saved { snackbar.show(L("detail_attachment_saved", att.name)) }
                    }
                }
            } catch {
                snackbar.show(L("detail_attachment_action_failed", error.localizedDescription))
            }
        }
    }

    // MARK: Snooze

    private struct SnoozeChoice: Identifiable {
        let label: String
        let until: Int64
        var id: String { label }
    }

    /// Auswahlzeiten für „Später erinnern“ (wie Android).
    private func snoozeChoices() -> [SnoozeChoice] {
        let now = Date()
        let cal = Calendar.current
        func at(_ days: Int, _ hour: Int) -> Date {
            let day = cal.date(byAdding: .day, value: days, to: now) ?? now
            return cal.date(bySettingHour: hour, minute: 0, second: 0, of: day) ?? day
        }
        var list = [SnoozeChoice(label: L("detail_snooze_in_1_hour"), until: now.ms + 60 * 60 * 1000)]
        let evening = at(0, 18)
        if evening.ms > now.ms + 15 * 60 * 1000 {
            list.append(SnoozeChoice(label: L("detail_snooze_tonight"), until: evening.ms))
        }
        list.append(SnoozeChoice(label: L("detail_snooze_tomorrow_morning"), until: at(1, 8).ms))
        list.append(SnoozeChoice(label: L("detail_snooze_in_3_days"), until: at(3, 8).ms))
        var monday = cal.date(byAdding: .day, value: 1, to: now) ?? now
        var guardCount = 0
        while cal.component(.weekday, from: monday) != 2 && guardCount < 8 {
            monday = cal.date(byAdding: .day, value: 1, to: monday) ?? monday
            guardCount += 1
        }
        let mondayAt8 = cal.date(bySettingHour: 8, minute: 0, second: 0, of: monday) ?? monday
        list.append(SnoozeChoice(label: L("detail_snooze_next_week"), until: mondayAt8.ms))
        return list
    }

    private func snooze(until: Int64) {
        guard let m = mail else { return }
        prefs.addSnooze(Prefs.Snooze(uid: m.uid, until: until, from: m.from,
                                     address: m.fromAddress, subject: m.subject))
        goBack()
    }

    // MARK: Teilen / Drucken

    private func shareMail(_ m: MailMessage) {
        var text = m.subject + "\n" + "\(m.from) <\(m.fromAddress)>" + "\n" + DetailFormat.longDate(m.date)
        if let b = mailBody {
            var t = b.text
            if t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty, let h = b.html {
                t = HTMLText.visibleText(h)
            }
            text += "\n\n" + t
        }
        DetailPresenter.share([text])
    }

    private func printMail(_ m: MailMessage) {
        let header = "<div style=\"font-family:-apple-system,sans-serif;\">" +
            "<h2>\(HTMLText.escape(m.subject))</h2>" +
            "<p>\(HTMLText.escape(m.from)) &lt;\(HTMLText.escape(m.fromAddress))&gt;<br>" +
            "\(HTMLText.escape(DetailFormat.longDate(m.date)))</p><hr></div>"
        let content: String
        if let b = mailBody {
            content = b.html ?? "<div style=\"font-family:-apple-system,sans-serif;\">" +
                HTMLText.plainToHtml(b.text) + "</div>"
        } else {
            content = ""
        }
        let info = UIPrintInfo(dictionary: nil)
        info.outputType = .general
        info.jobName = m.subject.isEmpty ? "BlockMail" : m.subject
        let pc = UIPrintInteractionController.shared
        pc.printInfo = info
        pc.printFormatter = UIMarkupTextPrintFormatter(markupText: header + content)
        pc.present(animated: true, completionHandler: nil)
    }
}
