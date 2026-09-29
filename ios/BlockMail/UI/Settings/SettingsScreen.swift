import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// Einstellungen (Port von `SettingsScreen.kt`) — ohne Abo/Pro-Karte:
/// Stattdessen steht oben gut sichtbar der TestFlight-Hinweis, alle
/// Funktionen sind freigeschaltet.
struct SettingsScreen: View {
    @Environment(AppNav.self) private var nav
    @Environment(\.palette) private var palette

    @State private var ui = SettingsUIState()

    init() {}

    var body: some View {
        Form {
            // TestFlight-Hinweis statt Pro-Karte
            Section {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.seal.fill")
                        .font(.title2)
                        .foregroundStyle(palette.onPrimaryContainer)
                    Text(L("testflight_badge"))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(palette.onPrimaryContainer)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.vertical, 4)
                .listRowBackground(palette.primaryContainer)
            }

            SettingsAccountsSections(ui: ui)
            SettingsAppearanceSections()
            SettingsNotificationSections(ui: ui)
            SettingsRulesSections()
            SettingsContactsSection(ui: ui)
            SettingsWritingSections(ui: ui)
            SettingsAISections()
            SettingsDataSections(ui: ui)
            SettingsAboutSections(ui: ui)
        }
        .navigationTitle(L("settings_title"))
        .navigationBarTitleDisplayMode(.inline)
        .scrollDismissesKeyboard(.interactively)
        .sheet(item: $ui.sheet) { sheet in
            switch sheet {
            case .addAccount:
                AddAccountSheet { mail in
                    ui.snackbar.show(L("ios_account_switched", mail))
                }
            case .accountColor(let mail):
                AccountColorSheet(accountEmail: mail)
                    .presentationDetents([.medium])
            case .folders(let mail):
                FolderPickerSheet(accountEmail: mail)
            case .template:
                TemplateEditorSheet { ui.listsVersion += 1 }
            case .feedback:
                FeedbackSheet { ui.snackbar.show(L("settings_feedback_sent")) }
            }
        }
        .fileExporter(isPresented: $ui.showExporter, document: ui.exportDocument,
                      contentType: .json, defaultFilename: "blockmail-einstellungen.json") { result in
            switch result {
            case .success:
                ui.snackbar.show(L("settings_backup_saved"))
            case .failure(let error):
                ui.snackbar.show(L("settings_export_failed", error.localizedDescription))
            }
        }
        .fileImporter(isPresented: $ui.showImporter, allowedContentTypes: [.json, .data]) { result in
            importBackup(result)
        }
        .alert(L("settings_index_clear_title"), isPresented: $ui.confirmClearIndex) {
            Button(L("settings_index_clear"), role: .destructive) {
                Task { @MainActor in
                    await MailIndex.shared.clearAll()
                    ui.indexVersion += 1
                    ui.snackbar.show(L("settings_index_cleared"))
                }
            }
            Button(L("settings_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_index_clear_text"))
        }
        .snackbar(ui.snackbar)
    }

    private func importBackup(_ result: Result<URL, Error>) {
        switch result {
        case .success(let url):
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let data = try Data(contentsOf: url)
                guard let json = String(data: data, encoding: .utf8) else {
                    throw NSError(domain: "BlockMail", code: 2,
                                  userInfo: [NSLocalizedDescriptionKey: L("settings_file_unreadable")])
                }
                let count = try Prefs.shared.importSettingsJson(json)
                Notifier.registerCategories()
                PushRegistration.shared.syncSoon()
                ui.listsVersion += 1
                ui.snackbar.show(L("settings_import_count", count))
            } catch {
                ui.snackbar.show(L("settings_import_failed", error.localizedDescription))
            }
        case .failure(let error):
            ui.snackbar.show(L("settings_import_failed", error.localizedDescription))
        }
    }
}

// MARK: - Konten

/// „Konto verbinden“ + „Konten“ (Farben, Ordner, Standard-Absender).
struct SettingsAccountsSections: View {
    let ui: SettingsUIState

    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo
    @Environment(AppNav.self) private var nav
    @Environment(\.palette) private var palette

    @State private var editConnection = false
    @State private var googleBusy = false

    // Bearbeiten-Formular des aktiven Kontos
    @State private var providerId = "custom"
    @State private var email = ""
    @State private var password = ""
    @State private var loginUserField = ""
    @State private var imapHostField = ""
    @State private var imapPortField = ""
    @State private var smtpHostField = ""
    @State private var smtpPortField = ""

