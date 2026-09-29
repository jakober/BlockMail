import Foundation
import Observation

/// App-Gruppe (geteilt mit dem Widget).
enum AppGroup {
    static let id = "group.com.jakober.blockmail"

    static var defaults: UserDefaults {
        UserDefaults(suiteName: id) ?? .standard
    }

    /// Gemeinsamer Datenordner (Posteingangs-Caches fürs Widget).
    static var container: URL {
        if let url = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) {
            return url
        }
        return FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
    }
}

/// Einstellungen & Zugangsdaten. Port von `Prefs.kt` — gleiche Schlüssel,
/// gleiche Standardwerte. Zugangsdaten liegen im Schlüsselbund, alles andere
/// in den UserDefaults der App-Gruppe. Die Android-„Flows“ sind hier
/// beobachtbare Eigenschaften (@Observable).
@Observable
final class Prefs {

    static let shared = Prefs()

    @ObservationIgnored private let sp = AppGroup.defaults

    // MARK: Datentypen

    /// Gespeichertes Mail-Konto (Profil) für den Konten-Wechsler.
    struct Account: Codable, Hashable, Identifiable {
        var email: String
        var authMethod: String
        var appPassword: String
        var refreshToken: String
        var imapHost: String = "imap.gmail.com"
        var imapPort: Int = 993
        var smtpHost: String = "smtp.gmail.com"
        var smtpPort: Int = 465
        /// Abweichender Anmeldename ("" = Mail-Adresse).
        var loginUser: String = ""

        var id: String { email.lowercased() }
        /// Name, mit dem sich die App beim Server anmeldet.
        func loginName() -> String { loginUser.isEmpty ? email : loginUser }
    }

    /// Zurückgestellte Mail (Snooze).
    struct Snooze: Codable, Hashable {
        var uid: Int64
        var until: Int64
        var from: String
        var address: String
        var subject: String
    }

    /// Geplante Mail in der Ausgangs-Warteschlange.
    struct ScheduledMail: Codable, Hashable, Identifiable {
        var id: Int64
        var sendAt: Int64
        var to: String
        var cc: String
        var bcc: String
        var subject: String
        var body: String
        var html: String?
        /// Absender-Konto ("" = aktives Konto beim Senden).
        var account: String = ""
    }

    /// Automatisch gespeicherter Entwurf aus dem Verfassen-Fenster.
    struct Draft: Codable, Hashable, Identifiable {
        var id: Int64
        var savedAt: Int64
        var to: String
        var cc: String
        var bcc: String
        var subject: String
        var html: String
        var account: String = ""
    }

    /// Gesendete Mail (für „wartet auf Antwort“).
    struct SentEntry: Codable, Hashable {
        var to: String
        var subject: String
        var at: Int64
    }

    // MARK: Beobachtbare Einstellungen (Android: *Flow)

    var colorScheme: String { didSet { sp.set(colorScheme, forKey: "color_scheme") } }
    /// Frei gewählte Akzentfarbe (ARGB) für „Eigene Farbe“.
    var customColor: Int { didSet { sp.set(customColor, forKey: "custom_color") } }
    /// "dark" (Standard), "light" oder "system".
    var darkMode: String { didSet { sp.set(darkMode, forKey: "dark_mode") } }
    var conversationView: Bool { didSet { sp.set(conversationView, forKey: "conversation_view") } }
    /// "blocks" (Raster) oder "list".
    var inboxLayout: String { didSet { sp.set(inboxLayout, forKey: "inbox_layout") } }
    /// "auto", "claude" oder "apple" (Android: "gemini").
    var aiEngine: String { didSet { sp.set(aiEngine, forKey: "ai_engine") } }
    var swipeLeftAction: String { didSet { sp.set(swipeLeftAction, forKey: "swipe_left") } }
    var swipeRightAction: String { didSet { sp.set(swipeRightAction, forKey: "swipe_right") } }
    var devMode: Bool { didSet { sp.set(devMode, forKey: "dev_mode") } }
    /// "push" (Echtzeit über Push-Server) oder "eco" (nur Hintergrundprüfung).
    var pushMode: String { didSet { sp.set(pushMode, forKey: "push_mode") } }
    /// Aktions-Knöpfe der Mail-Benachrichtigung (reply/read/archive/delete, max. 3).
    /// Werte über `setNotifActions` setzen (bereinigt und auf 3 begrenzt).
    private(set) var notifActions: [String]

