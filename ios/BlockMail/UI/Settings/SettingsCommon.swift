import SwiftUI
import UIKit
import UniformTypeIdentifiers

// MARK: - Anbieter-Vorlagen

/// Vordefinierter Mail-Anbieter (Server werden automatisch gesetzt).
/// Port von `MailProvider`/`DlgProvider`/`SetupProvider` der Android-App.
struct MailProviderPreset: Identifiable, Hashable {
    let id: String
    /// Anzeigename ("" = eigene Server → `settings_provider_custom`).
    let label: String
    let imap: String
    let imapPort: Int
    let smtp: String
    let smtpPort: Int
    /// Nur für den Einrichtungsassistenten.
    var noteKey: String = ""
    var helpURL: String? = nil
    var helpLabelKey: String? = nil
    var passwordLabelKey: String = "settings_password_label"

    var displayLabel: String { label.isEmpty ? L("settings_provider_custom") : label }
    var isCustom: Bool { id == "custom" }

    /// Anbieter im Popup „Konto hinzufügen“ und im Bearbeiten-Formular.
    static let all: [MailProviderPreset] = [
        MailProviderPreset(id: "gmail", label: "Gmail", imap: "imap.gmail.com", imapPort: 993,
                           smtp: "smtp.gmail.com", smtpPort: 465),
        MailProviderPreset(id: "webde", label: "Web.de", imap: "imap.web.de", imapPort: 993,
                           smtp: "smtp.web.de", smtpPort: 587),
        MailProviderPreset(id: "gmx", label: "GMX", imap: "imap.gmx.net", imapPort: 993,
                           smtp: "mail.gmx.net", smtpPort: 587),
        MailProviderPreset(id: "outlook", label: "Outlook / Office 365", imap: "outlook.office365.com",
                           imapPort: 993, smtp: "smtp.office365.com", smtpPort: 587),
        MailProviderPreset(id: "yahoo", label: "Yahoo Mail", imap: "imap.mail.yahoo.com", imapPort: 993,
                           smtp: "smtp.mail.yahoo.com", smtpPort: 465),
        MailProviderPreset(id: "tonline", label: "T-Online", imap: "secureimap.t-online.de", imapPort: 993,
                           smtp: "securesmtp.t-online.de", smtpPort: 465),
        MailProviderPreset(id: "icloud", label: "iCloud Mail", imap: "imap.mail.me.com", imapPort: 993,
                           smtp: "smtp.mail.me.com", smtpPort: 587),
        MailProviderPreset(id: "custom", label: "", imap: "", imapPort: 993, smtp: "", smtpPort: 587)
    ]

    /// Anbieter des Einrichtungsassistenten (mit Hinweisen und Hilfe-Links).
    static let setup: [MailProviderPreset] = [
        MailProviderPreset(id: "gmail", label: "Gmail", imap: "imap.gmail.com", imapPort: 993,
                           smtp: "smtp.gmail.com", smtpPort: 465, noteKey: "setup_note_gmail",
                           helpURL: "https://myaccount.google.com/apppasswords",
                           helpLabelKey: "setup_help_gmail", passwordLabelKey: "setup_pw_app"),
        MailProviderPreset(id: "webde", label: "Web.de", imap: "imap.web.de", imapPort: 993,
                           smtp: "smtp.web.de", smtpPort: 587, noteKey: "setup_note_webde",
                           helpURL: "https://hilfe.web.de/pop-imap/einschalten.html",
                           helpLabelKey: "setup_help_guide", passwordLabelKey: "setup_pw_normal"),
        MailProviderPreset(id: "gmx", label: "GMX", imap: "imap.gmx.net", imapPort: 993,
                           smtp: "mail.gmx.net", smtpPort: 587, noteKey: "setup_note_gmx",
                           helpURL: "https://hilfe.gmx.net/pop-imap/einschalten.html",
                           helpLabelKey: "setup_help_guide", passwordLabelKey: "setup_pw_normal"),
        MailProviderPreset(id: "outlook", label: "Outlook / Hotmail", imap: "outlook.office365.com",
                           imapPort: 993, smtp: "smtp.office365.com", smtpPort: 587,
                           noteKey: "setup_note_outlook",
                           helpURL: "https://account.live.com/proofs/AppPassword",
                           helpLabelKey: "setup_help_outlook", passwordLabelKey: "setup_pw_outlook"),
        MailProviderPreset(id: "yahoo", label: "Yahoo Mail", imap: "imap.mail.yahoo.com", imapPort: 993,
                           smtp: "smtp.mail.yahoo.com", smtpPort: 465, noteKey: "setup_note_yahoo",
                           helpURL: "https://login.yahoo.com/myaccount/security",
                           helpLabelKey: "setup_help_yahoo", passwordLabelKey: "setup_pw_app"),
        MailProviderPreset(id: "tonline", label: "T-Online", imap: "secureimap.t-online.de", imapPort: 993,
                           smtp: "securesmtp.t-online.de", smtpPort: 465, noteKey: "setup_note_tonline",
                           helpURL: "https://email.t-online.de",
                           helpLabelKey: "setup_help_tonline", passwordLabelKey: "setup_pw_tonline"),
        MailProviderPreset(id: "icloud", label: "iCloud Mail", imap: "imap.mail.me.com", imapPort: 993,
                           smtp: "smtp.mail.me.com", smtpPort: 587, noteKey: "setup_note_icloud",
                           helpURL: "https://appleid.apple.com",
                           helpLabelKey: "setup_help_icloud", passwordLabelKey: "setup_pw_icloud")
    ]

