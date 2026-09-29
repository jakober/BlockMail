import SwiftUI
import UIKit

// MARK: - Schreiben

/// „Schreiben“: PDF erstellen, Signatur, Textvorlagen, Entwürfe, Ausgang.
struct SettingsWritingSections: View {
    let ui: SettingsUIState

    @Environment(Prefs.self) private var prefs
    @Environment(AppNav.self) private var nav
    @Environment(\.palette) private var palette

    var body: some View {
        let _ = ui.listsVersion
        let templates = prefs.mailTemplates()

        Section {
            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_documents"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(palette.primary)
                SettingsHint(L("settings_documents_desc"))
            }
            Button {
                nav.showNewPdf = true
            } label: {
                Label(L("inbox_new_pdf"), systemImage: "doc.badge.plus")
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_signature"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(palette.primary)
                SettingsHint(L("settings_signature_desc"))
            }
            TextField(L("settings_signature_label"), text: prefs.settingsBinding(\.signature), axis: .vertical)
                .lineLimit(2...8)

            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_templates"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(palette.primary)
                SettingsHint(L("settings_templates_desc"))
            }
            if templates.isEmpty {
                SettingsHint(L("settings_templates_empty"))
            }
            ForEach(0..<templates.count, id: \.self) { i in
                HStack {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(templates[i].0)
                        Text(templates[i].1)
                            .font(.caption)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button {
                        var list = prefs.mailTemplates()
                        if list.indices.contains(i) { list.remove(at: i) }
                        prefs.saveMailTemplates(list)
                        ui.listsVersion += 1
                    } label: {
                        Image(systemName: "xmark.circle")
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("settings_template_delete"))
                }
            }
            Button {
                ui.sheet = .template
            } label: {
                Label(L("settings_template_add"), systemImage: "plus")
            }
        } header: {
            SettingsSectionHeader(title: L("settings_signature_title"), icon: "pencil",
                                  subtitle: L("settings_signature_subtitle"))
        }

        // Entwürfe
        Section {
            if prefs.drafts.isEmpty {
                SettingsHint(L("inbox_drafts_empty"))
            }
            ForEach(prefs.drafts) { d in
                HStack {
                    Button {
                        nav.compose = ComposeRequest(draftId: d.id)
                    } label: {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(d.subject.isEmpty ? L("inbox_drafts_no_subject") : d.subject)
                                .foregroundStyle(palette.onSurface)
                                .lineLimit(1)
                            if !d.to.isEmpty {
                                Text(L("inbox_drafts_to", d.to))
                                    .font(.caption)
                                    .foregroundStyle(palette.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            Text(SettingsFormat.dateTime(d.savedAt))
                                .font(.caption2)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    .accessibilityHint(L("ios_drafts_open"))
                    Spacer()
                    Button {
                        prefs.removeDraft(d.id)
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .accessibilityLabel(L("inbox_drafts_delete"))
                }
                .buttonStyle(.borderless)
            }
            SettingsHint(L("inbox_drafts_hint"))
        } header: {
            SettingsSectionHeader(title: L("inbox_drafts_title"), icon: "doc.text")
        }

        // Geplante Mails
        Section {
            let _ = prefs.outboxVersion
            let outbox = prefs.outbox().sorted { $0.sendAt < $1.sendAt }
            if outbox.isEmpty {
                SettingsHint(L("ios_outbox_empty"))
            }
            ForEach(outbox) { m in
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(m.subject.isEmpty ? L("inbox_drafts_no_subject") : m.subject)
                            .lineLimit(1)
                        Text(L("inbox_drafts_to", m.to))
                            .font(.caption)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .lineLimit(1)
                        Text(L("ios_outbox_at", SettingsFormat.dateTime(m.sendAt)))
                            .font(.caption2)
                            .foregroundStyle(palette.primary)
                        if !m.account.isEmpty {
                            Text(m.account)
                                .font(.caption2)
                                .foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    Spacer()
                    Button {
                        sendNow(m.id)
                    } label: {
                        Image(systemName: "paperplane")
                    }
                    .accessibilityLabel(L("ios_outbox_send_now"))
                    Button {
                        prefs.removeOutbox(m.id)
                        ui.snackbar.show(L("ios_outbox_deleted"))
                    } label: {
                        Image(systemName: "trash")
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .accessibilityLabel(L("ios_outbox_delete"))
                }
                .buttonStyle(.borderless)
            }
        } header: {
            SettingsSectionHeader(title: L("ios_outbox_title"), icon: "clock.arrow.circlepath")
        }
    }

    private func sendNow(_ id: Int64) {
        prefs.saveOutbox(prefs.outbox().map { o in
            var c = o
            if o.id == id { c.sendAt = nowMs() }
            return c
        })
        Task { @MainActor in await MailChecker.processOutboxNow() }
    }
}

/// Neue Textvorlage anlegen.
struct TemplateEditorSheet: View {
    var onSaved: () -> Void

    @Environment(Prefs.self) private var prefs
    @Environment(\.dismiss) private var dismiss

    @State private var title = ""
    @State private var text = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField(L("settings_template_title"), text: $title)
                TextField(L("settings_template_text"), text: $text, axis: .vertical)
                    .lineLimit(3...12)
            }
            .navigationTitle(L("settings_template_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("settings_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("settings_save")) {
                        prefs.saveMailTemplates(prefs.mailTemplates() +
                                                [(title.trimmingCharacters(in: .whitespaces), text)])
                        onSaved()
                        dismiss()
                    }
                    .disabled(title.trimmingCharacters(in: .whitespaces).isEmpty ||
                              text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

// MARK: - KI

/// „Künstliche Intelligenz“: eigener Claude-Schlüssel, KI-Wahl, Antwort-Radar.
struct SettingsAISections: View {
    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette

    private var activeAI: String {
        let key = prefs.claudeApiKey
        let engine = prefs.aiEngine
        let wantsApple = engine == "apple" || engine == "gemini" || (engine == "auto" && key.isEmpty)
        if wantsApple && AppleAI.isAvailable { return L("ios_ai_engine_apple") }
        if !key.isEmpty { return L("settings_ai_claude_active") }
        return L("ios_ai_none")
    }

    var body: some View {
        Section {
            Text(L("settings_ai_active", activeAI))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.primary)

            VStack(alignment: .leading, spacing: 6) {
                Text(L("ios_claude_key")).font(.subheadline.weight(.semibold))
                SecureField(L("settings_claude_key_label"), text: prefs.settingsBinding(\.claudeApiKey))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.password)
                SettingsHint(L("ios_claude_key_hint"))
            }
            if let url = URL(string: "https://console.anthropic.com/settings/keys") {
                Link(destination: url) {
                    Label("console.anthropic.com", systemImage: "arrow.up.right.square")
                }
            }

            Picker(L("ios_ai_engine_title"), selection: Binding(
                get: { prefs.aiEngine == "gemini" ? "apple" : prefs.aiEngine },
                set: { prefs.aiEngine = $0 }
            )) {
                Text(L("settings_ai_mode_auto")).tag("auto")
                Text(L("settings_ai_mode_claude")).tag("claude")
                Text(L("ios_ai_engine_apple")).tag("apple")
            }
            .pickerStyle(.inline)

            SettingsToggleRow(title: L("settings_radar_title"), desc: L("settings_radar_desc"),
                              isOn: prefs.settingsBinding(\.radarEnabled))
        } header: {
            SettingsSectionHeader(title: L("settings_group_ai"), icon: "sparkles",
                                  subtitle: L("settings_claude_key_subtitle"))
        }
    }
}

// MARK: - Daten

/// Suchindex und Sicherung.
struct SettingsDataSections: View {
    let ui: SettingsUIState

    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette

    @State private var stats: MailIndex.IndexStats?

    private static let yearOptions: [(Int, String)] = [
        (1, "settings_index_years_1"), (2, "settings_index_years_2"),
        (5, "settings_index_years_5"), (0, "settings_index_years_all")
    ]

    var body: some View {
        let index = MailIndex.shared
        Section {
            SettingsHint(L("settings_index_desc"))
                .task(id: "\(index.buildRunning)-\(index.buildProgress)-\(ui.indexVersion)") {
                    stats = await MailIndex.shared.stats()
                }
            if let stats {
                Text(stats.mailCount == 0
                     ? L("settings_index_empty")
                     : L("settings_index_status", stats.mailCount, SettingsFormat.dbSize(stats.dbBytes)))
                    .font(.subheadline)
            }
            Toggle(L("settings_index_auto"), isOn: prefs.settingsBinding(\.indexEnabled))
            Picker(L("settings_index_years"), selection: prefs.settingsBinding(\.indexYears)) {
                ForEach(0..<Self.yearOptions.count, id: \.self) { i in
                    Text(L(Self.yearOptions[i].1)).tag(Self.yearOptions[i].0)
                }
            }
            if index.buildRunning {
                HStack(spacing: 10) {
                    ProgressView()
                    Text(index.buildTotal > 0
                         ? L("settings_index_building", index.buildProgress, index.buildTotal)
                         : L("settings_index_building_unknown", index.buildProgress))
                        .font(.subheadline)
                    Spacer()
                    Button(L("settings_cancel")) { MailIndex.shared.cancelBuild() }
                        .buttonStyle(.bordered)
                }
                if index.buildTotal > 0 {
                    ProgressView(value: Double(min(index.buildProgress, index.buildTotal)),
                                 total: Double(index.buildTotal))
                }
            } else {
                Button {
                    Task { @MainActor in
                        await MailIndex.shared.fullBuild()
                        stats = await MailIndex.shared.stats()
                    }
                } label: {
                    Label(L("settings_index_build"), systemImage: "bolt")
                }
                .disabled(!prefs.isConfigured)
            }
            SettingsHint(L("settings_index_build_hint"))
            Button(L("settings_index_clear"), role: .destructive) {
                ui.confirmClearIndex = true
            }
            .disabled(index.buildRunning)
        } header: {
            SettingsSectionHeader(title: L("settings_index_title"), icon: "doc.text.magnifyingglass",
                                  subtitle: L("settings_index_subtitle"))
        }

        Section {
            SettingsHint(L("settings_backup_desc"))
            HStack {
                Button {
                    ui.exportDocument = SettingsBackupDocument(text: prefs.exportSettingsJson())
                    ui.showExporter = true
                } label: {
                    Label(L("settings_backup_export"), systemImage: "square.and.arrow.up")
                }
                Spacer()
                Button {
                    ui.showImporter = true
                } label: {
                    Label(L("settings_backup_import"), systemImage: "square.and.arrow.down")
                }
            }
            .buttonStyle(.borderless)
        } header: {
            SettingsSectionHeader(title: L("settings_backup_title"), icon: "arrow.up.arrow.down.square",
                                  subtitle: L("settings_backup_subtitle"))
        }
    }
}

// MARK: - Feedback, Tour, Datenschutz, Version

struct SettingsAboutSections: View {
    let ui: SettingsUIState

    @Environment(Prefs.self) private var prefs
    @Environment(AppNav.self) private var nav
    @Environment(\.palette) private var palette

    var body: some View {
        Section {
            SettingsHint(L("settings_feedback_desc"))
            Button {
                if prefs.isConfigured {
                    ui.sheet = .feedback
                } else {
                    ui.snackbar.show(L("settings_feedback_connect_first"))
                }
            } label: {
                Label(L("settings_feedback_write"), systemImage: "envelope")
            }
            Button {
                // Die Tour läuft im Posteingang — dorthin zurück und neu starten
                prefs.tourShown = false
                nav.popToRoot()
                NotificationCenter.default.post(name: .settingsRequestTour, object: nil)
            } label: {
                Label(L("settings_tour"), systemImage: "questionmark.circle")
            }
        } header: {
            SettingsSectionHeader(title: L("settings_feedback_title"), icon: "bubble.left.and.bubble.right",
                                  subtitle: L("settings_feedback_subtitle"))
        }

        Section {
            Text(L("ios_privacy_text"))
                .font(.footnote)
                .foregroundStyle(palette.onSurfaceVariant)
                .fixedSize(horizontal: false, vertical: true)
        } header: {
            SettingsSectionHeader(title: L("ios_privacy_title"), icon: "lock.shield")
        } footer: {
            VStack(spacing: 4) {
                Text(L("settings_version", SettingsFormat.appVersion))
                Text(L("testflight_badge"))
            }
            .font(.caption)
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
            .padding(.top, 12)
        }
    }
}

/// Feedback an den Entwickler (über das eigene Mail-Konto).
struct FeedbackSheet: View {
    var onSent: () -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    @State private var text = ""
    @State private var sending = false
    @State private var error: String?

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    SettingsHint(L("settings_feedback_dialog_desc"))
                    TextField(L("settings_feedback_placeholder"), text: $text, axis: .vertical)
                        .lineLimit(6...16)
                }
                if let error {
                    Section {
                        Text(L("settings_feedback_send_failed", error))
                            .foregroundStyle(palette.error)
                    }
                }
            }
            .navigationTitle(L("settings_feedback_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("settings_cancel")) { dismiss() }
                        .disabled(sending)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(sending ? L("settings_feedback_sending") : L("settings_feedback_send")) {
                        send()
                    }
                    .disabled(sending || text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
            .interactiveDismissDisabled(sending)
        }
    }

    private func send() {
        sending = true
        error = nil
        let version = SettingsFormat.appVersion
        let body = text.trimmingCharacters(in: .whitespacesAndNewlines) + "\n\n" +
            L("ios_feedback_device_info", version, UIDevice.current.systemVersion, Self.deviceModel())
        Task { @MainActor in
            do {
                _ = try await MailRepository.shared.send(
                    to: "mat.jakober@gmail.com",
                    subject: L("settings_feedback_subject", version),
                    body: body)
                sending = false
                onSent()
                dismiss()
            } catch {
                sending = false
                self.error = MailRepository.shared.friendlyError(error)
            }
        }
    }

    /// Geräte-Kennung (z. B. „iPhone15,2“).
    private static func deviceModel() -> String {
        var info = utsname()
        uname(&info)
        let mirror = Mirror(reflecting: info.machine)
        let id = mirror.children.reduce(into: "") { acc, el in
            if let v = el.value as? Int8, v != 0 { acc.append(Character(UnicodeScalar(UInt8(v)))) }
        }
        return id.isEmpty ? UIDevice.current.model : id
    }
}