    func setNotifActions(_ v: [String]) {
        var cleaned: [String] = []
        for a in v where ["reply", "read", "archive", "delete"].contains(a) && !cleaned.contains(a) {
            cleaned.append(a)
        }
        cleaned = Array(cleaned.prefix(3))
        sp.set(cleaned, forKey: "notif_actions")
        notifActions = cleaned
    }
    private(set) var drafts: [Draft] = []
    private(set) var phishing: Set<String> = []
    var radarEnabled: Bool { didSet { sp.set(radarEnabled, forKey: "radar_enabled") } }
    var focusMode: Bool { didSet { sp.set(focusMode, forKey: "focus_mode") } }
    var indexEnabled: Bool { didSet { sp.set(indexEnabled, forKey: "index_enabled") } }
    /// Zeitraum des Suchindex in Jahren (0 = alles).
    var indexYears: Int { didSet { sp.set(indexYears, forKey: "index_years") } }
    var plainDesign: Bool { didSet { sp.set(plainDesign, forKey: "plain_design") } }
    /// Schriftgröße in Prozent (80–120).
    var fontScalePercent: Int { didSet { sp.set(min(120, max(80, fontScalePercent)), forKey: "font_scale") } }
    /// Änderungszähler der Konto-Farben (löst Neuzeichnen aus).
    private(set) var accountColorsVersion = 0
    /// Änderungszähler der ausgeblendeten/zusätzlichen Ordner.
    private(set) var hiddenFoldersVersion = 0
    private(set) var muted: Set<String> = []
    private(set) var blocked: Set<String> = []
    private(set) var vip: Set<String> = []
    var vipOnlyNotifications: Bool { didSet { sp.set(vipOnlyNotifications, forKey: "vip_only_notif") } }
    /// UIDs aller aktuell zurückgestellten Mails.
    private(set) var snoozed: Set<Int64> = []

    // MARK: Aktives Konto

    var email: String { didSet { sp.set(email.trimmingCharacters(in: .whitespaces), forKey: "email") } }
    var appPassword: String {
        get { access(keyPath: \.appPassword); return Keychain.get("app_password") ?? "" }
        set { withMutation(keyPath: \.appPassword) { Keychain.set("app_password", newValue.replacingOccurrences(of: " ", with: "")) } }
    }
    /// "oauth" = Google-Anmeldung, "password" = App-Passwort.
    var authMethod: String { didSet { sp.set(authMethod, forKey: "auth_method") } }
    var refreshToken: String {
        get { access(keyPath: \.refreshToken); return Keychain.get("g_refresh_token") ?? "" }
        set { withMutation(keyPath: \.refreshToken) { Keychain.set("g_refresh_token", newValue) } }
    }
    var accessToken: String {
        get { Keychain.get("g_access_token") ?? "" }
        set { Keychain.set("g_access_token", newValue) }
    }
    var accessTokenExpiry: Int64 {
        get { Int64(sp.double(forKey: "g_access_token_expiry")) }
        set { sp.set(Double(newValue), forKey: "g_access_token_expiry") }
    }
    var imapHost: String { didSet { sp.set(imapHost.trimmingCharacters(in: .whitespaces), forKey: "imap_host") } }
    var imapPort: Int { didSet { sp.set(imapPort, forKey: "imap_port") } }
    var smtpHost: String { didSet { sp.set(smtpHost.trimmingCharacters(in: .whitespaces), forKey: "smtp_host") } }
    var smtpPort: Int { didSet { sp.set(smtpPort, forKey: "smtp_port") } }
    var loginUser: String { didSet { sp.set(loginUser.trimmingCharacters(in: .whitespaces), forKey: "login_user") } }

