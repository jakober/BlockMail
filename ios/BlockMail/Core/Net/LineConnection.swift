import Foundation
import Network

/// Fehler der Netzwerkschicht (IMAP/SMTP). Die Texte landen über
/// `MailRepository.friendlyError` in der Oberfläche.
enum MailNetError: LocalizedError {
    case connectFailed(String)
    case timeout
    case closed
    case authFailed(String)
    case protocolError(String)
    case commandFailed(String)

    var errorDescription: String? {
        switch self {
        case .connectFailed(let s): return s
        case .timeout: return L("err_timeout")
        case .closed: return L("err_connection_closed")
        case .authFailed(let s): return s
        case .protocolError(let s): return s
        case .commandFailed(let s): return s
        }
    }

    /// Verbindungs-/Anmeldefehler (wie `isConnectivityError` der Android-App).
    var isConnectivity: Bool {
        switch self {
        case .connectFailed, .timeout, .closed, .authFailed: return true
        default: return false
        }
    }
}

/// Zeilenorientierte TLS-Verbindung auf Basis von Network.framework —
/// gemeinsames Fundament für den IMAP- und den SMTP-Client.
///
/// Nicht für parallele Nutzung gedacht: Jede Verbindung gehört genau einem
/// Ablauf (wie ein JavaMail-Store). Nur `write` darf zusätzlich von einer
/// zweiten Task kommen (IMAP `DONE` beendet ein laufendes IDLE).
protocol LineIO: AnyObject, Sendable {
    func open() async throws
    func close()
    func write(_ data: Data) async throws
    func readLine() async throws -> Data
    func readBytes(_ count: Int) async throws -> Data
}

extension LineIO {
    func write(_ string: String) async throws {
        try await write(Data(string.utf8))
    }

    /// Zeile als Text ohne CRLF (für SMTP und Status-Zeilen).
    func readTextLine() async throws -> String {
        let data = try await readLine()
        let s = String(decoding: data, as: UTF8.self)
        return s.trimmingCharacters(in: CharacterSet(charactersIn: "\r\n"))
    }
}

final class LineConnection: LineIO, @unchecked Sendable {

    private let connection: NWConnection
    private let queue = DispatchQueue(label: "blockmail.net")
    private var buffer = Data()
    private var closed = false
    private let lock = NSLock()

    /// Lese-Zeitlimit in Sekunden (IDLE-Verbindungen setzen es hoch).
    var readTimeout: TimeInterval

    let host: String
    let port: Int

    init(host: String, port: Int, tls: Bool = true, readTimeout: TimeInterval = 60) {
        self.host = host
        self.port = port
        self.readTimeout = readTimeout
        let tcp = NWProtocolTCP.Options()
        tcp.connectionTimeout = 15
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 60
        let params: NWParameters
        if tls {
            params = NWParameters(tls: NWProtocolTLS.Options(), tcp: tcp)
        } else {
            params = NWParameters(tls: nil, tcp: tcp)
        }
        connection = NWConnection(
            host: NWEndpoint.Host(host),
            port: NWEndpoint.Port(integerLiteral: UInt16(clamping: port)),
            using: params
        )
    }

    /// Baut die Verbindung auf (TLS-Handshake inklusive).
    func open() async throws {
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            let resumed = ResumeOnce()
            connection.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if resumed.claim() { cont.resume() }
                case .failed(let err):
                    self?.markClosed()
                    if resumed.claim() {
                        cont.resume(throwing: MailNetError.connectFailed(Self.describe(err)))
                    }
                case .waiting(let err):
                    // Kein Netz / Host nicht erreichbar: nicht endlos warten
                    if resumed.claim() {
                        self?.connection.cancel()
                        cont.resume(throwing: MailNetError.connectFailed(Self.describe(err)))
                    }
                case .cancelled:
                    self?.markClosed()
                    if resumed.claim() { cont.resume(throwing: MailNetError.closed) }
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + 20) { [weak self] in
                if resumed.claim() {
                    self?.connection.cancel()
                    cont.resume(throwing: MailNetError.timeout)
                }
            }
        }
    }

    private static func describe(_ err: NWError) -> String {
        switch err {
        case .dns: return L("err_no_connection")
        case .posix(let code) where code == .ECONNREFUSED || code == .ENETUNREACH
            || code == .EHOSTUNREACH || code == .ETIMEDOUT || code == .ENETDOWN:
            return L("err_no_connection")
        case .tls: return L("err_tls")
        default: return err.localizedDescription
        }
    }

    private func markClosed() {
        lock.lock(); closed = true; lock.unlock()
    }

    var isClosed: Bool {
        lock.lock(); defer { lock.unlock() }
        return closed
    }

    func close() {
        markClosed()
        connection.cancel()
    }

    // MARK: Schreiben

    func write(_ data: Data) async throws {
        if isClosed { throw MailNetError.closed }
        try await withCheckedThrowingContinuation { (cont: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { err in
                if let err {
                    cont.resume(throwing: MailNetError.connectFailed(Self.describe(err)))
                } else {
                    cont.resume()
                }
            })
        }
    }

    // MARK: Lesen

    /// Holt mindestens ein weiteres Datenpaket in den Puffer.
    private func receiveMore() async throws {
        if isClosed { throw MailNetError.closed }
        let timeout = readTimeout
        let chunk: Data = try await withCheckedThrowingContinuation { cont in
            let resumed = ResumeOnce()
            connection.receive(minimumIncompleteLength: 1, maximumLength: 256 * 1024) {
                [weak self] data, _, isComplete, error in
                guard resumed.claim() else { return }
                if let error {
                    self?.markClosed()
                    cont.resume(throwing: MailNetError.connectFailed(Self.describe(error)))
                } else if let data, !data.isEmpty {
                    cont.resume(returning: data)
                } else if isComplete {
                    self?.markClosed()
                    cont.resume(throwing: MailNetError.closed)
                } else {
                    cont.resume(returning: Data())
                }
            }
            queue.asyncAfter(deadline: .now() + timeout) { [weak self] in
                if resumed.claim() {
                    // Lautlos gestorbene Verbindung: abbrechen, damit der
                    // Aufrufer neu verbinden kann
                    self?.close()
                    cont.resume(throwing: MailNetError.timeout)
                }
            }
        }
        buffer.append(chunk)
    }

    /// Liest eine Zeile inklusive CRLF (als Rohdaten).
    func readLine() async throws -> Data {
        while true {
            if let idx = buffer.firstRange(of: Data([13, 10])) {
                let line = buffer.subdata(in: buffer.startIndex..<idx.upperBound)
                buffer.removeSubrange(buffer.startIndex..<idx.upperBound)
                return line
            }
            try await receiveMore()
        }
    }

    /// Liest genau `count` Bytes (IMAP-Literale).
    func readBytes(_ count: Int) async throws -> Data {
        while buffer.count < count {
            try await receiveMore()
        }
        let out = buffer.subdata(in: buffer.startIndex..<(buffer.startIndex + count))
        buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + count))
        return out
    }
}

/// Kleiner Helfer: stellt sicher, dass eine Continuation genau einmal
/// fortgesetzt wird (Zeitlimit und Ergebnis konkurrieren).
final class ResumeOnce: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if done { return false }
        done = true
        return true
    }
}
