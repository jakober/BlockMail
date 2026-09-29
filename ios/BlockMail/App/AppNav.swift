import Foundation
import Observation

/// Ziele der Navigation (Port der NavHost-Routen aus `MainActivity.kt`).
enum Route: Hashable {
    /// Mail-Detail. `folder` nil = aktuell angezeigter Ordner; `fallback`
    /// für Treffer außerhalb der geladenen Liste (Suche/KI/„Antwort ansehen“).
    case detail(uid: Int64, account: String, folder: MailFolder?, fallback: MailMessage?)
    case settings
    case setup
    case welcome
    case stats
    case attachments
    /// Dokument-/PDF-Editor (Quelle liegt in `DocumentEditing.pending`).
    case editor
}

/// Anforderung für das Verfassen-Fenster (Port von „compose?replyTo=&draft=&forward=“).
struct ComposeRequest: Identifiable, Equatable {
    let id = UUID()
    var replyTo: MailMessage? = nil
    /// „Allen antworten“: (An, CC) — sonst nil.
    var replyAll: (String, String)? = nil
    var forward: MailMessage? = nil
    /// Ordner der Originalmail (für Antwort/Weiterleiten aus Archiv/Suche).
    var sourceFolder: MailFolder? = nil
    var draftId: Int64? = nil
    var prefill: ComposePrefill? = nil

    static func == (a: ComposeRequest, b: ComposeRequest) -> Bool { a.id == b.id }
}

/// Navigationszustand der App (Stack + Verfassen-Fenster + „PDF erstellen“).
@MainActor
@Observable
final class AppNav {
    static let shared = AppNav()

    var path: [Route] = []
    var compose: ComposeRequest?
    var showNewPdf = false
    /// Zweispaltig: in der rechten Spalte gezeigte Mail.
    var selected: Route?

    private init() {}

    func push(_ r: Route) { path.append(r) }
    func pop() { if !path.isEmpty { path.removeLast() } }
    func popToRoot() { path.removeAll() }

    func openMail(_ m: MailMessage, folder: MailFolder? = nil, fallback: Bool = false) {
        push(.detail(uid: m.uid, account: m.account, folder: folder, fallback: fallback ? m : nil))
    }
}