    /// Eigener Claude-API-Schlüssel (iOS: einziger Weg zu Claude — kein Abo-Proxy).
    var claudeApiKey: String {
        get { access(keyPath: \.claudeApiKey); return Keychain.get("claude_key") ?? "" }
        set { withMutation(keyPath: \.claudeApiKey) { Keychain.set("claude_key", newValue.trimmingCharacters(in: .whitespacesAndNewlines)) } }
    }

    /// URL des eigenen Push-Servers ("" = kein Echtzeit-Push, nur Hintergrundprüfung).
    var pushServerURL: String { didSet { sp.set(pushServerURL.trimmingCharacters(in: .whitespaces), forKey: "push_server_url") } }
    /// Zuletzt registriertes APNs-Geräte-Token (hex).
    var apnsToken: String { didSet { sp.set(apnsToken, forKey: "apns_token") } }
    /// Zeitpunkt der letzten erfolgreichen Push-Registrierung (ms, 0 = nie).
    var pushRegisteredAt: Int64 { didSet { sp.set(Double(pushRegisteredAt), forKey: "push_registered_at") } }

    // MARK: Sonstiges

    /// Standard-Absender für NEUE Mails ("" = aktives Konto).
    var defaultSendAccount: String { didSet { sp.set(defaultSendAccount.trimmingCharacters(in: .whitespaces), forKey: "default_send_account") } }
    var welcomeShown: Bool { didSet { sp.set(welcomeShown, forKey: "welcome_shown") } }
    var tourShown: Bool { didSet { sp.set(tourShown, forKey: "tour_shown") } }
    var signature: String { didSet { sp.set(signature, forKey: "signature") } }
    var lastRadarRunDay: String { didSet { sp.set(lastRadarRunDay, forKey: "last_radar_day") } }
    var indexAutoBuilt: Bool { didSet { sp.set(indexAutoBuilt, forKey: "index_auto_built") } }
    var snippetVersion: Int { didSet { sp.set(snippetVersion, forKey: "snippet_version") } }
    var firstStartAt: Int64 { didSet { sp.set(Double(firstStartAt), forKey: "first_start_at") } }
    var aiPdfTitle: String { didSet { sp.set(aiPdfTitle, forKey: "ai_pdf_title") } }
    var aiPdfBody: String { didSet { sp.set(aiPdfBody, forKey: "ai_pdf_body") } }

    /// Installations-Token (zufällige UUID, einmalig erzeugt).
    var installToken: String {
        if let t = sp.string(forKey: "install_token"), !t.isEmpty { return t }
        let t = UUID().uuidString.lowercased()
        sp.set(t, forKey: "install_token")
        return t
    }

    // MARK: Init

