import Foundation

/// Zeilenverbindung über Foundation-Streams. Wird nur für SMTP mit STARTTLS
/// (Port 587, z. B. Outlook/Office 365) gebraucht: Anders als NWConnection
/// lassen sich Foundation-Streams nach dem Klartext-Begrüßungsdialog auf TLS
/// umschalten (`startTLS`).
final class StreamConnection: LineIO, @unchecked Sendable {

    private let host: String
    private let port: Int
    private var input: InputStream?
    private var output: OutputStream?
    private let queue = DispatchQueue(label: "blockmail.stream")
    private var buffer = Data()
    private let lock = NSLock()
    private var closed = false
    var readTimeout: TimeInterval = 60

    init(host: String, port: Int) {
        self.host = host
        self.port = port
    }

    func open() async throws {
        var inS: InputStream?
        var outS: OutputStream?
        Stream.getStreamsToHost(withName: host, port: port, inputStream: &inS, outputStream: &outS)
        guard let inS, let outS else { throw MailNetError.connectFailed(L("err_no_connection")) }
        input = inS
        output = outS
        try await runBlocking(timeout: 20) {
            inS.open()
            outS.open()
            let start = Date()
            while inS.streamStatus == .opening || outS.streamStatus == .opening {
                if Date().timeIntervalSince(start) > 20 { throw MailNetError.timeout }
                Thread.sleep(forTimeInterval: 0.02)
            }
            if inS.streamStatus == .error || outS.streamStatus == .error {
                throw MailNetError.connectFailed(
                    inS.streamError?.localizedDescription ?? L("err_no_connection")
                )
            }
        }
    }

    /// Schaltet die bestehende Verbindung auf TLS um (nach „220 Ready“).
    func startTLS() {
        let settings: [String: Any] = [
            kCFStreamSSLLevel as String: kCFStreamSocketSecurityLevelNegotiatedSSL as String,
            kCFStreamSSLPeerName as String: host
        ]
        input?.setProperty(settings, forKey: Stream.PropertyKey(kCFStreamPropertySSLSettings as String))
        output?.setProperty(settings, forKey: Stream.PropertyKey(kCFStreamPropertySSLSettings as String))
    }

    func close() {
        lock.lock(); closed = true; lock.unlock()
        input?.close()
        output?.close()
    }

    private func runBlocking<T>(timeout: TimeInterval, _ work: @escaping () throws -> T) async throws -> T {
        try await withCheckedThrowingContinuation { cont in
            let once = ResumeOnce()
            queue.async {
                do {
                    let v = try work()
                    if once.claim() { cont.resume(returning: v) }
                } catch {
                    if once.claim() { cont.resume(throwing: error) }
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { [weak self] in
                if once.claim() {
                    self?.close()
                    cont.resume(throwing: MailNetError.timeout)
                }
            }
        }
    }

    func write(_ data: Data) async throws {
        guard let out = output else { throw MailNetError.closed }
        try await runBlocking(timeout: readTimeout) {
            var offset = 0
            try data.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                while offset < data.count {
                    let n = out.write(base + offset, maxLength: data.count - offset)
                    if n <= 0 { throw MailNetError.closed }
                    offset += n
                }
            }
        }
    }

    private func receiveMore() async throws {
        guard let inS = input else { throw MailNetError.closed }
        let chunk: Data = try await runBlocking(timeout: readTimeout) {
            var buf = [UInt8](repeating: 0, count: 64 * 1024)
            let n = inS.read(&buf, maxLength: buf.count)
            if n <= 0 { throw MailNetError.closed }
            return Data(buf[0..<n])
        }
        buffer.append(chunk)
    }

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

    func readBytes(_ count: Int) async throws -> Data {
        while buffer.count < count { try await receiveMore() }
        let out = buffer.subdata(in: buffer.startIndex..<(buffer.startIndex + count))
        buffer.removeSubrange(buffer.startIndex..<(buffer.startIndex + count))
        return out
    }
}