    private var googleConnected: Bool {
        prefs.authMethod == "oauth" && !prefs.refreshToken.isEmpty
    }

    var body: some View {
        let _ = prefs.accountsVersion
        let _ = prefs.accountColorsVersion
        let accountList = prefs.accounts()

        Section {
            Button {
                nav.push(.setup)
            } label: {
                Label(L("settings_setup_wizard_start"), systemImage: "wand.and.stars")
                    .font(.body.weight(.semibold))
            }
            SettingsHint(L("settings_setup_wizard_hint"))

            if googleConnected {
                HStack(spacing: 12) {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(palette.onPrimaryContainer)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("settings_google_connected"))
                            .font(.subheadline.weight(.semibold))
                        Text(prefs.email).font(.caption)
                    }
                    .foregroundStyle(palette.onPrimaryContainer)
                    Spacer()
                    Button(L("settings_disconnect")) {
                        GoogleAuth.signOut()
                        ui.snackbar.show(L("settings_google_disconnected_snack"))
                    }
                    .buttonStyle(.bordered)
                }
                .listRowBackground(palette.primaryContainer)
            }

            Button {
                editConnection = false
                ui.sheet = .addAccount
            } label: {
                Label(L("settings_add_account"), systemImage: "person.badge.plus")
            }

            if !googleConnected {
                if GoogleAuth.isConfigured {
                    Button {
                        googleSignIn()
                    } label: {
                        HStack {
                            Label(L("settings_google_signin"), systemImage: "person.crop.circle")
                            Spacer()
                            if googleBusy { ProgressView() }
                        }
                    }
                    .disabled(googleBusy)
                    SettingsHint(L("settings_google_signin_hint"))
                } else {
                    SettingsHint(L("ios_google_not_configured"))
                }
            }