    private init() {
        let sp = AppGroup.defaults
        colorScheme = sp.string(forKey: "color_scheme") ?? "klarmail"
        customColor = sp.object(forKey: "custom_color") as? Int ?? Int(Int32(bitPattern: 0xFFEE5F0F))
        darkMode = sp.string(forKey: "dark_mode") ?? "dark"
        conversationView = sp.bool(forKey: "conversation_view")
        inboxLayout = sp.string(forKey: "inbox_layout") ?? "blocks"
        aiEngine = sp.string(forKey: "ai_engine") ?? "auto"
        swipeLeftAction = sp.string(forKey: "swipe_left") ?? "delete"
        swipeRightAction = sp.string(forKey: "swipe_right") ?? "read"
        devMode = sp.bool(forKey: "dev_mode")
        pushMode = sp.string(forKey: "push_mode") ?? "push"
        notifActions = (sp.array(forKey: "notif_actions") as? [String]) ?? ["reply", "read", "delete"]
        radarEnabled = sp.object(forKey: "radar_enabled") as? Bool ?? true
        focusMode = sp.bool(forKey: "focus_mode")
        indexEnabled = sp.object(forKey: "index_enabled") as? Bool ?? true
        indexYears = sp.object(forKey: "index_years") as? Int ?? 1
        plainDesign = sp.object(forKey: "plain_design") as? Bool ?? true
        fontScalePercent = min(120, max(80, sp.object(forKey: "font_scale") as? Int ?? 100))
        vipOnlyNotifications = sp.bool(forKey: "vip_only_notif")
        email = sp.string(forKey: "email") ?? ""
        authMethod = sp.string(forKey: "auth_method") ?? "password"
        imapHost = sp.string(forKey: "imap_host") ?? "imap.gmail.com"
        imapPort = sp.object(forKey: "imap_port") as? Int ?? 993
        smtpHost = sp.string(forKey: "smtp_host") ?? "smtp.gmail.com"
        smtpPort = sp.object(forKey: "smtp_port") as? Int ?? 465
        loginUser = sp.string(forKey: "login_user") ?? ""
        pushServerURL = sp.string(forKey: "push_server_url") ?? ""
        apnsToken = sp.string(forKey: "apns_token") ?? ""
        pushRegisteredAt = Int64(sp.double(forKey: "push_registered_at"))
        defaultSendAccount = sp.string(forKey: "default_send_account") ?? ""
        welcomeShown = sp.bool(forKey: "welcome_shown")
        tourShown = sp.bool(forKey: "tour_shown")
        signature = sp.string(forKey: "signature") ?? ""
        lastRadarRunDay = sp.string(forKey: "last_radar_day") ?? ""
        indexAutoBuilt = sp.bool(forKey: "index_auto_built")
        snippetVersion = sp.integer(forKey: "snippet_version")
        firstStartAt = Int64(sp.double(forKey: "first_start_at"))
        aiPdfTitle = sp.string(forKey: "ai_pdf_title") ?? ""
        aiPdfBody = sp.string(forKey: "ai_pdf_body") ?? ""
        muted = loadSet("muted_senders")
        blocked = loadSet("blocked_senders")
        vip = loadSet("vip_senders")
        phishing = loadSet("phishing_mails")
        drafts = loadDrafts()
        snoozed = Set(snoozes().map { $0.uid })
        if firstStartAt == 0 {
            firstStartAt = nowMs()
            sp.set(Double(firstStartAt), forKey: "first_start_at")
        }
        snapshotActiveAccount()
    }

    // MARK: JSON-Helfer

    private func loadJSON<T: Decodable>(_ key: String, _ type: T.Type, secure: Bool = false) -> T? {
        let raw: String? = secure ? Keychain.get(key) : sp.string(forKey: key)
        guard let raw, let data = raw.data(using: .utf8) else { return nil }
        return try? JSONDecoder().decode(type, from: data)
    }

    private func saveJSON<T: Encodable>(_ key: String, _ value: T, secure: Bool = false) {
        guard let data = try? JSONEncoder().encode(value),
              let s = String(data: data, encoding: .utf8) else { return }
        if secure { Keychain.set(key, s) } else { sp.set(s, forKey: key) }
    }

    private func loadSet(_ key: String) -> Set<String> {
        Set((loadJSON(key, [String].self) ?? []).map { $0.lowercased() })
    }

    private func saveSet(_ key: String, _ set: Set<String>) {
        saveJSON(key, Array(set))
    }

    private static func norm(_ a: String) -> String { a.trimmingCharacters(in: .whitespaces).lowercased() }

    // MARK: Stumm / Blockiert / VIP

    static let rulesChangedNotification = Notification.Name("BlockMailRulesChanged")

    /// Meldet geänderte Stumm-/Blockier-/Snooze-Listen (Android: combine der Flows).
    private func rulesChanged() {
        NotificationCenter.default.post(name: Self.rulesChangedNotification, object: nil)
    }

    func addMuted(_ address: String) {
        let k = Self.norm(address); guard k.contains("@") else { return }
        muted.insert(k); saveSet("muted_senders", muted); rulesChanged()
    }
    func removeMuted(_ address: String) { muted.remove(Self.norm(address)); saveSet("muted_senders", muted); rulesChanged() }
    func isMuted(_ address: String) -> Bool { muted.contains(Self.norm(address)) }

    func addBlocked(_ address: String) {
        let k = Self.norm(address); guard k.contains("@") else { return }
        blocked.insert(k); saveSet("blocked_senders", blocked); rulesChanged()
    }
    func removeBlocked(_ address: String) { blocked.remove(Self.norm(address)); saveSet("blocked_senders", blocked); rulesChanged() }
    func isBlocked(_ address: String) -> Bool { blocked.contains(Self.norm(address)) }

