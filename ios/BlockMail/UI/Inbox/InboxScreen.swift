import SwiftUI
import UIKit

/// Ein Abschnitt der Posteingangs-Liste (Überschrift + Einträge).
struct InboxSection: Identifiable {
    let id: String
    let title: String?
    var focusToolbar: Bool = false
    let items: [InboxItem]
}

/// Ein Eintrag: einzelne Mail oder Konversations-Bündel.
enum InboxItem: Identifiable {
    case mail(MailMessage, inThread: Bool)
    case thread(InboxMailThread, expanded: Bool)

    var id: String {
        switch self {
        case .mail(let m, _): return "\(m.account):\(m.uid)"
        case .thread(let t, _): return "thread_\(t.key)"
        }
    }
}

/// Posteingang (Port von `InboxScreen.kt`) — ohne Abo/Pro-Sperren:
/// alle KI-Funktionen sind immer verfügbar.
struct InboxScreen: View {
    /// Öffnet eine Route (einspaltig: Push, zweispaltig: rechte Spalte).
    let onOpen: (Route) -> Void

    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var searchFocused: Bool

    private var model: InboxModel { InboxModel.shared }
    private var nav: AppNav { AppNav.shared }

    var body: some View {
        let configured = prefs.isConfigured
        VStack(spacing: 0) {
            if !configured {
                welcomeEmpty
            } else {
                searchBar
                if model.aiAskBusy { aiBusyPill }
                if let answer = model.aiAnswer {
                    aiAnswerCard(answer)
                    aiHitsView
                } else if model.searchActive {
                    searchView
                } else {
                    mainList
                        .inboxTourTarget("list")
                        .overlay(alignment: .bottomLeading) { aiFab }
                }
            }
        }
        .background(palette.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent(configured: configured) }
        .overlay(alignment: .bottomTrailing) { composeFab(configured: configured) }
        .snackbar(model.snackbar)
        .sheet(item: Bindable(model).aiResult) { result in
            InboxSummarySheet(result: result) { mail in
                model.aiResult = nil
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 350_000_000)
                    openMail(mail)
                }
            }
            .environment(\.palette, palette)
        }
        .sheet(isPresented: Bindable(model).showDrafts) {
            InboxDraftsSheet { id in
                model.showDrafts = false
                Task { @MainActor in
                    try? await Task.sleep(nanoseconds: 400_000_000)
                    AppNav.shared.compose = ComposeRequest(draftId: id)
                }
            }
            .environment(\.palette, palette)
            .environment(prefs)
        }
        .alert(L("inbox_thread_delete_title"),
               isPresented: Binding(get: { model.confirmDeleteThread != nil },
                                    set: { if !$0 { model.confirmDeleteThread = nil } }),
               presenting: model.confirmDeleteThread) { t in
            Button(L("inbox_delete_all"), role: .destructive) {
                model.confirmDeleteThread = nil
                model.deleteThread(t)
            }
            Button(L("inbox_cancel"), role: .cancel) { model.confirmDeleteThread = nil }
        } message: { t in
            Text(L("inbox_thread_delete_text", t.mails.count))
        }
        .task {
            if prefs.isConfigured { await repo.refresh() }
        }
        .onAppear { showPendingError() }
        .onChange(of: repo.error) { _, _ in showPendingError() }
    }

    private func showPendingError() {
        if let e = repo.error {
            model.snackbar.show(e)
            repo.clearError()
        }
    }

    // MARK: Öffnen

    private func openMail(_ m: MailMessage) {
        onOpen(.detail(uid: m.uid, account: m.account, folder: nil, fallback: nil))
    }

    /// Öffnen aus Such-/KI-Treffern: Ordner + Rückfall-Objekt mitgeben
    /// (die Mail kann außerhalb des geladenen Fensters liegen).
    private func openFromSearch(_ m: MailMessage, folder: MailFolder?) {
        repo.pendingOpen = (folder ?? repo.currentFolder, m)
        onOpen(.detail(uid: m.uid, account: m.account, folder: folder, fallback: m))
    }

    private func tapMail(_ m: MailMessage) {
        if model.selectionMode { model.toggleSelect(m.uid) } else { openMail(m) }
    }

    // MARK: Kopfzeile

    @ToolbarContentBuilder
    private func toolbarContent(configured: Bool) -> some ToolbarContent {
        if model.selectionMode {
            ToolbarItem(placement: .topBarLeading) {
                Button { model.selected.removeAll() } label: { Image(systemName: "xmark") }
                    .accessibilityLabel(L("inbox_selection_close"))
            }
            ToolbarItem(placement: .principal) {
                Text(L("inbox_selected_count", model.selected.count)).font(.headline)
            }
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { model.markSelectedRead() } label: { Image(systemName: "envelope.open") }
                    .accessibilityLabel(L("inbox_mark_read"))
                Button { model.deleteSelected() } label: { Image(systemName: "trash") }
                    .accessibilityLabel(L("inbox_delete"))
            }
        } else {
            ToolbarItem(placement: .principal) {
                folderMenu(configured: configured)
                    .inboxTourTarget("folderMenu")
            }
            ToolbarItem(placement: .topBarLeading) {
                if configured {
                    HStack(spacing: 4) {
                        themeButton
                        paletteButton
                    }
                    .inboxTourTarget("headerLeft")
                }
            }
            ToolbarItem(placement: .topBarTrailing) {
                HStack(spacing: 4) {
                    if configured { layoutQuickSwitch }
                    overflowMenu(configured: configured)
                }
                .inboxTourTarget("headerRight")
            }
        }
    }

    private var folderTitle: String {
        if repo.starred { return L("inbox_starred") }
        if repo.unified { return L("inbox_all_accounts") }
        if let c = repo.customFolder { return ModifiedUTF7.decode(c).components(separatedBy: "/").last ?? c }
        return repo.currentFolder.label
    }

    private func folderIcon(_ f: MailFolder) -> String {
        switch f {
        case .INBOX: return "tray"
        case .SENT: return "paperplane"
        case .DRAFTS: return "doc.text"
        case .ARCHIVE: return "archivebox"
        case .TRASH: return "trash"
        }
    }

    private func menuLabel(_ title: String, icon: String, active: Bool) -> some View {
        Label(title, systemImage: active ? "checkmark" : icon)
    }

    private func folderMenu(configured: Bool) -> some View {
        Menu {
            folderMenuItems
        } label: {
            HStack(spacing: 2) {
                Text(folderTitle)
                    .font(.headline)
                    .foregroundStyle(palette.onSurface)
                    .lineLimit(1)
                if configured {
                    Image(systemName: "chevron.down")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                }
            }
            .accessibilityLabel(L("inbox_folder_switch"))
        }
        .disabled(!configured)
    }

    @ViewBuilder
    private var folderMenuItems: some View {
        let _ = prefs.hiddenFoldersVersion
        let hidden = prefs.hiddenFolders(prefs.email)
        let unified = repo.unified
        let starred = repo.starred
        ForEach(MailFolder.allCases.filter { !hidden.contains($0.rawValue) }, id: \.self) { f in
            let active = !unified && !starred && repo.customFolder == nil && f == repo.currentFolder
            Button { switchFolder(f) } label: { menuLabel(f.label, icon: folderIcon(f), active: active) }
        }
        // Virtueller Ordner: alle mit Stern markierten Mails, kontoübergreifend
        Button {
            Task {
                if repo.unified { await repo.setUnified(false, reload: false) }
                await repo.switchStarred()
            }
        } label: { menuLabel(L("inbox_starred"), icon: "star", active: starred) }
        // Zusätzlich eingeblendete Server-Ordner
        ForEach(prefs.extraFolders(prefs.email).sorted { $0.lowercased() < $1.lowercased() }, id: \.self) { path in
            let active = !unified && repo.customFolder == path
            let name = ModifiedUTF7.decode(path).components(separatedBy: "/").last ?? path
            Button {
                Task {
                    if repo.unified { await repo.setUnified(false, reload: false) }
                    await repo.switchCustomFolder(path)
                }
            } label: { menuLabel(name, icon: "folder", active: active) }
        }
        if !prefs.drafts.isEmpty {
            Button { model.showDrafts = true } label: {
                Label(L("inbox_drafts_count", prefs.drafts.count), systemImage: "doc.text")
            }
        }
        Button { nav.push(.attachments) } label: { Label(L("inbox_attachments"), systemImage: "paperclip") }
        Button { nav.push(.stats) } label: { Label(L("inbox_stats"), systemImage: "chart.bar") }
        // Konten-Wechsler (nur bei mehreren gespeicherten Konten)
        let _ = prefs.accountsVersion
        let accounts = prefs.accounts()
        if accounts.count > 1 {
            Divider()
            Button {
                if !repo.unified { Task { await repo.setUnified(true) } }
            } label: { menuLabel(L("inbox_all_accounts"), icon: "tray.2", active: unified) }
            ForEach(accounts) { acc in
                let active = !unified && acc.email.caseInsensitiveCompare(prefs.email) == .orderedSame
                Button { switchAccount(acc, active: active) } label: { accountLabel(acc, active: active) }
            }
        }
    }

    private func accountLabel(_ acc: Prefs.Account, active: Bool) -> some View {
        let _ = prefs.accountColorsVersion
        return Label {
            Text(acc.email)
        } icon: {
            if active {
                Image(systemName: "checkmark")
            } else if let c = prefs.accountColor(acc.email) {
                Image(uiImage: Self.colorDot(Color(inboxArgbInt: c)))
            } else {
                Image(systemName: "person.crop.circle")
            }
        }
    }

    /// Farbiger Punkt als Bild (Menüs färben SF-Symbole nicht ein).
    private static func colorDot(_ color: Color) -> UIImage {
        let size = CGSize(width: 18, height: 18)
        let img = UIGraphicsImageRenderer(size: size).image { ctx in
            UIColor(color).setFill()
            ctx.cgContext.fillEllipse(in: CGRect(origin: .zero, size: size))
        }
        return img.withRenderingMode(.alwaysOriginal)
    }

    private func switchFolder(_ f: MailFolder) {
        Task {
            if repo.unified {
                if f == .INBOX {
                    await repo.setUnified(false)
                } else {
                    await repo.setUnified(false, reload: false)
                    await repo.switchFolder(f)
                }
            } else {
                await repo.switchFolder(f)
            }
        }
    }

    private func switchAccount(_ acc: Prefs.Account, active: Bool) {
        guard !active else { return }
        Task {
            if acc.email.caseInsensitiveCompare(prefs.email) == .orderedSame {
                // Nur den Sammel-Modus verlassen
                await repo.setUnified(false)
            } else {
                await repo.setUnified(false, reload: false)
                await repo.switchAccount(acc)
            }
        }
    }

    /// Hell/Dunkel direkt umschalten.
    private var themeButton: some View {
        Button {
            prefs.darkMode = palette.dark ? "light" : "dark"
        } label: {
            Image(systemName: palette.dark ? "sun.max" : "moon")
        }
        .accessibilityLabel(palette.dark ? L("inbox_theme_light") : L("inbox_theme_dark"))
    }

    /// Farbwechsler: Symbol in der aktuellen Schemafarbe; Klick rotiert durch die Schemata.
    private var paletteButton: some View {
        let accent = SchemeDef.current(prefs).preview
        return Button {
            let ids = SchemeDef.all.map { $0.id }
            let idx = ids.firstIndex(of: prefs.colorScheme) ?? -1
            prefs.colorScheme = ids[(idx + 1) % ids.count]
        } label: {
            Image(systemName: "paintpalette.fill").foregroundStyle(accent)
        }
        .accessibilityLabel(L("inbox_color_scheme_next"))
    }

    private static func layoutIcon(_ layout: String) -> String {
        switch layout {
        case "blocks": return "square.grid.2x2"
        case "blocks3": return "square.grid.3x3"
        default: return "list.bullet"
        }
    }

    private static func layoutLabel(_ layout: String) -> String {
        switch layout {
        case "blocks": return L("inbox_layout_blocks")
        case "blocks3": return L("inbox_layout_blocks3")
        default: return L("inbox_layout_list")
        }
    }

    /// Schnellwechsler: Symbol zeigt die NÄCHSTE Ansicht (Liste → 2er → 3er).
    private var layoutQuickSwitch: some View {
        let next: String
        switch prefs.inboxLayout {
        case "list": next = "blocks"
        case "blocks": next = "blocks3"
        default: next = "list"
        }
        return Button { prefs.inboxLayout = next } label: { Image(systemName: Self.layoutIcon(next)) }
            .accessibilityLabel(L("inbox_view_quick_switch"))
    }

    private func overflowMenu(configured: Bool) -> some View {
        Menu {
            if configured {
                Button {
                    prefs.darkMode = palette.dark ? "light" : "dark"
                } label: {
                    Label(palette.dark ? L("inbox_theme_light") : L("inbox_theme_dark"),
                          systemImage: palette.dark ? "sun.max" : "moon")
                }
                Divider()
                ForEach(["list", "blocks", "blocks3"], id: \.self) { value in
                    Button { prefs.inboxLayout = value } label: {
                        menuLabel(Self.layoutLabel(value), icon: Self.layoutIcon(value), active: prefs.inboxLayout == value)
                    }
                }
                Divider()
                Button { prefs.plainDesign.toggle() } label: {
                    menuLabel(L("settings_plain_design"), icon: "circle.lefthalf.filled", active: prefs.plainDesign)
                }
                Divider()
                // Schriftgröße: − / Prozent / + in 10er-Schritten (80–120 %)
                ControlGroup {
                    Button { prefs.fontScalePercent = max(80, prefs.fontScalePercent - 10) } label: {
                        Label(L("inbox_font_smaller"), systemImage: "textformat.size.smaller")
                    }
                    .disabled(prefs.fontScalePercent <= 80)
                    Button { prefs.fontScalePercent = 100 } label: {
                        Text("\(prefs.fontScalePercent) %")
                    }
                    Button { prefs.fontScalePercent = min(120, prefs.fontScalePercent + 10) } label: {
                        Label(L("inbox_font_larger"), systemImage: "textformat.size.larger")
                    }
                    .disabled(prefs.fontScalePercent >= 120)
                }
                Divider()
                Button { nav.push(.stats) } label: { Label(L("inbox_stats"), systemImage: "chart.bar") }
                Button { nav.push(.attachments) } label: { Label(L("inbox_attachments"), systemImage: "paperclip") }
            }
            Button { nav.showNewPdf = true } label: { Label(L("inbox_new_pdf"), systemImage: "doc.badge.plus") }
            Button { nav.push(.settings) } label: { Label(L("inbox_settings"), systemImage: "gearshape") }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .accessibilityLabel(L("inbox_more"))
    }

    // MARK: Leerer Zustand ohne Konto

    private var welcomeEmpty: some View {
        VStack(spacing: 0) {
            Spacer()
            Image(systemName: "envelope")
                .font(.system(size: 56))
                .foregroundStyle(palette.primary)
            Text(L("inbox_welcome_title"))
                .font(.title2)
                .foregroundStyle(palette.onSurface)
                .multilineTextAlignment(.center)
                .padding(.top, 16)
            Text(L("inbox_welcome_text"))
                .font(.subheadline)
                .foregroundStyle(palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Button(L("inbox_welcome_connect")) { nav.push(.settings) }
                .buttonStyle(.borderedProminent)
                .padding(.top, 24)
            Spacer()
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    // MARK: Such-/KI-Leiste

    private var searchBar: some View {
        let aiOn = ClaudeClient.isAvailable
        return HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .font(.system(size: 17))
                .foregroundStyle(palette.onSurfaceVariant)
                .accessibilityLabel(L("inbox_search"))
            TextField(aiOn ? L("inbox_ai_ask_placeholder") : L("inbox_search"),
                      text: Bindable(model).query)
                .textFieldStyle(.plain)
                .foregroundStyle(palette.onSurface)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .focused($searchFocused)
                .onSubmit {
                    searchFocused = false
                    model.submitSearch()
                }
                .onChange(of: model.query) { _, _ in model.onQueryChanged() }
            if model.aiAskBusy {
                Color.clear.frame(width: 4, height: 1)
            } else if !model.query.isEmpty {
                Button { model.exitSearch() } label: {
                    Image(systemName: "xmark")
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                .accessibilityLabel(L("inbox_search_clear"))
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 48)
        .background(Capsule().fill(palette.surfaceVariant.opacity(palette.dark ? 1 : 0.7)))
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .inboxTourTarget("search")
    }

    /// KI-Status als gut sichtbare Pille unter der Leiste.
    private var aiBusyPill: some View {
        let label: String
        if model.aiReadingCount > 0 {
            label = L("inbox_ai_reading", model.aiReadingCount)
        } else if model.aiPhase == 2 {
            label = L("inbox_ai_phase_ask")
        } else {
            label = L("inbox_ai_phase_search")
        }
        return HStack(spacing: 10) {
            ProgressView().controlSize(.small).tint(palette.onPrimaryContainer)
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(palette.onPrimaryContainer)
                .lineLimit(1)
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(Capsule().fill(palette.primaryContainer))
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.bottom, 6)
    }

    private func aiAnswerCard(_ answer: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "sparkles")
                .foregroundStyle(palette.onSecondaryContainer)
            Text(answer)
                .font(.subheadline)
                .foregroundStyle(palette.onSecondaryContainer)
                .frame(maxWidth: .infinity, alignment: .leading)
                .textSelection(.enabled)
            Button { model.exitSearch() } label: {
                Image(systemName: "xmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(palette.onSecondaryContainer)
            }
            .accessibilityLabel(L("inbox_ai_answer_close"))
        }
        .padding(.leading, 14)
        .padding(.trailing, 10)
        .padding(.vertical, 12)
        .background(RoundedRectangle(cornerRadius: 16).fill(palette.secondaryContainer))
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
    }

    // MARK: KI-Treffer

    @ViewBuilder
    private var aiHitsView: some View {
        let hits = model.aiHits
        if hits.isEmpty {
            Text(L("inbox_ai_ask_no_hits"))
                .font(.subheadline)
                .foregroundStyle(palette.onSurfaceVariant)
                .padding(20)
                .frame(maxWidth: .infinity, alignment: .leading)
            Spacer()
        } else if prefs.inboxLayout.hasPrefix("blocks") {
            let compact = prefs.inboxLayout == "blocks3"
            ScrollView {
                LazyVGrid(columns: gridColumns(compact), spacing: 10) {
                    Section {
                        ForEach(hits, id: \.inboxHitKey) { hit in
                            block(hit.mail, compact: compact, selectable: false,
                                  onTap: { openFromSearch(hit.mail, folder: hit.folder) })
                        }
                    } header: {
                        InboxSectionHeader(text: L("inbox_ai_hits_header", hits.count))
                    }
                }
                .padding(.horizontal, 10)
                .padding(.bottom, 10)
            }
        } else {
            List {
                InboxSectionHeader(text: L("inbox_ai_hits_header", hits.count))
                    .inboxPlainRow()
                ForEach(hits, id: \.inboxHitKey) { hit in
                    listRow(hit.mail, selectable: false, onTap: { openFromSearch(hit.mail, folder: hit.folder) })
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
        }
    }

    // MARK: Suchansicht

    private var searchResults: [MailMessage] {
        let q = model.query
        let unfiltered: [MailMessage]
        if let server = model.serverResults {
            unfiltered = server
        } else if q.trimmingCharacters(in: .whitespaces).isEmpty {
            unfiltered = []
        } else {
            unfiltered = repo.messages.filter {
                $0.subject.range(of: q, options: .caseInsensitive) != nil ||
                    $0.from.range(of: q, options: .caseInsensitive) != nil ||
                    $0.fromAddress.range(of: q, options: .caseInsensitive) != nil
            }
        }
        let weekAgo = nowMs() - 7 * 24 * 60 * 60 * 1000
        return unfiltered
            .filter { !model.filterUnread || !$0.seen }
            .filter { !model.filterAttachment || $0.hasAttachments }
            .filter { !model.filterRecent || $0.date >= weekAgo }
    }

    private func chip(_ title: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if on { Image(systemName: "checkmark").font(.caption.weight(.semibold)) }
                Text(title).font(.subheadline)
            }
            .foregroundStyle(on ? palette.onSecondaryContainer : palette.onSurfaceVariant)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(on ? palette.secondaryContainer : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .strokeBorder(on ? Color.clear : palette.outlineVariant, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }

    private var searchView: some View {
        let results = searchResults
        let shownKeys = Set(results.map { "\(InboxAI.normAccount($0.account)):\($0.uid)" })
        let archiveExtra = model.archiveHits.filter { h in
            h.folder != .INBOX || !shownKeys.contains("\(InboxAI.normAccount(h.mail.account)):\(h.mail.uid)")
        }
        let hasQuery = !model.query.trimmingCharacters(in: .whitespaces).isEmpty
        return VStack(spacing: 0) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip(L("inbox_filter_unread"), on: model.filterUnread) { model.filterUnread.toggle() }
                    chip(L("inbox_filter_attachment"), on: model.filterAttachment) { model.filterAttachment.toggle() }
                    chip(L("inbox_filter_recent"), on: model.filterRecent) { model.filterRecent.toggle() }
                    Button {
                        searchFocused = false
                        model.runServerSearch()
                    } label: {
                        Label(L("inbox_search_server"), systemImage: "server.rack")
                            .font(.subheadline)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .overlay(RoundedRectangle(cornerRadius: 8).strokeBorder(palette.outlineVariant, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(palette.primary)
                    .disabled(model.searching || !hasQuery)
                }
                .padding(.horizontal, 12)
            }
            if model.searching {
                ProgressView().controlSize(.small).frame(maxWidth: .infinity).padding(.top, 6)
            }
            List {
                if hasQuery {
                    InboxSectionHeader(text: model.serverResults != nil
                                       ? L("inbox_search_results", results.count)
                                       : L("inbox_search_results_local", results.count))
                        .inboxPlainRow()
                }
                ForEach(results) { mail in
                    let folder: MailFolder? = model.serverResults != nil
                        ? (model.serverSearchFolders[mail.account] ?? .INBOX) : nil
                    listRow(mail, selectable: false,
                            right: InboxSwipeSpec(label: mail.seen ? L("inbox_mark_unread") : L("inbox_mark_read"),
                                                  icon: mail.seen ? "envelope.badge" : "envelope.open") {
                                model.toggleSeenInResults(mail)
                            },
                            left: InboxSwipeSpec(label: L("inbox_delete"), icon: "trash", destructive: true) {
                                model.deleteInResults(mail)
                            },
                            onTap: { openFromSearch(mail, folder: folder) })
                }
                if hasQuery && results.isEmpty && !model.searching && archiveExtra.isEmpty {
                    Text(L("inbox_search_no_results"))
                        .font(.subheadline)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .padding(20)
                        .inboxPlainRow()
                }
                // „Aus dem Archiv“: Volltext-Treffer aus dem lokalen Index
                if !archiveExtra.isEmpty {
                    InboxSectionHeader(text: L("inbox_search_archive_header"))
                        .inboxPlainRow()
                    ForEach(archiveExtra, id: \.inboxHitKey) { hit in
                        listRow(hit.mail, selectable: false, onTap: { openFromSearch(hit.mail, folder: hit.folder) })
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .environment(\.defaultMinListRowHeight, 0)
            .scrollDismissesKeyboard(.immediately)
        }
        .task(id: model.query) { await model.updateArchiveHits(for: model.query) }
    }

    // MARK: Hauptliste

    private func sections() -> [InboxSection] {
        let messages = repo.messages
        if prefs.focusMode {
            var out = [InboxSection(id: "focus_toolbar", title: nil, focusToolbar: true, items: [])]
            for (i, mails) in model.focusSections(messages) {
                out.append(InboxSection(id: "focus_\(i)",
                                        title: L("inbox_section_label_count", InboxFocus.label(i), mails.count),
                                        items: mails.map { InboxItem.mail($0, inThread: false) }))
            }
            return out
        }
        if !prefs.conversationView {
            var out: [InboxSection] = []
            let unread = messages.filter { !$0.seen }
            let read = messages.filter { $0.seen }
            if !unread.isEmpty {
                out.append(InboxSection(id: "unread", title: L("inbox_section_new", unread.count),
                                        items: unread.map { InboxItem.mail($0, inThread: false) }))
            }
            for (g, mails) in InboxTimeGroup.group(read, date: { $0.date }) {
                out.append(InboxSection(id: "time_\(g.rawValue)", title: g.label,
                                        items: mails.map { InboxItem.mail($0, inThread: false) }))
            }
            return out
        }
        // Konversations-Ansicht: Mails mit gleichem Betreff gebündelt
        let threads = InboxMailThread.build(messages)
        func items(_ ts: [InboxMailThread]) -> [InboxItem] {
            var out: [InboxItem] = []
            for t in ts {
                if t.mails.count == 1 {
                    out.append(.mail(t.mails[0], inThread: false))
                } else {
                    let expanded = model.expandedThreads.contains(t.key)
                    out.append(.thread(t, expanded: expanded))
                    if expanded { out += t.mails.map { InboxItem.mail($0, inThread: true) } }
                }
            }
            return out
        }
        var out: [InboxSection] = []
        let unreadThreads = threads.filter { $0.unread > 0 }
        if !unreadThreads.isEmpty {
            out.append(InboxSection(id: "unread", title: L("inbox_section_new", unreadThreads.reduce(0) { $0 + $1.unread }),
                                    items: items(unreadThreads)))
        }
        for (g, ts) in InboxTimeGroup.group(threads.filter { $0.unread == 0 }, date: { $0.newest.date }) {
            out.append(InboxSection(id: "time_\(g.rawValue)", title: g.label, items: items(ts)))
        }
        return out
    }

    private func toggleThread(_ t: InboxMailThread) {
        if model.expandedThreads.contains(t.key) {
            model.expandedThreads.remove(t.key)
        } else {
            model.expandedThreads.insert(t.key)
        }
    }

    private var topMailKey: String? {
        repo.messages.first.map { "\($0.account):\($0.uid)" }
    }

    @ViewBuilder
    private var mainList: some View {
        let secs = sections()
        ScrollViewReader { proxy in
            Group {
                if prefs.inboxLayout.hasPrefix("blocks") {
                    let compact = prefs.inboxLayout == "blocks3"
                    ScrollView {
                        VStack(spacing: 0) {
                            Color.clear.frame(height: 0).id("inbox_top")
                            LazyVGrid(columns: gridColumns(compact), spacing: 10) {
                                ForEach(secs) { sec in
                                    Section {
                                        ForEach(sec.items) { item in gridItem(item, compact: compact) }
                                    } header: {
                                        sectionHeader(sec)
                                    }
                                }
                            }
                            .padding(.horizontal, 10)
                            listFooter
                            Color.clear.frame(height: 80)
                        }
                    }
                } else {
                    List {
                        Color.clear.frame(height: 0).id("inbox_top").inboxPlainRow()
                        ForEach(secs) { sec in
                            sectionHeader(sec).inboxPlainRow()
                            ForEach(sec.items) { item in listItem(item) }
                        }
                        listFooter.inboxPlainRow()
                        Color.clear.frame(height: 70).inboxPlainRow()
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .environment(\.defaultMinListRowHeight, 0)
                }
            }
            .refreshable { await repo.refresh() }
            // Kommt oben eine neue UNGELESENE Mail an: automatisch hochscrollen
            .onChange(of: topMailKey) { old, new in
                if old != nil, new != nil, old != new, repo.messages.first?.seen == false {
                    withAnimation { proxy.scrollTo("inbox_top", anchor: .top) }
                }
            }
            // Beim erneuten Öffnen der App nach oben, wenn Ungelesene da sind
            .onChange(of: scenePhase) { _, phase in
                if phase == .active && repo.messages.contains(where: { !$0.seen }) {
                    proxy.scrollTo("inbox_top", anchor: .top)
                }
            }
        }
    }

    @ViewBuilder
    private func sectionHeader(_ sec: InboxSection) -> some View {
        if sec.focusToolbar {
            focusToolbar
        } else if let t = sec.title {
            InboxSectionHeader(text: t)
        }
    }

    /// Kopfzeile der Fokus-Blöcke mit KI-Verfeinerungs-Knopf.
    private var focusToolbar: some View {
        HStack {
            Text(L("inbox_focus_grouped"))
                .font(.footnote)
                .foregroundStyle(palette.onSurfaceVariant)
                .frame(maxWidth: .infinity, alignment: .leading)
            if model.focusAiBusy {
                ProgressView().controlSize(.small).padding(.trailing, 12)
            } else {
                Button(model.focusAiDone ? L("inbox_focus_refine_again") : L("inbox_focus_refine")) {
                    model.refineFocusWithAi()
                }
                .font(.subheadline)
            }
        }
        .padding(.horizontal, 6)
        .padding(.top, 8)
    }

    @ViewBuilder
    private var listFooter: some View {
        if repo.canLoadMore && !repo.messages.isEmpty {
            // Wird das Lade-Icon sichtbar: nächstes Paket holen
            ProgressView()
                .controlSize(.regular)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 20)
                .task(id: repo.messages.count) { await repo.loadMore() }
        }
        if repo.messages.isEmpty && !repo.loading {
            Text(L("inbox_empty"))
                .font(.subheadline)
                .foregroundStyle(palette.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(48)
        }
    }

    private func gridColumns(_ compact: Bool) -> [GridItem] {
        [GridItem(.adaptive(minimum: compact ? 108 : 164), spacing: 10, alignment: .top)]
    }

    // MARK: Einträge

    @ViewBuilder
    private func gridItem(_ item: InboxItem, compact: Bool) -> some View {
        switch item {
        case .mail(let m, let inThread):
            block(m, compact: compact, selectable: true, inThread: inThread, onTap: { tapMail(m) })
        case .thread(let t, let expanded):
            let shown: MailMessage = {
                var m = t.newest
                m.seen = t.unread == 0
                return m
            }()
            InboxSwipeContainer(enabled: !model.selectionMode,
                                right: InboxSwipeSpec.forThread(prefs.swipeRightAction, t, model: model),
                                left: InboxSwipeSpec.forThread(prefs.swipeLeftAction, t, model: model),
                                onTap: { toggleThread(t) },
                                onLongPress: {}) {
                InboxMailBlock(mail: shown, threadCount: t.mails.count, threadExpanded: expanded, compact: compact)
            }
        }
    }

    private func block(_ m: MailMessage, compact: Bool, selectable: Bool, inThread: Bool = false,
                       onTap: @escaping () -> Void) -> some View {
        let isSelected = selectable && model.selected.contains(m.uid)
        let selMode = selectable && model.selectionMode
        return InboxSwipeContainer(enabled: !selMode,
                                   right: InboxSwipeSpec.forMail(prefs.swipeRightAction, m, model: model),
                                   left: InboxSwipeSpec.forMail(prefs.swipeLeftAction, m, model: model),
                                   onTap: onTap,
                                   onLongPress: { if selectable { model.toggleSelect(m.uid) } }) {
            InboxMailBlock(mail: m, selected: isSelected, selectionMode: selMode, inThread: inThread, compact: compact)
        }
    }

    @ViewBuilder
    private func listItem(_ item: InboxItem) -> some View {
        switch item {
        case .mail(let m, let inThread):
            listRow(m, selectable: true, inThread: inThread, onTap: { tapMail(m) })
        case .thread(let t, _):
            let shown: MailMessage = {
                var m = t.newest
                m.seen = t.unread == 0
                return m
            }()
            InboxMailRow(mail: shown, threadCount: t.mails.count)
                .onTapGesture { toggleThread(t) }
                .modifier(InboxRowSwipe(enabled: !model.selectionMode,
                                        right: InboxSwipeSpec.forThread(prefs.swipeRightAction, t, model: model),
                                        left: InboxSwipeSpec.forThread(prefs.swipeLeftAction, t, model: model)))
                .listRowInsets(EdgeInsets(top: prefs.plainDesign ? 1 : 3, leading: 10,
                                          bottom: prefs.plainDesign ? 1 : 3, trailing: 10))
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
        }
    }

    private func listRow(_ m: MailMessage, selectable: Bool, inThread: Bool = false,
                         right: InboxSwipeSpec? = nil, left: InboxSwipeSpec? = nil,
                         onTap: @escaping () -> Void) -> some View {
        let isSelected = selectable && model.selected.contains(m.uid)
        let selMode = selectable && model.selectionMode
        let r = right ?? InboxSwipeSpec.forMail(prefs.swipeRightAction, m, model: model)
        let l = left ?? InboxSwipeSpec.forMail(prefs.swipeLeftAction, m, model: model)
        let vpad: CGFloat = prefs.plainDesign ? 1 : 3
        return InboxMailRow(mail: m, selected: isSelected, selectionMode: selMode)
            .onTapGesture { onTap() }
            .onLongPressGesture(minimumDuration: 0.45) {
                guard selectable else { return }
                UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                model.toggleSelect(m.uid)
            }
            .modifier(InboxRowSwipe(enabled: !selMode, right: r, left: l))
            .listRowInsets(EdgeInsets(top: vpad, leading: 10 + (inThread ? 14 : 0), bottom: vpad, trailing: 10))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }

    // MARK: Schwebende Knöpfe

    @ViewBuilder
    private func composeFab(configured: Bool) -> some View {
        if configured && !model.selectionMode && !model.searchActive && model.aiAnswer == nil {
            Button {
                nav.compose = ComposeRequest()
            } label: {
                Image(systemName: "pencil")
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 56, height: 56)
                    .background(RoundedRectangle(cornerRadius: 16).fill(palette.accent))
                    .shadow(color: .black.opacity(0.25), radius: 4, y: 2)
            }
            .accessibilityLabel(L("inbox_compose"))
            .inboxTourTarget("fab")
            .padding(16)
        }
    }

    /// KI-Knopf unten links: Tages-Überblick & Co.
    @ViewBuilder
    private var aiFab: some View {
        if !model.selectionMode {
            Menu {
                Button { model.summarizeToday() } label: {
                    Label(L("inbox_ai_summarize_day"), systemImage: "sparkles")
                }
                Button { model.summarizeUnread() } label: {
                    Label(L("inbox_ai_summarize_unread"), systemImage: "envelope.badge")
                }
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 12).fill(palette.secondaryContainer)
                    if model.aiBusy {
                        ProgressView().controlSize(.small).tint(palette.onSecondaryContainer)
                    } else {
                        Image(systemName: "sparkles")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(palette.onSecondaryContainer)
                    }
                }
                .frame(width: 44, height: 44)
                .shadow(color: .black.opacity(0.2), radius: 3, y: 1)
            }
            .disabled(model.aiBusy)
            .accessibilityLabel(L("inbox_ai_functions"))
            .inboxTourTarget("aiFab")
            .padding(16)
        }
    }
}

// MARK: - Hilfs-Modifikatoren

/// Wischaktionen einer Listenzeile (links/rechts nach Einstellung).
struct InboxRowSwipe: ViewModifier {
    let enabled: Bool
    let right: InboxSwipeSpec
    let left: InboxSwipeSpec
    @Environment(\.palette) private var palette

    @ViewBuilder
    func body(content: Content) -> some View {
        if enabled {
            content
                .swipeActions(edge: .leading, allowsFullSwipe: true) { button(right) }
                .swipeActions(edge: .trailing, allowsFullSwipe: true) { button(left) }
        } else {
            content
        }
    }

    private func button(_ spec: InboxSwipeSpec) -> some View {
        Button(role: spec.destructive ? .destructive : nil) {
            spec.action()
        } label: {
            Label(spec.label, systemImage: spec.icon)
        }
        .tint(spec.destructive ? palette.error : palette.primary)
    }
}

extension View {
    /// Zeile ohne Trenner/Hintergrund/Innenabstand (Überschriften, Hinweise).
    func inboxPlainRow() -> some View {
        self
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}

// MARK: - Dialoge

/// Ergebnis-Dialog der KI-Zusammenfassung: Zeilen mit Mail-Bezug öffnen die Mail.
struct InboxSummarySheet: View {
    let result: InboxSummaryResult
    let onOpenMail: (MailMessage) -> Void
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(result.title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.onSurface)
                .padding(.bottom, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(result.lines) { line in
                        if line.isHeader {
                            Text(line.text)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(palette.primary)
                                .padding(.top, 10)
                                .padding(.bottom, 2)
                        } else {
                            row(line)
                        }
                    }
                    Text(L("inbox_ai_tap_hint"))
                        .font(.caption)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .padding(.top, 10)
                }
            }
            HStack {
                Spacer()
                Button(L("inbox_ok")) { dismiss() }
                    .font(.body.weight(.semibold))
            }
            .padding(.top, 10)
        }
        .padding(20)
        .background(palette.surface.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func row(_ line: InboxSummaryLine) -> some View {
        let content = HStack(alignment: .center, spacing: 0) {
            Text("•  ").font(.subheadline).foregroundStyle(palette.primary)
            VStack(alignment: .leading, spacing: 1) {
                Text(line.text)
                    .font(.subheadline)
                    .foregroundStyle(palette.onSurface)
                    .multilineTextAlignment(.leading)
                if let m = line.mail {
                    Text(m.from).font(.caption2).foregroundStyle(palette.onSurfaceVariant)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if line.mail != nil {
                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .accessibilityLabel(L("inbox_ai_open_mail"))
            }
        }
        .padding(.horizontal, 2)
        .padding(.vertical, 5)
        .contentShape(Rectangle())
        if let m = line.mail {
            Button { onOpenMail(m) } label: { content }
                .buttonStyle(.plain)
        } else {
            content
        }
    }
}

/// Liste der automatisch gespeicherten Entwürfe mit Fortsetzen/Löschen.
struct InboxDraftsSheet: View {
    let onOpen: (Int64) -> Void
    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(L("inbox_drafts_title"))
                .font(.title3.weight(.semibold))
                .foregroundStyle(palette.onSurface)
                .padding(.bottom, 8)
            if prefs.drafts.isEmpty {
                Text(L("inbox_drafts_empty"))
                    .font(.subheadline)
                    .foregroundStyle(palette.onSurfaceVariant)
                Spacer()
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(L("inbox_drafts_hint"))
                            .font(.footnote)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .padding(.bottom, 8)
                        ForEach(prefs.drafts) { d in
                            HStack {
                                Button { onOpen(d.id) } label: {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(d.subject.trimmingCharacters(in: .whitespaces).isEmpty
                                             ? L("inbox_drafts_no_subject") : d.subject)
                                            .font(.body.weight(.semibold))
                                            .foregroundStyle(palette.onSurface)
                                            .lineLimit(1)
                                        Text(subtitle(d))
                                            .font(.footnote)
                                            .foregroundStyle(palette.onSurfaceVariant)
                                            .lineLimit(1)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                Button { prefs.removeDraft(d.id) } label: {
                                    Image(systemName: "trash").foregroundStyle(palette.onSurfaceVariant)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(L("inbox_drafts_delete"))
                            }
                            .padding(.vertical, 6)
                        }
                    }
                }
            }
            HStack {
                Spacer()
                Button(L("inbox_close")) { dismiss() }
                    .font(.body.weight(.semibold))
            }
            .padding(.top, 10)
        }
        .padding(20)
        .background(palette.surface.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func subtitle(_ d: Prefs.Draft) -> String {
        var parts: [String] = []
        if !d.to.trimmingCharacters(in: .whitespaces).isEmpty { parts.append(L("inbox_drafts_to", d.to)) }
        parts.append(InboxFormat.draftDate(d.savedAt))
        return parts.joined(separator: " · ")
    }
}