    static func idFor(imapHost: String) -> String {
        all.first { !$0.imap.isEmpty && $0.imap.caseInsensitiveCompare(imapHost) == .orderedSame }?.id ?? "custom"
    }

    static func byId(_ id: String) -> MailProviderPreset {
        all.first { $0.id == id } ?? all[all.count - 1]
    }
}

// MARK: - Konto-Aktionen (gemeinsam für Einstellungen, Assistent, Popup)

@MainActor
enum AccountActions {

    /// Speichert Passwort-Zugangsdaten als neues aktives Konto (bisheriges
    /// bleibt in der Kontenliste) und wechselt vollständig dorthin.
    static func activatePasswordAccount(email: String, password: String, loginUser: String,
                                        imapHost: String, imapPort: Int,
                                        smtpHost: String, smtpPort: Int) async {
        let prefs = Prefs.shared
        prefs.snapshotActiveAccount()
        prefs.email = email.trimmingCharacters(in: .whitespaces)
        prefs.appPassword = password
        prefs.imapHost = imapHost.trimmingCharacters(in: .whitespaces)
        prefs.imapPort = imapPort
        prefs.smtpHost = smtpHost.trimmingCharacters(in: .whitespaces)
        prefs.smtpPort = smtpPort
        prefs.authMethod = "password"
        prefs.loginUser = loginUser.trimmingCharacters(in: .whitespaces)
        // Kein alter Google-Token darf am neuen Konto hängen
        prefs.refreshToken = ""
        prefs.accessToken = ""
        prefs.accessTokenExpiry = 0
        prefs.snapshotActiveAccount()
        await MailRepository.shared.switchAccount(prefs.activeAccount)
        restartPush()
    }

    /// Google-Anmeldung (ASWebAuthenticationSession) → neues aktives Konto.
    /// Liefert die angemeldete Adresse.
    static func signInWithGoogle() async throws -> String {
        let prefs = Prefs.shared
        let result = try await GoogleAuth.signIn()
        let previous = prefs.email
        // Bisheriges Konto sichern, bevor die aktiven Felder überschrieben werden
        prefs.snapshotActiveAccount()
        let mail = result.email ?? previous
        let changed = mail.caseInsensitiveCompare(previous) != .orderedSame
        prefs.email = mail
        prefs.authMethod = "oauth"
        prefs.accessToken = result.accessToken
        prefs.accessTokenExpiry = nowMs() + result.expiresIn * 1000
        if let rt = result.refreshToken, !rt.isEmpty {
            prefs.refreshToken = rt
        } else if changed {
            prefs.refreshToken = ""
        }
        // Google meldet sich immer mit der Mail-Adresse an und nutzt die Gmail-Server
        prefs.appPassword = ""
        prefs.loginUser = ""
        prefs.imapHost = "imap.gmail.com"
        prefs.imapPort = 993
        prefs.smtpHost = "smtp.gmail.com"
        prefs.smtpPort = 465
        prefs.snapshotActiveAccount()
        await MailRepository.shared.reloadForActiveAccount()
        restartPush()
        return mail
    }

    /// Echtzeit-Verbindung und Push-Server-Anmeldung auf den neuen Kontostand bringen.
    static func restartPush() {
        if Prefs.shared.pushMode == "push" {
            PushService.shared.restart()
        }
        PushRegistration.shared.syncSoon()
    }
}