    func addVip(_ address: String) {
        let k = Self.norm(address); guard k.contains("@") else { return }
        vip.insert(k); saveSet("vip_senders", vip)
    }
    func removeVip(_ address: String) { vip.remove(Self.norm(address)); saveSet("vip_senders", vip) }
    func isVip(_ address: String) -> Bool { vip.contains(Self.norm(address)) }

    // MARK: Backup & Umzug (ohne Zugangsdaten)

    private let backupKeys: Set<String> = [
        "color_scheme", "custom_color", "dark_mode", "inbox_layout", "plain_design", "font_scale",
        "conversation_view", "swipe_left", "swipe_right", "signature", "mail_templates",
        "muted_senders", "blocked_senders", "vip_senders", "vip_only_notif", "notif_actions",
        "push_mode", "default_send_account", "ai_engine", "known_recipients"
    ]
    private let backupPrefixes = ["account_color_", "hidden_folders_"]

    func exportSettingsJson() -> String {
        var values: [String: Any] = [:]
        for (k, v) in sp.dictionaryRepresentation()
        where backupKeys.contains(k) || backupPrefixes.contains(where: { k.hasPrefix($0) }) {
            switch v {
            case let s as String: values[k] = ["t": "s", "v": s]
            case let b as Bool where CFGetTypeID(v as CFTypeRef) == CFBooleanGetTypeID(): values[k] = ["t": "b", "v": b]
            case let i as Int: values[k] = ["t": "i", "v": i]
            case let a as [String]: values[k] = ["t": "s", "v": String(data: (try? JSONSerialization.data(withJSONObject: a)) ?? Data(), encoding: .utf8) ?? "[]"]
            default: continue
            }
        }
        let root: [String: Any] = [
            "app": "BlockMail", "backupVersion": 1, "exportedAt": nowMs(), "values": values
        ]
        let data = (try? JSONSerialization.data(withJSONObject: root, options: [.prettyPrinted, .sortedKeys])) ?? Data()
        return String(data: data, encoding: .utf8) ?? "{}"
    }

    /// Spielt eine Sicherung ein (auch von Android exportierte) und liefert die Zahl übernommener Werte.
    func importSettingsJson(_ json: String) throws -> Int {
        guard let data = json.data(using: .utf8),
              let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["app"] as? String == "BlockMail",
              let values = root["values"] as? [String: Any] else {
            throw NSError(domain: "BlockMail", code: 1, userInfo: [NSLocalizedDescriptionKey: L("set_backup_invalid")])
        }
        var count = 0
        for (k, raw) in values {
            guard backupKeys.contains(k) || backupPrefixes.contains(where: { k.hasPrefix($0) }),
                  let o = raw as? [String: Any], let t = o["t"] as? String else { continue }
            switch t {
            case "s":
                let s = o["v"] as? String ?? ""
                if k == "notif_actions", let d = s.data(using: .utf8),
                   let arr = try? JSONSerialization.jsonObject(with: d) as? [String] {
                    sp.set(arr, forKey: k)
                } else { sp.set(s, forKey: k) }
            case "b": sp.set(o["v"] as? Bool ?? false, forKey: k)
            case "i", "l": sp.set(o["v"] as? Int ?? 0, forKey: k)
            default: continue
            }
            count += 1
        }
        reloadAfterImport()
        return count
    }

