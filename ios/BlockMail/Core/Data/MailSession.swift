import Foundation

/// Standard-Ordner (Port von `MailRepository.MailFolder`).
enum MailFolder: String, CaseIterable, Codable, Hashable {
    case INBOX, SENT, DRAFTS, ARCHIVE, TRASH

    var label: String {
        switch self {
        case .INBOX: return L("folder_inbox")
        case .SENT: return L("folder_sent")
        case .DRAFTS: return L("folder_drafts")
        case .ARCHIVE: return L("folder_archive")
        case .TRASH: return L("folder_trash")
        }
    }

    /// IMAP-Attribut (RFC 6154) und bekannte Namen verschiedener Anbieter.
    var specialUse: String? {
        switch self {
        case .INBOX: return nil
        case .SENT: return "\\sent"
        case .DRAFTS: return "\\drafts"
        case .ARCHIVE: return "\\all"
        case .TRASH: return "\\trash"
        }
    }

    var candidates: [String] {
        switch self {
        case .INBOX: return ["INBOX"]
        case .SENT: return ["[Gmail]/Gesendet", "[Gmail]/Sent Mail", "[Google Mail]/Gesendet",
                            "[Google Mail]/Sent Mail", "Gesendet", "Sent", "Sent Items",
                            "Gesendete Objekte", "Gesendete Elemente", "INBOX.Sent", "INBOX.Gesendet"]
        case .DRAFTS: return ["[Gmail]/Entwürfe", "[Gmail]/Drafts", "[Google Mail]/Entwürfe",
                              "[Google Mail]/Drafts", "Entwürfe", "Drafts", "Entwurf", "INBOX.Drafts"]
        case .ARCHIVE: return ["[Gmail]/Alle Nachrichten", "[Gmail]/All Mail", "[Google Mail]/Alle Nachrichten",
                               "[Google Mail]/All Mail", "Archiv", "Archive", "INBOX.Archive"]
        case .TRASH: return ["[Gmail]/Papierkorb", "[Gmail]/Trash", "[Google Mail]/Papierkorb",
                             "[Google Mail]/Trash", "Papierkorb", "Trash", "Deleted Items",
                             "Gelöschte Elemente", "Gelöscht", "INBOX.Trash"]
        }
    }
}

/// Eine angemeldete IMAP-Verbindung zu einem Konto samt Ordner-Zuordnung.
final class IMAPSession: @unchecked Sendable {
    let client: IMAPClient
    let account: Prefs.Account
    private var folderList: [IMAPFolderInfo]?

    init(client: IMAPClient, account: Prefs.Account) {
        self.client = client
        self.account = account
    }

    func folders() async throws -> [IMAPFolderInfo] {
        if let f = folderList { return f }
        let f = try await client.list()
        folderList = f
        return f
    }

    /// Server-Name eines Standard-Ordners (nil, wenn es ihn nicht gibt).
    func resolve(_ folder: MailFolder) async -> String? {
        if folder == .INBOX { return "INBOX" }
        guard let list = try? await folders() else { return nil }
        if let use = folder.specialUse,
           let f = list.first(where: { $0.attributes.contains(use) && $0.selectable }) {
            return f.name
        }
        // XLIST-Altlast von Gmail: \AllMail
        if folder == .ARCHIVE, let f = list.first(where: { $0.attributes.contains("\\allmail") }) {
            return f.name
        }
        for cand in folder.candidates {
            if let f = list.first(where: {
                $0.displayName.caseInsensitiveCompare(cand) == .orderedSame && $0.selectable
            }) { return f.name }
        }
        return nil
    }

    /// Wie `resolve`, legt aber einen fehlenden Archiv-Ordner an (Android löschte
    /// sonst beim Archivieren ohne Archiv-Ordner die Mail nur).
    func resolveOrCreate(_ folder: MailFolder) async throws -> String {
        if let name = await resolve(folder) { return name }
        let name = folder == .ARCHIVE ? L("folder_archive_create") : folder.candidates.last ?? folder.rawValue
        let encoded = ModifiedUTF7.encode(name)
        try? await client.create(encoded)
        folderList = nil
        return encoded
    }