// MARK: - Hilfsfunktionen

enum SettingsFormat {
    /// Menschenlesbare Größe der Index-Datenbank (Port von `formatDbSize`).
    static func dbSize(_ bytes: Int64) -> String {
        if bytes < 1_000_000 {
            return "\(bytes / 1000) kB"
        }
        return String(format: "%.1f MB", locale: Locale.current, Double(bytes) / 1_000_000.0)
    }

    static func dateTime(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f.string(from: Date(ms: ms))
    }

    static var appVersion: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? ""
        return b.isEmpty || b == v ? v : "\(v) (\(b))"
    }

    /// ARGB-Int (Android-Format, vorzeichenbehaftet) ↔ SwiftUI-Farbe.
    static func color(_ argb: Int) -> Color {
        Color(argb: UInt32(truncatingIfNeeded: argb))
    }

    static func argb(_ color: Color) -> Int {
        let (r, g, b, _) = UIColor(color).rgba
        func c(_ v: Double) -> UInt32 { UInt32(max(0, min(255, (v * 255).rounded()))) }
        let value: UInt32 = 0xFF00_0000 | (c(r) << 16) | (c(g) << 8) | c(b)
        return Int(Int32(bitPattern: value))
    }
}

/// Zeile mit Titel, Beschreibung und Schalter (Android: Row + Switch).
struct SettingsToggleRow: View {
    let title: String
    var desc: String? = nil
    @Binding var isOn: Bool

    @Environment(\.palette) private var palette

    var body: some View {
        Toggle(isOn: $isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                if let desc {
                    Text(desc)
                        .font(.caption)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
            }
        }
    }
}

/// Kleiner erläuternder Text (Android: bodySmall, onSurfaceVariant).
struct SettingsHint: View {
    let text: String
    @Environment(\.palette) private var palette

    init(_ text: String) { self.text = text }

    var body: some View {
        Text(text)
            .font(.caption)
            .foregroundStyle(palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// Abschnitts-Kopf mit Symbol und Untertitel (Android: `SectionCard`).
struct SettingsSectionHeader: View {
    let title: String
    let icon: String
    var subtitle: String? = nil

    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Label(title, systemImage: icon)
                .font(.headline)
                .foregroundStyle(palette.primary)
                .textCase(nil)
            if let subtitle {
                Text(subtitle)
                    .font(.caption)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .textCase(nil)
            }
        }
        .padding(.top, 6)
    }
}

// MARK: - Datei für Sicherung (Export/Import)

struct SettingsBackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.json] }

    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents,
              let s = String(data: data, encoding: .utf8) else {
            throw CocoaError(.fileReadCorruptFile)
        }
        text = s
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

extension Notification.Name {
    /// Bitte an den Posteingang, die Einführungs-Tour (erneut) zu starten.
    static let settingsRequestTour = Notification.Name("BlockMailSettingsRequestTour")
}

// MARK: - Gemeinsamer Zustand der Einstellungen

/// Sheets der Einstellungen (zentral am Formular angehängt, damit sie nicht
/// von einzelnen, evtl. nicht mehr sichtbaren Listenzeilen abhängen).
enum SettingsSheet: Identifiable {
    case addAccount
    case accountColor(String)
    case folders(String)
    case template
    case feedback

    var id: String {
        switch self {
        case .addAccount: return "add"
        case .accountColor(let e): return "color:" + e.lowercased()
        case .folders(let e): return "folders:" + e.lowercased()
        case .template: return "template"
        case .feedback: return "feedback"
        }
    }
}

@MainActor
@Observable
final class SettingsUIState {
    let snackbar = SnackbarState()
    var sheet: SettingsSheet?
    var showExporter = false
    var showImporter = false
    var exportDocument = SettingsBackupDocument(text: "")
    var confirmClearIndex = false
    /// Änderungszähler für die Index-Statistik (nach „Leeren“).
    var indexVersion = 0
    /// Änderungszähler für nicht beobachtbare Listen (Vorlagen, Kontakte).
    var listsVersion = 0
}

extension Prefs {
    /// Bindung an eine Einstellung (für Toggle/Picker/TextField).
    func settingsBinding<T>(_ keyPath: ReferenceWritableKeyPath<Prefs, T>) -> Binding<T> {
        Binding(get: { self[keyPath: keyPath] }, set: { self[keyPath: keyPath] = $0 })
    }
}
