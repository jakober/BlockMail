import SwiftUI

/// Eigenständiges Popup „Konto hinzufügen“ (Port von `AddAccountDialog.kt`):
/// Anbieter wählen (oder eigene Server eintragen), Zugangsdaten samt
/// optionalem abweichendem Anmeldenamen eingeben, Verbindung testen — erst
/// bei Erfolg wird das Konto gespeichert und aktiviert. Bei Gmail zusätzlich
/// die Google-Anmeldung (falls eine iOS-Client-ID hinterlegt ist).
struct AddAccountSheet: View {
    /// Aufgerufen, nachdem das neue Konto aktiviert wurde.
    var onDone: (String) -> Void

    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette

    @State private var providerId = "gmail"
    @State private var email = ""
    @State private var password = ""
    @State private var loginUser = ""
    @State private var imapHost = ""
    @State private var imapPort = "993"
    @State private var smtpHost = ""
    @State private var smtpPort = "587"
    @State private var testing = false
    @State private var googleBusy = false
    @State private var error: String?

    private var provider: MailProviderPreset { MailProviderPreset.byId(providerId) }

    private var resolvedImapHost: String {
        provider.isCustom ? imapHost.trimmingCharacters(in: .whitespaces) : provider.imap
    }
    private var resolvedImapPort: Int {
        provider.isCustom ? (Int(imapPort.trimmingCharacters(in: .whitespaces)) ?? 993) : provider.imapPort
    }
    private var resolvedSmtpHost: String {
        provider.isCustom ? smtpHost.trimmingCharacters(in: .whitespaces) : provider.smtp
    }
    private var resolvedSmtpPort: Int {
        provider.isCustom ? (Int(smtpPort.trimmingCharacters(in: .whitespaces)) ?? 587) : provider.smtpPort
    }

    private var canConnect: Bool {
        !testing && !googleBusy && email.contains("@") && !password.trimmingCharacters(in: .whitespaces).isEmpty &&
            (!provider.isCustom || (!imapHost.trimmingCharacters(in: .whitespaces).isEmpty &&
                                    !smtpHost.trimmingCharacters(in: .whitespaces).isEmpty))
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Picker(L("settings_provider"), selection: $providerId) {
                        ForEach(MailProviderPreset.all) { p in
                            Text(p.displayLabel).tag(p.id)
                        }
                    }
                }

                if providerId == "gmail" {
                    Section {
                        if GoogleAuth.isConfigured {
                            Button {
                                googleSignIn()
                            } label: {
                                HStack {
                                    Image(systemName: "person.crop.circle.badge.checkmark")
                                    Text(L("settings_google_signin"))
                                    Spacer()
                                    if googleBusy { ProgressView() }
                                }
                            }
                            .disabled(testing || googleBusy)
                            SettingsHint(L("ios_setup_google_alt"))
                        } else {
                            SettingsHint(L("ios_google_not_configured"))
                        }
                    }
                }

                Section {
                    TextField(L("settings_email_address"), text: $email)
                        .keyboardType(.emailAddress)
                        .textContentType(.emailAddress)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    SecureField(L("settings_password_label"), text: $password)
                        .textContentType(.password)
                    VStack(alignment: .leading, spacing: 4) {
                        TextField(L("setup_login_user"), text: $loginUser)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SettingsHint(L("setup_login_user_hint"))
                    }
                }

                if provider.isCustom {
                    Section {
                        HStack {
                            TextField(L("settings_imap_server"), text: $imapHost)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keyboardType(.URL)
                            TextField(L("settings_port"), text: $imapPort)
                                .keyboardType(.numberPad)
                                .frame(width: 70)
                        }
                        HStack {
                            TextField(L("settings_smtp_server"), text: $smtpHost)
                                .textInputAutocapitalization(.never)
                                .autocorrectionDisabled()
                                .keyboardType(.URL)
                            TextField(L("settings_port"), text: $smtpPort)
                                .keyboardType(.numberPad)
                                .frame(width: 70)
                        }
                        SettingsHint(L("settings_smtp_port_hint"))
                    }
                }

                if let error {
                    Section {
                        Text(error)
                            .foregroundStyle(palette.error)
                    }
                }

                if testing {
                    Section {
                        HStack(spacing: 10) {
                            ProgressView()
                            Text(L("setup_testing"))
                        }
                    }
                }
            }
            .navigationTitle(L("add_account_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("settings_cancel")) { dismiss() }
                        .disabled(testing || googleBusy)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("add_account_connect")) { connectAndSave() }
                        .disabled(!canConnect)
                }
            }
        }
        // Eingaben dürfen nicht durch versehentliches Wegwischen verloren gehen
        .interactiveDismissDisabled(true)
    }

    private func connectAndSave() {
        guard canConnect else { return }
        testing = true
        error = nil
        let mail = email.trimmingCharacters(in: .whitespaces)
        let pw = password
        let user = loginUser.trimmingCharacters(in: .whitespaces)
        let iHost = resolvedImapHost, iPort = resolvedImapPort
        let sHost = resolvedSmtpHost, sPort = resolvedSmtpPort
        Task { @MainActor in
            let err = await MailRepository.shared.testConnection(
                email: mail, password: pw, host: iHost, port: iPort, loginUser: user)
            if let err {
                testing = false
                error = L("setup_connection_failed", err)
                return
            }
            await AccountActions.activatePasswordAccount(
                email: mail, password: pw, loginUser: user,
                imapHost: iHost, imapPort: iPort, smtpHost: sHost, smtpPort: sPort)
            testing = false
            onDone(mail)
            dismiss()
        }
    }

    private func googleSignIn() {
        googleBusy = true
        error = nil
        Task { @MainActor in
            do {
                let mail = try await AccountActions.signInWithGoogle()
                googleBusy = false
                onDone(mail)
                dismiss()
            } catch {
                googleBusy = false
                self.error = L("settings_auth_failed", error.localizedDescription)
            }
        }
    }
}