    private func reloadAfterImport() {
        colorScheme = sp.string(forKey: "color_scheme") ?? colorScheme
        customColor = sp.object(forKey: "custom_color") as? Int ?? customColor
        darkMode = sp.string(forKey: "dark_mode") ?? darkMode
        conversationView = sp.bool(forKey: "conversation_view")
        inboxLayout = sp.string(forKey: "inbox_layout") ?? inboxLayout
        aiEngine = sp.string(forKey: "ai_engine") ?? aiEngine
        swipeLeftAction = sp.string(forKey: "swipe_left") ?? swipeLeftAction
        swipeRightAction = sp.string(forKey: "swipe_right") ?? swipeRightAction
        pushMode = sp.string(forKey: "push_mode") ?? pushMode
        muted = loadSet("muted_senders")
        blocked = loadSet("blocked_senders")
        vip = loadSet("vip_senders")
        vipOnlyNotifications = sp.bool(forKey: "vip_only_notif")
        setNotifActions((sp.array(forKey: "notif_actions") as? [String]) ?? notifActions)
        signature = sp.string(forKey: "signature") ?? signature
        plainDesign = sp.object(forKey: "plain_design") as? Bool ?? plainDesign
        fontScalePercent = sp.object(forKey: "font_scale") as? Int ?? fontScalePercent
        accountColorsVersion += 1
        hiddenFoldersVersion += 1
    }

    // MARK: Bekannte Empfänger

    func knownRecipients() -> [String: String] {
        loadJSON("known_recipients", [String: String].self) ?? [:]
    }

    func addKnownRecipients(_ entries: [(String, String)]) {
        guard !entries.isEmpty else { return }
        var o = knownRecipients()
        for (address, name) in entries {
            let k = Self.norm(address)
            guard k.contains("@") else { continue }
            o[k] = name.isEmpty ? (o[k] ?? "") : name
        }
        saveJSON("known_recipients", o)
    }

    func removeKnownRecipient(_ address: String) {
        var o = knownRecipients()
        o.removeValue(forKey: Self.norm(address))
        saveJSON("known_recipients", o)
    }

    // MARK: Antwort-Gedächtnis

    private struct ReplyRecord: Codable { var at: Int64; var mid: String }

    private func replyKey(_ account: String, _ uid: Int64) -> String {
        (account.isEmpty ? Self.norm(email) : Self.norm(account)) + ":\(uid)"
    }

    func replyRecord(account: String, uid: Int64) -> (at: Int64, messageId: String)? {
        guard let r = (loadJSON("reply_records", [String: ReplyRecord].self) ?? [:])[replyKey(account, uid)]
        else { return nil }
        return (r.at, r.mid)
    }

    func addReplyRecord(account: String, uid: Int64, at: Int64, messageId: String) {
        var o = loadJSON("reply_records", [String: ReplyRecord].self) ?? [:]
        o[replyKey(account, uid)] = ReplyRecord(at: at, mid: messageId)
        if o.count > 400 {
            for k in o.sorted(by: { $0.value.at < $1.value.at }).prefix(o.count - 400).map({ $0.key }) {
                o.removeValue(forKey: k)
            }
        }
        saveJSON("reply_records", o)
    }

    // MARK: Push-UID-Merkliste je Konto

    private func pushUidKey(_ accountEmail: String) -> String {
        "last_push_uid_" + Self.norm(accountEmail.isEmpty ? email : accountEmail)
    }
    func lastPushUidFor(_ accountEmail: String) -> Int64 { Int64(sp.double(forKey: pushUidKey(accountEmail))) }
    func setLastPushUidFor(_ accountEmail: String, _ v: Int64) { sp.set(Double(v), forKey: pushUidKey(accountEmail)) }

    // MARK: Konten

    /// Konten stehen samt Zugangsdaten im Schlüsselbund.
    func accounts() -> [Account] {
        (loadJSON("accounts", [Account].self, secure: true) ?? []).filter { !$0.email.isEmpty }
    }

    private func saveAccounts(_ list: [Account]) {
        saveJSON("accounts", list, secure: true)
        accountsVersion += 1
    }

    /// Änderungszähler der Kontenliste (für die Oberfläche).
    private(set) var accountsVersion = 0

    /// Konten, die geprüft/gepusht werden: alle gespeicherten plus das aktive.
    func pushAccounts() -> [Account] {
        let list = accounts()
        guard isConfigured, !email.isEmpty else { return list }
        if list.contains(where: { $0.email.caseInsensitiveCompare(email) == .orderedSame }) { return list }
        return list + [activeAccount]
    }

