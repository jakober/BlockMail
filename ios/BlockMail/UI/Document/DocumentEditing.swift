import Foundation

/// Übergabe an den Dokument-Editor (Port von `AttachmentEditing` — nur der
/// gemeinsame Vertrag; die Editor-Logik liegt in UI/Document).
enum DocumentEditing {

    /// Woher das Dokument kommt — bestimmt die Ausgabewege.
    enum Origin { case mail, externalView, externalEdit, externalShare }

    /// Dokument, das gleich bearbeitet wird: Daten (Mail-Anhang) ODER Datei-URL (von außen).
    struct Source {
        var name: String
        var mime: String
        var data: Data?
        var url: URL?
        /// Mail, auf die danach geantwortet werden soll (nil = keine).
        var replyUid: Int64?
        /// Konto der Mail ("" = aktives Konto).
        var account: String = ""
        var origin: Origin = .mail
        var canOverwrite: Bool = false
        /// Per KI erstelltes Dokument? Dann bietet der Editor „Mit KI überarbeiten“ an.
        var aiDocument: Bool = false
    }

    /// Fertig bearbeitetes Dokument für das Verfassen-Fenster.
    struct Result {
        var url: URL
        var name: String
        var size: Int64
    }

    @MainActor static var pending: Source?
    @MainActor static var pendingResult: Result?

    /// Bearbeitbar (PDF oder Bild)?
    static func isEditable(mime: String, name: String) -> Bool {
        isPdf(mime: mime, name: name) || mime.lowercased().hasPrefix("image/") ||
            ["jpg", "jpeg", "png", "heic", "webp"].contains((name as NSString).pathExtension.lowercased())
    }

    static func isPdf(mime: String, name: String) -> Bool {
        mime.lowercased() == "application/pdf" || name.lowercased().hasSuffix(".pdf")
    }
}