            if !prefs.email.isEmpty {
                Text(L("settings_connected_as", prefs.email))
                Button(editConnection ? L("settings_edit_connection_close") : L("settings_edit_connection")) {
                    if !editConnection { loadFormFromPrefs() }
                    withAnimation { editConnection.toggle() }
                }
            }
        } header: {
            SettingsSectionHeader(title: L("settings_connect_title"), icon: "person.crop.circle",
                                  subtitle: L("settings_connect_subtitle"))
        }

        if editConnection && !googleConnected {
            editConnectionSection
        }

        Section {
            SettingsHint(L("settings_accounts_desc"))
            ForEach(accountList) { acc in
                accountRow(acc)
            }
            if accountList.count > 1 {
                VStack(alignment: .leading, spacing: 4) {
                    Text(L("settings_default_sender")).font(.subheadline.weight(.semibold))
                    SettingsHint(L("settings_default_sender_desc"))
                }
                Picker(L("settings_send_new_via"), selection: prefs.settingsBinding(\.defaultSendAccount)) {
                    Text(L("settings_active_account")).tag("")
                    ForEach(accountList) { acc in
                        Text(acc.email).tag(acc.email)
                    }
                }
            }
        } header: {
            SettingsSectionHeader(title: L("settings_accounts_title"), icon: "person.2",
                                  subtitle: L("settings_accounts_subtitle"))
        }
    }

    // MARK: Konto-Zeile

    @ViewBuilder
    private func accountRow(_ acc: Prefs.Account) -> some View {
        let active = acc.email.caseInsensitiveCompare(prefs.email) == .orderedSame
        let dot = prefs.accountColor(acc.email)
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Circle()
                    .fill(dot.map { SettingsFormat.color($0) } ?? palette.surfaceContainerHigh)
                    .overlay(Circle().stroke(palette.outlineVariant, lineWidth: dot == nil ? 1 : 0))
                    .frame(width: 22, height: 22)
                Text(active ? L("settings_account_active", acc.email) : acc.email)
                    .foregroundStyle(active ? palette.primary : palette.onSurface)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Spacer()
                if !active {
                    Button(L("ios_account_switch")) {
                        Task { @MainActor in
                            await repo.switchAccount(acc)
                            AccountActions.restartPush()
                            ui.snackbar.show(L("ios_account_switched", acc.email))
                        }
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    Button(role: .destructive) {
                        prefs.removeAccount(acc.email)
                        if prefs.defaultSendAccount.caseInsensitiveCompare(acc.email) == .orderedSame {
                            prefs.defaultSendAccount = ""
                        }
                        AccountActions.restartPush()
                        ui.snackbar.show(L("ios_account_removed", acc.email))
                    } label: {
                        Image(systemName: "xmark.circle")
                    }
                    .accessibilityLabel(L("settings_account_remove"))
                }
            }
            HStack(spacing: 8) {
                Button {
                    ui.sheet = .accountColor(acc.email)
                } label: {
                    Label(L("settings_choose_color"), systemImage: "paintpalette")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
                Button {
                    ui.sheet = .folders(acc.email)
                } label: {
                    Label(L("settings_visible_folders"), systemImage: "folder")
                        .font(.caption)
                }
                .buttonStyle(.bordered)
                .controlSize(.small)
            }
            .padding(.leading, 32)
        }
        .buttonStyle(.borderless)
        .padding(.vertical, 4)
    }

    // MARK: Zugangsdaten bearbeiten

    private var editConnectionSection: some View {
        let provider = MailProviderPreset.byId(providerId)
        return Section {
            Picker(L("settings_provider"), selection: $providerId) {
                ForEach(MailProviderPreset.all) { p in
                    Text(p.displayLabel).tag(p.id)
                }
            }
            .onChange(of: providerId) { _, newId in
                let p = MailProviderPreset.byId(newId)
                if !p.imap.isEmpty {
                    imapHostField = p.imap
                    imapPortField = String(p.imapPort)
                    smtpHostField = p.smtp
                    smtpPortField = String(p.smtpPort)
                }
            }
            TextField(L("settings_email_address"), text: $email)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            SecureField(L("settings_password_label"), text: $password)
            VStack(alignment: .leading, spacing: 4) {
                TextField(L("setup_login_user"), text: $loginUserField)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SettingsHint(L("setup_login_user_hint"))
            }
            if provider.isCustom {
                HStack {
                    TextField(L("settings_imap_server"), text: $imapHostField)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField(L("settings_port"), text: $imapPortField)
                        .keyboardType(.numberPad)
                        .frame(width: 70)
                }
                HStack {
                    TextField(L("settings_smtp_server"), text: $smtpHostField)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                    TextField(L("settings_port"), text: $smtpPortField)
                        .keyboardType(.numberPad)
                        .frame(width: 70)
                }
                SettingsHint(L("settings_smtp_port_hint"))
            }
            if providerId == "gmail", let url = URL(string: "https://myaccount.google.com/apppasswords") {
                Link(destination: url) {
                    Label(L("settings_gmail_app_password"), systemImage: "arrow.up.right.square")
                }
            }
            Button {
                saveConnection()
            } label: {
                Text(L("settings_save")).frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .disabled(email.trimmingCharacters(in: .whitespaces).isEmpty ||
                      password.trimmingCharacters(in: .whitespaces).isEmpty)
        } header: {
            Text(L("settings_manual"))
        }
    }

    private func loadFormFromPrefs() {
        email = prefs.email
        password = prefs.appPassword
        loginUserField = prefs.loginUser
        imapHostField = prefs.imapHost
        imapPortField = String(prefs.imapPort)
        smtpHostField = prefs.smtpHost
        smtpPortField = String(prefs.smtpPort)
        providerId = MailProviderPreset.idFor(imapHost: prefs.imapHost)
    }

    private func saveConnection() {
        let mail = email, pw = password, user = loginUserField
        let iHost = imapHostField, sHost = smtpHostField
        let iPort = Int(imapPortField.trimmingCharacters(in: .whitespaces)) ?? 993
        let sPort = Int(smtpPortField.trimmingCharacters(in: .whitespaces)) ?? 465
        editConnection = false
        Task { @MainActor in
            await AccountActions.activatePasswordAccount(
                email: mail, password: pw, loginUser: user,
                imapHost: iHost, imapPort: iPort, smtpHost: sHost, smtpPort: sPort)
            ui.snackbar.show(L("settings_saved"))
        }
    }

    private func googleSignIn() {
        googleBusy = true
        Task { @MainActor in
            do {
                let mail = try await AccountActions.signInWithGoogle()
                googleBusy = false
                editConnection = false
                ui.snackbar.show(L("settings_google_connected_snack", mail))
            } catch {
                googleBusy = false
                ui.snackbar.show(L("settings_auth_failed", error.localizedDescription))
            }
        }
    }
}

// MARK: - Konto-Farbe

