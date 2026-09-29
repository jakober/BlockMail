import Foundation

/// Datei-Helfer zum Übergabe-Vertrag (Port von `materialize`,
/// `cleanupOldFiles`, `signedName` aus AttachmentEditing.kt) und der
/// Einstieg für von außen geöffnete Dokumente (Port von ViewerActivity).
extension DocumentEditing {

    /// Von außen geöffnetes Dokument (Dateien-App, „Öffnen in …“,
    /// `AppRouter.openDocument`) für den Editor vormerken. Der Aufrufer
    /// navigiert danach zu `.editor`.
    /// - Returns: false, wenn der Dateityp nicht bearbeitbar ist.
    @MainActor
    @discardableResult
    static func openExternal(_ url: URL) -> Bool {
        let name = url.lastPathComponent.isEmpty ? "Dokument.pdf" : url.lastPathComponent
        let mime = MailRepository.effectiveMime(name, "")
        guard isEditable(mime: mime, name: name) else { return false }
        pending = Source(name: name, mime: mime, data: nil, url: url, replyUid: nil,
                         account: "", origin: .externalView, canOverwrite: false, aiDocument: false)
        return true
    }

    /// Dateiname des Ergebnisses: „Vertrag.pdf“ → „Vertrag-signiert.pdf“.
    static func signedName(_ name: String) -> String {
        let ns = name as NSString
        let ext = ns.pathExtension
        let base = ns.deletingPathExtension
        if ext.isEmpty || base.isEmpty { return name + "-signiert" }
        return base + "-signiert." + ext
    }

    static func safeFileName(_ name: String) -> String {
        let s = name.replacingOccurrences(of: "[/\\\\:*?\"<>|]", with: "_", options: .regularExpression)
        return s.isEmpty ? "Dokument" : s
    }

    /// Arbeitsordner im Cache (editor, exports …).
    static func cacheDir(_ name: String) -> URL {
        let base = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// Legt die Arbeitsdatei an: Bytes aus der Mail schreiben bzw. die Datei
    /// von außen kopieren (mit Sicherheitsbereich).
    static func materialize(_ src: Source) throws -> URL {
        let dir = cacheDir("editor")
        let out = dir.appendingPathComponent("src_\(Int64(Date().timeIntervalSince1970 * 1000))_\(safeFileName(src.name))")
        if let data = src.data {
            try data.write(to: out, options: .atomic)
        } else if let url = src.url {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            try? FileManager.default.removeItem(at: out)
            try FileManager.default.copyItem(at: url, to: out)
        } else {
            throw DocumentPDF.OpError(message: "Quelle ohne Inhalt")
        }
        return out
    }

    /// Räumt liegen gebliebene Arbeitsdateien weg (Abstürze, Abbrüche).
    static func cleanupOldFiles(maxAge: TimeInterval = 24 * 60 * 60) {
        let fm = FileManager.default
        let now = Date()
        for name in ["editor", "exports", "newpdf"] {
            let dir = cacheDir(name)
            guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { continue }
            for f in files {
                let date = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate ?? now
                if now.timeIntervalSince(date) > maxAge { try? fm.removeItem(at: f) }
            }
        }
    }
}