    var activeAccount: Account {
        Account(email: email, authMethod: authMethod, appPassword: appPassword,
                refreshToken: refreshToken, imapHost: imapHost, imapPort: imapPort,
                smtpHost: smtpHost, smtpPort: smtpPort, loginUser: loginUser)
    }

    /// Sichert die aktuellen Zugangsdaten als Konto in der Kontenliste (Upsert).
    func snapshotActiveAccount() {
        guard !email.isEmpty, isConfigured else { return }
        let acc = activeAccount
        saveAccounts(accounts().filter { $0.email.caseInsensitiveCompare(acc.email) != .orderedSame } + [acc])
        indexAutoBuilt = false
    }

    func removeAccount(_ accountEmail: String) {
        saveAccounts(accounts().filter { $0.email.caseInsensitiveCompare(accountEmail) != .orderedSame })
    }

    /// Aktiviert ein gespeichertes Konto (Zugangsdaten in die aktiven Felder).
    func activateAccount(_ acc: Account) {
        snapshotActiveAccount()
        email = acc.email
        authMethod = acc.authMethod
        appPassword = acc.appPassword
        refreshToken = acc.refreshToken
        imapHost = acc.imapHost
        imapPort = acc.imapPort
        smtpHost = acc.smtpHost
        smtpPort = acc.smtpPort
        loginUser = acc.loginUser
        accessToken = ""
        accessTokenExpiry = 0
        snoozed = Set(snoozes().map { $0.uid })
    }

    /// Meldet das aktive Konto ab (Zugangsdaten leeren).
    func clearActiveAccount() {
        email = ""
        appPassword = ""
        refreshToken = ""
        accessToken = ""
        accessTokenExpiry = 0
        authMethod = "password"
        imapHost = "imap.gmail.com"; imapPort = 993
        smtpHost = "smtp.gmail.com"; smtpPort = 465
        loginUser = ""
    }

    /// Dateiname des Posteingangs-Caches für ein Konto.
    static func inboxCacheFileName(for accountEmail: String) -> String {
        let safe = accountEmail.trimmingCharacters(in: .whitespaces).lowercased()
            .replacingOccurrences(of: "[^a-z0-9@._-]", with: "_", options: .regularExpression)
        return safe.isEmpty ? "inbox_cache.json" : "inbox_cache_\(safe).json"
    }

    func inboxCacheFileName() -> String { Self.inboxCacheFileName(for: email) }

    // MARK: Snooze

    private func snoozeKey() -> String { "snoozes_" + Self.norm(email) }

    func snoozes() -> [Snooze] {
        loadJSON(snoozeKey(), [Snooze].self) ?? loadJSON("snoozes", [Snooze].self) ?? []
    }

    private func saveSnoozes(_ list: [Snooze]) {
        saveJSON(snoozeKey(), list)
        snoozed = Set(list.map { $0.uid })
        rulesChanged()
    }

    func addSnooze(_ s: Snooze) { saveSnoozes(snoozes().filter { $0.uid != s.uid } + [s]) }
    func removeSnooze(_ uid: Int64) { saveSnoozes(snoozes().filter { $0.uid != uid }) }

    // MARK: Ausgang (geplantes Senden)

    func outbox() -> [ScheduledMail] { loadJSON("outbox", [ScheduledMail].self) ?? [] }
    func saveOutbox(_ list: [ScheduledMail]) { saveJSON("outbox", list); outboxVersion += 1 }
    func addOutbox(_ m: ScheduledMail) { saveOutbox(outbox() + [m]) }
    func removeOutbox(_ id: Int64) { saveOutbox(outbox().filter { $0.id != id }) }
    private(set) var outboxVersion = 0

    // MARK: Entwürfe

    private func loadDrafts() -> [Draft] {
        (loadJSON("drafts", [Draft].self) ?? []).sorted { $0.savedAt > $1.savedAt }
    }

    private func persistDrafts(_ list: [Draft]) {
        let capped = Array(list.sorted { $0.savedAt > $1.savedAt }.prefix(20))
        saveJSON("drafts", capped)
        drafts = capped
    }

    func saveDraft(_ d: Draft) { persistDrafts(drafts.filter { $0.id != d.id } + [d]) }
    func removeDraft(_ id: Int64) { persistDrafts(drafts.filter { $0.id != id }) }