/// Auswahl der Konto-Farbe (Balken vorne an den Mail-Karten).
struct AccountColorSheet: View {
    let accountEmail: String

    @Environment(Prefs.self) private var prefs
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    /// Wählbare Konto-Farben (wie Android `accountPalette`).
    static let colors: [UInt32] = [
        0xFFE53935, 0xFFFB8C00, 0xFFFBC02D, 0xFF43A047,
        0xFF00ACC1, 0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60
    ]

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text(accountEmail)
                    .font(.caption)
                    .foregroundStyle(palette.onSurfaceVariant)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 4), spacing: 14) {
                    ForEach(Self.colors, id: \.self) { c in
                        let value = Int(Int32(bitPattern: c))
                        Button {
                            prefs.setAccountColor(accountEmail, value)
                            dismiss()
                        } label: {
                            Circle()
                                .fill(Color(argb: c))
                                .frame(width: 44, height: 44)
                                .overlay {
                                    if prefs.accountColor(accountEmail) == value {
                                        Image(systemName: "checkmark")
                                            .font(.headline)
                                            .foregroundStyle(Color.white)
                                    }
                                }
                        }
                        .buttonStyle(.plain)
                    }
                }
                Spacer()
            }
            .padding(20)
            .navigationTitle(L("settings_account_color_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("settings_cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("settings_no_color")) {
                        prefs.setAccountColor(accountEmail, nil)
                        dismiss()
                    }
                }
            }
        }
    }
}

// MARK: - Sichtbare Ordner

/// Sichtbare Standard-Ordner und zusätzliche Server-Ordner je Konto.
struct FolderPickerSheet: View {
    let accountEmail: String

    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    @State private var serverFolders: [String]?
    @State private var serverError: String?

    private var isActive: Bool {
        accountEmail.caseInsensitiveCompare(prefs.email) == .orderedSame
    }

    var body: some View {
        let _ = prefs.hiddenFoldersVersion
        let hidden = prefs.hiddenFolders(accountEmail)
        let extra = prefs.extraFolders(accountEmail)

        NavigationStack {
            Form {
                Section {
                    ForEach(MailFolder.allCases.filter { $0 != .INBOX }, id: \.self) { f in
                        Toggle(f.label, isOn: Binding(
                            get: { !hidden.contains(f.rawValue) },
                            set: { show in
                                var next = prefs.hiddenFolders(accountEmail)
                                if show { next.remove(f.rawValue) } else { next.insert(f.rawValue) }
                                prefs.setHiddenFolders(accountEmail, next)
                                // Wird der gerade geöffnete Ordner ausgeblendet → zurück in den Posteingang
                                if !show && isActive && repo.currentFolder == f && repo.customFolder == nil {
                                    Task { @MainActor in await repo.switchFolder(.INBOX) }
                                }
                            }
                        ))
                    }
                } header: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(accountEmail).textCase(nil)
                        Text(L("settings_visible_folders_desc")).textCase(nil)
                    }
                }

                Section {
                    SettingsHint(L("settings_more_folders_desc"))
                    if let serverError {
                        Text(L("settings_more_folders_failed", serverError))
                            .font(.caption)
                            .foregroundStyle(palette.error)
                    } else if let folders = serverFolders {
                        if folders.isEmpty {
                            SettingsHint(L("settings_more_folders_none"))
                        } else {
                            ForEach(folders, id: \.self) { path in
                                Toggle(ModifiedUTF7.decode(path), isOn: Binding(
                                    get: { extra.contains(path) },
                                    set: { show in
                                        var next = prefs.extraFolders(accountEmail)
                                        if show { next.insert(path) } else { next.remove(path) }
                                        prefs.setExtraFolders(accountEmail, next)
                                        if !show && isActive && repo.customFolder == path {
                                            Task { @MainActor in await repo.switchFolder(.INBOX) }
                                        }
                                    }
                                ))
                            }
                        }
                    } else {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(L("settings_more_folders_loading")).font(.caption)
                        }
                    }
                } header: {
                    Text(L("settings_more_folders"))
                }
            }
            .navigationTitle(L("settings_visible_folders"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("settings_done")) { dismiss() }
                }
            }
            .task(id: accountEmail) {
                do {
                    let all = try await repo.listServerFolders(accountEmail)
                    serverFolders = all.filter { !repo.isStandardFolderName($0) }
                } catch {
                    serverError = repo.friendlyError(error)
                    serverFolders = []
                }
            }
        }
    }
}