    func select(_ folder: MailFolder, readOnly: Bool) async throws {
        guard let name = await resolve(folder) else {
            throw MailNetError.commandFailed(L("err_folder_not_found", folder.label))
        }
        try await client.select(name, readOnly: readOnly)
    }

    func select(custom path: String, readOnly: Bool) async throws {
        try await client.select(path, readOnly: readOnly)
    }
}

/// Verbindungs-Pool: eine wiederverwendete Verbindung je Konto, Zugriffe
/// werden nacheinander ausgeführt. Bricht eine Verbindung weg, wird einmal
/// neu verbunden (Android öffnete für jede Aktion einen neuen Store —
/// das wäre auf iOS spürbar langsamer).
actor MailSessionPool {
    static let shared = MailSessionPool()

    private var sessions: [String: IMAPSession] = [:]
    private var locks: [String: AsyncLock] = [:]

    private func lock(for key: String) -> AsyncLock {
        if let l = locks[key] { return l }
        let l = AsyncLock()
        locks[key] = l
        return l
    }

    private func take(_ key: String) -> IMAPSession? { sessions[key] }
    private func put(_ key: String, _ s: IMAPSession?) { sessions[key] = s }

    /// Führt `body` mit der Verbindung des Kontos aus ("" = aktives Konto).
    func with<T>(account: String = "", _ body: (IMAPSession) async throws -> T) async throws -> T {
        let acc = try MailSessionPool.resolveAccount(account)
        let key = acc.email.lowercased()
        let l = lock(for: key)
        await l.lock()
        defer { Task { await l.unlock() } }
        var attempt = 0
        while true {
            attempt += 1
            let session: IMAPSession
            if let s = take(key), !s.client.isClosed {
                session = s
            } else {
                session = try await MailSessionPool.connect(acc)
                put(key, session)
            }
            do {
                return try await body(session)
            } catch let e as MailNetError where e.isConnectivity && attempt == 1 && !isAuth(e) {
                session.client.close()
                put(key, nil)
                continue
            } catch {
                if let e = error as? MailNetError, e.isConnectivity {
                    session.client.close()
                    put(key, nil)
                }
                throw error
            }
        }
    }

    private func isAuth(_ e: MailNetError) -> Bool {
        if case .authFailed = e { return true }
        return false
    }

    /// Alle Verbindungen schließen (Kontowechsel, App im Hintergrund).
    func closeAll() {
        for s in sessions.values { s.client.close() }
        sessions.removeAll()
    }

    func close(account: String) {
        let key = account.lowercased()
        sessions[key]?.client.close()
        sessions[key] = nil
    }

    // MARK: Verbindungsaufbau

    static func resolveAccount(_ account: String) throws -> Prefs.Account {
        let p = Prefs.shared
        if account.isEmpty || account.caseInsensitiveCompare(p.email) == .orderedSame {
            return p.activeAccount
        }
        guard let acc = p.accounts().first(where: { $0.email.caseInsensitiveCompare(account) == .orderedSame })
        else { throw MailNetError.commandFailed(L("err_account_not_found", account)) }
        return acc
    }

    /// Neue, angemeldete Verbindung (auch für IDLE und Verbindungstests).
    static func connect(_ acc: Prefs.Account, idle: Bool = false) async throws -> IMAPSession {
        let client = IMAPClient(host: acc.imapHost, port: acc.imapPort, idleMode: idle)
        try await client.connect()
        do {
            if acc.authMethod == "oauth" {
                let token = try await GoogleAuth.freshAccessToken(for: acc.refreshToken)
                try await client.authenticateXOAuth2(user: acc.loginName(), accessToken: token)
            } else {
                try await client.login(user: acc.loginName(), password: acc.appPassword)
            }
        } catch {
            client.close()
            throw error
        }
        return IMAPSession(client: client, account: acc)
    }
}

/// Einfache asynchrone Sperre (FIFO).
actor AsyncLock {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func lock() async {
        if !locked { locked = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }
}
