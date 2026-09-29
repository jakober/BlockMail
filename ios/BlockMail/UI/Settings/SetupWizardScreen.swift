import SwiftUI

/// Einrichtungsassistent (Port von `SetupWizardScreen.kt`): Anbieter wählen,
/// E-Mail + Passwort eingeben — Server und Ports setzt die App selbst, die
/// Verbindung wird vor dem Speichern getestet.
struct SetupWizardScreen: View {
    @Environment(AppNav.self) private var nav
    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette
    @Environment(\.openURL) private var openURL

    @State private var provider: MailProviderPreset?
    @State private var email = ""
    @State private var password = ""
    @State private var passwordAutoFilled = false
    @State private var loginUser = ""
    @State private var testing = false
    @State private var googleBusy = false
    @State private var error: String?
    @State private var showCustom = false

    init() {}

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let p = provider {
                    providerForm(p)
                } else {
                    providerList
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(palette.background)
        .navigationTitle(provider?.label ?? L("setup_title"))
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(true)
        .toolbar {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    if provider != nil {
                        provider = nil
                        error = nil
                    } else {
                        nav.pop()
                    }
                } label: {
                    Image(systemName: "chevron.backward")
                }
                .accessibilityLabel(L("setup_back"))
                .disabled(testing || googleBusy)
            }
        }
        .sheet(isPresented: $showCustom) {
            AddAccountSheet { _ in finish() }
        }
    }

    // MARK: Anbieter-Auswahl

    private var providerList: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L("setup_choose_provider"))
                .font(.title3.weight(.semibold))
            Text(L("setup_choose_provider_desc"))
                .font(.caption)
                .foregroundStyle(palette.onSurfaceVariant)
                .padding(.bottom, 4)
            ForEach(MailProviderPreset.setup) { sp in
                Button {
                    provider = sp
                    error = nil
                    password = ""
                    passwordAutoFilled = false
                } label: {
                    HStack(spacing: 12) {
                        Image(systemName: "envelope.fill")
                            .foregroundStyle(palette.primary)
                        Text(sp.label)
                            .foregroundStyle(palette.onSurface)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .font(.caption)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .padding(16)
                    .frame(maxWidth: .infinity)
                    .background(RoundedRectangle(cornerRadius: 16).fill(palette.surfaceContainer))
                }
                .buttonStyle(.plain)
            }
            // Eigener Anbieter: gleiches Popup wie in den Einstellungen
            Button(L("setup_other_provider")) { showCustom = true }
                .padding(.top, 4)
        }
    }

    // MARK: Zugangsdaten

    @ViewBuilder
    private func providerForm(_ p: MailProviderPreset) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L(p.noteKey))
                .font(.subheadline)
                .foregroundStyle(palette.onSecondaryContainer)
                .fixedSize(horizontal: false, vertical: true)
            if let s = p.helpURL, let url = URL(string: s) {
                Button {
                    openURL(url)
                } label: {
                    Label(L(p.helpLabelKey ?? "setup_help_open"), systemImage: "arrow.up.right.square")
                        .font(.subheadline.weight(.semibold))
                }
            }
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(palette.secondaryContainer.opacity(0.5)))

        if p.id == "gmail" && GoogleAuth.isConfigured {
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    googleSignIn()
                } label: {
                    HStack {
                        if googleBusy { ProgressView().tint(palette.onPrimary) }
                        else { Image(systemName: "person.crop.circle.badge.checkmark") }
                        Text(L("settings_google_signin"))
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(testing || googleBusy)
                Text(L("ios_setup_google_alt"))
                    .font(.caption)
                    .foregroundStyle(palette.onSurfaceVariant)
            }
        }

        field {
            TextField(L("setup_email_address"), text: $email)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
        }
        HStack(spacing: 8) {
            field {
                SecureField(L(p.passwordLabelKey), text: Binding(
                    get: { password },
                    set: { password = $0; passwordAutoFilled = false }
                ))
                .textContentType(.password)
            }
            // Einfügen ohne Nachfrage des Systems (z. B. frisch erzeugtes App-Passwort)
            PasteButton(payloadType: String.self) { strings in
                guard let raw = strings.first else { return }
                let clip = raw.trimmingCharacters(in: .whitespacesAndNewlines)
                let compact = clip.replacingOccurrences(of: " ", with: "")
                let isAppPassword = compact.count == 16 && compact.allSatisfy { $0.isASCII && $0.isLetter }
                Task { @MainActor in
                    password = isAppPassword ? compact : clip
                    passwordAutoFilled = isAppPassword
                }
            }
            .labelStyle(.iconOnly)
            .buttonBorderShape(.roundedRectangle)
        }
        if passwordAutoFilled {
            Label(L("setup_pw_autofilled"), systemImage: "checkmark.circle.fill")
                .font(.caption)
                .foregroundStyle(palette.primary)
        }
        VStack(alignment: .leading, spacing: 4) {
            field {
                TextField(L("setup_login_user"), text: $loginUser)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Text(L("setup_login_user_hint"))
                .font(.caption)
                .foregroundStyle(palette.onSurfaceVariant)
        }

        if let error {
            Text(error)
                .font(.subheadline)
                .foregroundStyle(palette.error)
        }

        Button {
            connect(p)
        } label: {
            HStack(spacing: 10) {
                if testing {
                    ProgressView().tint(palette.onPrimary)
                    Text(L("setup_testing"))
                } else {
                    Image(systemName: "checkmark.circle.fill")
                    Text(L("setup_connect"))
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 4)
        }
        .buttonStyle(.borderedProminent)
        .disabled(testing || googleBusy || !email.contains("@") ||
                  password.trimmingCharacters(in: .whitespaces).isEmpty)
        .padding(.top, 4)
    }

    private func field<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .padding(12)
            .background(RoundedRectangle(cornerRadius: 12).fill(palette.surfaceContainer))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(palette.outlineVariant, lineWidth: 1))
    }

    // MARK: Aktionen

    private func connect(_ p: MailProviderPreset) {
        testing = true
        error = nil
        let mail = email.trimmingCharacters(in: .whitespaces)
        let pw = password
        let user = loginUser.trimmingCharacters(in: .whitespaces)
        Task { @MainActor in
            let err = await MailRepository.shared.testConnection(
                email: mail, password: pw, host: p.imap, port: p.imapPort, loginUser: user)
            if let err {
                testing = false
                error = L("setup_connection_failed", err)
                return
            }
            await AccountActions.activatePasswordAccount(
                email: mail, password: pw, loginUser: user,
                imapHost: p.imap, imapPort: p.imapPort, smtpHost: p.smtp, smtpPort: p.smtpPort)
            testing = false
            finish()
        }
    }

    private func googleSignIn() {
        googleBusy = true
        error = nil
        Task { @MainActor in
            do {
                _ = try await AccountActions.signInWithGoogle()
                googleBusy = false
                finish()
            } catch {
                googleBusy = false
                self.error = L("settings_auth_failed", error.localizedDescription)
            }
        }
    }

    /// Zurück in den Posteingang (die Einführungs-Tour startet dort beim ersten Mal).
    private func finish() {
        prefs.welcomeShown = true
        nav.popToRoot()
    }
}
