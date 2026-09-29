import Foundation
import Observation

/// Zentrale Navigations-Anforderungen von außen (Benachrichtigung, Widget,
/// Quick Action, mailto:-Link, geöffnetes PDF). Entspricht den Intent-Extras
/// der Android-MainActivity („open_uid“, „open_account“, Compose-Prefill …).
@MainActor
@Observable
final class AppRouter {
    static let shared = AppRouter()

    /// Mail direkt öffnen (uid, Konto-Kennung).
    var openMail: (uid: Int64, account: String)?
    /// Verfassen-Fenster mit Vorbelegung öffnen.
    var compose: ComposePrefill?
    /// Von außen geöffnetes Dokument (PDF) im Editor zeigen.
    var openDocument: URL?
    /// „PDF erstellen“-Dialog öffnen (Quick Action).
    var newPdf = false

    private init() {}

    /// Verarbeitet blockmail://- und mailto:-Links sowie Datei-URLs.
    func handle(url: URL) {
        if url.isFileURL {
            openDocument = url
            return
        }
        if url.scheme?.lowercased() == "mailto" {
            compose = Self.parseMailto(url)
            return
        }
        guard url.scheme == "blockmail" else { return }
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        switch url.host {
        case "mail":
            if let uid = items.first(where: { $0.name == "uid" })?.value.flatMap(Int64.init) {
                openMail = (uid, items.first(where: { $0.name == "account" })?.value ?? "")
            }
        case "compose":
            compose = ComposePrefill()
        case "newpdf":
            newPdf = true
        default:
            break
        }
    }

    static func parseMailto(_ url: URL) -> ComposePrefill {
        var p = ComposePrefill()
        let s = url.absoluteString
        let rest = s.dropFirst("mailto:".count)
        let parts = rest.split(separator: "?", maxSplits: 1)
        p.to = String(parts.first ?? "").removingPercentEncoding ?? ""
        if parts.count > 1 {
            for pair in parts[1].split(separator: "&") {
                let kv = pair.split(separator: "=", maxSplits: 1)
                guard kv.count == 2 else { continue }
                let v = String(kv[1]).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? ""
                switch kv[0].lowercased() {
                case "cc": p.cc = v
                case "bcc": p.bcc = v
                case "subject": p.subject = v
                case "body": p.body = v
                case "to": p.to = p.to.isEmpty ? v : p.to + ", " + v
                default: break
                }
            }
        }
        return p
    }
}