    // MARK: Phishing-Wächter

    func phishingKey(_ account: String, _ uid: Int64) -> String { "\(Self.norm(account)):\(uid)" }

    func markPhishing(_ account: String, _ uid: Int64, _ suspicious: Bool) {
        let key = phishingKey(account, uid)
        var updated = phishing
        if suspicious { updated.insert(key) } else { updated.remove(key) }
        guard updated != phishing else { return }
        if updated.count > 200 { updated = Set(updated.suffix(200)) }
        saveSet("phishing_mails", updated)
        phishing = updated
    }

    func isPhishing(_ account: String, _ uid: Int64) -> Bool { phishing.contains(phishingKey(account, uid)) }

    func markNotPhishing(_ account: String, _ uid: Int64) {
        var set = loadSet("phishing_ok")
        set.insert(phishingKey(account, uid))
        if set.count > 300 { set = Set(set.suffix(300)) }
        saveSet("phishing_ok", set)
        markPhishing(account, uid, false)
    }

    func isPhishingCleared(_ account: String, _ uid: Int64) -> Bool {
        loadSet("phishing_ok").contains(phishingKey(account, uid))
    }

    // MARK: Antwort-Radar

    func addReplied(_ account: String, _ uid: Int64) {
        var set = loadSet("replied_mails")
        set.insert(phishingKey(account, uid))
        if set.count > 300 { set = Set(set.suffix(300)) }
        saveSet("replied_mails", set)
    }

    func isReplied(_ account: String, _ uid: Int64) -> Bool {
        loadSet("replied_mails").contains(phishingKey(account, uid))
    }

    func sentLog() -> [SentEntry] { loadJSON("sent_log", [SentEntry].self) ?? [] }

    func addSentLog(to: String, subject: String) {
        let k = Self.norm(to)
        guard k.contains("@") else { return }
        saveJSON("sent_log", Array((sentLog() + [SentEntry(to: k, subject: subject, at: nowMs())]).suffix(50)))
    }

    // MARK: Vorlagen

    struct Template: Codable, Hashable { var title: String; var text: String }

    func mailTemplates() -> [(String, String)] {
        (loadJSON("mail_templates", [Template].self) ?? []).map { ($0.title, $0.text) }
    }

    func saveMailTemplates(_ list: [(String, String)]) {
        saveJSON("mail_templates", list.map { Template(title: $0.0, text: $0.1) })
    }

    // MARK: Konto-Farben & Ordner

    func accountColor(_ accountEmail: String) -> Int? {
        let v = sp.integer(forKey: "account_color_" + Self.norm(accountEmail))
        return v == 0 ? nil : v
    }

    func setAccountColor(_ accountEmail: String, _ color: Int?) {
        let key = "account_color_" + Self.norm(accountEmail)
        if let color { sp.set(color, forKey: key) } else { sp.removeObject(forKey: key) }
        accountColorsVersion += 1
    }

    func extraFolders(_ accountEmail: String) -> Set<String> {
        Set(loadJSON("extra_folders_\(accountEmail.lowercased())", [String].self) ?? [])
    }

    func setExtraFolders(_ accountEmail: String, _ folders: Set<String>) {
        saveJSON("extra_folders_\(accountEmail.lowercased())", Array(folders))
        hiddenFoldersVersion += 1
    }

    func hiddenFolders(_ accountEmail: String) -> Set<String> {
        Set(loadJSON("hidden_folders_" + Self.norm(accountEmail), [String].self) ?? [])
    }

    func setHiddenFolders(_ accountEmail: String, _ hidden: Set<String>) {
        saveJSON("hidden_folders_" + Self.norm(accountEmail), Array(hidden))
        hiddenFoldersVersion += 1
    }

    // MARK: Status

    var isConfigured: Bool {
        !email.isEmpty && ((authMethod == "oauth" && !refreshToken.isEmpty) || !appPassword.isEmpty)
    }

    /// iOS-Testversion: alle Funktionen sind immer freigeschaltet (kein Abo).
    let isPro = true
}
