import SwiftUI
import UIKit
import WebKit

// HTML-Ansicht der Mail-Detailseite (Port von HtmlMailView/FitWebView/
// buildMailPageHtml aus DetailScreen.kt). JavaScript bleibt aus; Links öffnen
// in Safari, App-Aktionen im Seitenkopf laufen über blockmail://-Links.

/// Nutzersichtbare Texte für die HTML-Seite (Android: MailPageTexts).
struct DetailPageTexts: Equatable {
    var summarize: String
    var phishingWarning: String
    var phishingAdvice: String
    var notPhishingLink: String
    var attachmentsLabel: String
    var viewReply: String
}

/// Baut die komplette Mail-Seite: Kopf (Betreff, Absender, Datum), ggf.
/// Phishing-Warnung, KI-Knopf und Anhänge liegen IM Seiteninhalt und scrollen
/// mit. Aktionen laufen über blockmail://-Links.
enum DetailPageBuilder {

    private static func esc(_ s: String) -> String { HTMLText.escape(s) }

    static func build(mail: MailMessage,
                      body: MailRepository.MailBody,
                      phishing: PhishingCheck.Result?,
                      aiAvailable: Bool,
                      dark: Bool,
                      texts: DetailPageTexts,
                      senderIcon: String?,
                      accent: String,
                      repliedText: String?,
                      showReplyLink: Bool) -> String {
        let orange = accent
        // Fester Kopf-Hintergrund je App-Design (Mails bringen oft eigene
        // Hintergründe mit — der Kopf bleibt so immer lesbar)
        let headerBg = dark ? "#101012" : "#ffffff"
        let titleColor = dark ? "#F2F2F2" : "#1a1a1a"
        let subColor = dark ? "#A8A8A8" : "#8a8a8a"
        let chipBg = dark ? "#2A2A2E" : "#f1f1f1"
        let chipColor = dark ? "#E4E4E4" : "#333"
        let hrColor = dark ? "#2A2A2E" : "#e5e5e5"
        var sb = ""
        sb += "<div style=\"font-family:-apple-system,sans-serif;background:\(headerBg);"
        sb += "padding:12px 12px 2px 12px;\">"
        // Betreff
        sb += "<div style=\"font-size:21px;font-weight:700;color:\(titleColor);"
        sb += "line-height:1.3;margin:2px 0 12px 0;\">"
        sb += esc(mail.subject)
        sb += "</div>"
        // Avatar: vorab geladenes Logo (als data:-URI) — sonst Initialen-Kreis
        let avatar: String
        if let icon = senderIcon {
            avatar = "<img src=\"\(esc(icon))\" " +
                "style=\"width:42px;height:42px;min-width:42px;border-radius:21px;" +
                "background:\(dark ? "#2A2A2E" : "#f2f2f2");object-fit:contain;\">"
        } else {
            let first = mail.from.first ?? mail.fromAddress.first ?? "?"
            let initial = esc(String(first).uppercased())
            avatar = "<div style=\"width:42px;height:42px;min-width:42px;border-radius:21px;" +
                "background:\(orange);color:#fff;font-size:19px;font-weight:600;" +
                "display:flex;align-items:center;justify-content:center;\">\(initial)</div>"
        }
        let date = DetailFormat.longDate(mail.date)
        sb += "<div style=\"display:flex;align-items:center;margin-bottom:12px;"
        sb += "flex-wrap:wrap;row-gap:8px;\">"
        sb += avatar
        sb += "<div style=\"margin-left:12px;min-width:0;flex:1;\">"
        sb += "<div style=\"font-size:15px;font-weight:600;color:\(titleColor);\">"
        sb += esc(mail.from) + "</div>"
        sb += "<div style=\"font-size:12.5px;color:\(subColor);\">"
        sb += esc(mail.fromAddress) + "</div>"
        sb += "<div style=\"font-size:12.5px;color:\(subColor);\">"
        sb += esc(date) + "</div>"
        sb += "</div>"
        if aiAvailable {
            sb += "<a href=\"blockmail://summarize\" style=\""
            sb += "background:\(orange);color:#fff;border-radius:16px;"
            sb += "padding:6px 12px;text-decoration:none;font-size:12.5px;"
            sb += "font-weight:600;white-space:nowrap;margin-left:8px;\">"
            sb += "✨ " + esc(texts.summarize) + "</a>"
        }
        sb += "</div>"
        // Beantwortet-Zeile mit Sprung zur gesendeten Antwort
        if let replied = repliedText {
            sb += "<div style=\"margin:0 0 10px 0;font-size:13px;color:\(subColor);\">"
            sb += "↩ " + esc(replied)
            if showReplyLink {
                sb += " · <a href=\"blockmail://openreply\" style=\""
                sb += "color:\(orange);font-weight:600;text-decoration:none;\">"
                sb += esc(texts.viewReply) + "</a>"
            }
            sb += "</div>"
        }
        if let phishing, phishing.suspicious {
            sb += "<div style=\"background:#b3261e;color:#fff;border-radius:14px;"
            sb += "padding:12px 14px;margin:8px 0;font-size:13px;line-height:1.55;\">"
            sb += "<b>⚠️ " + esc(texts.phishingWarning) + "</b><br>"
            for reason in phishing.reasons.prefix(3) {
                sb += "• " + esc(reason) + "<br>"
            }
            sb += esc(texts.phishingAdvice) + "<br>"
            sb += "<a href=\"blockmail://notphishing\" style=\"color:#fff;font-weight:600;\">"
            sb += esc(texts.notPhishingLink) + "</a>"
            sb += "</div>"
        }
        // Anhänge: <details>/<summary> klappt ohne JavaScript auf
        if !body.attachments.isEmpty {
            sb += "<style>summary::-webkit-details-marker{display:none}</style>"
            sb += "<details style=\"margin:2px 0 8px 0;\">"
            sb += "<summary style=\"cursor:pointer;color:\(chipColor);"
            sb += "font-size:15.5px;font-weight:600;padding:8px 0;list-style:none;\">"
            sb += "📎 " + esc(texts.attachmentsLabel)
            sb += " <span style=\"font-size:13px;\">▼</span></summary>"
            sb += "<div style=\"margin-top:6px;line-height:2.4;\">"
            for (i, att) in body.attachments.enumerated() {
                sb += "<a href=\"blockmail://att/\(i)\" style=\"display:inline-block;background:\(chipBg);"
                sb += "color:\(chipColor);border-radius:16px;padding:7px 13px;"
                sb += "text-decoration:none;font-size:12.5px;margin-right:8px;\">"
                sb += "📎 " + esc(att.name) + "</a>"
            }
            sb += "</div></details>"
        }
        sb += "<hr style=\"border:none;border-top:1px solid \(hrColor);margin:6px 0 0 0;\">"
        sb += "</div>"
        // Marker: bei zu breiten Mails wird NUR dieser Container verkleinert
        sb += "<div class=\"bm-mailbody\" style=\"padding:8px;\">"
        sb += body.html ?? ""
        sb += "</div>"
        return sb
    }
}

/// WKWebView, die Breitenänderungen (Drehen, Split View) meldet.
final class DetailFitWebView: WKWebView {
    var onWidthChanged: (() -> Void)?
    private var lastWidth: CGFloat = 0

    override func layoutSubviews() {
        super.layoutSubviews()
        let w = bounds.width
        if w <= 0 { return }
        if lastWidth <= 0 {
            lastWidth = w
        } else if abs(w - lastWidth) > 1 {
            lastWidth = w
            Task { @MainActor [weak self] in self?.onWidthChanged?() }
        }
    }
}

/// Stellt HTML-Mails dar (eigener Scrollbereich, Zoom, Links in Safari).
/// Zu breite Mails werden nach dem Rendern gemessen und per CSS-zoom NUR im
/// Mail-Inhalt eingepasst — der Kopf behält seine Größe (wie Android).
struct DetailHTMLView: UIViewRepresentable {
    let html: String
    let fontScale: Int
    let onAppLink: (URL) -> Void

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIView(context: Context) -> DetailFitWebView {
        let config = WKWebViewConfiguration()
        let prefs = WKWebpagePreferences()
        prefs.allowsContentJavaScript = false
        config.defaultWebpagePreferences = prefs
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.allowsInlineMediaPlayback = false
        let wv = DetailFitWebView(frame: .zero, configuration: config)
        wv.navigationDelegate = context.coordinator
        wv.isOpaque = true
        wv.backgroundColor = .white
        wv.scrollView.backgroundColor = .white
        wv.scrollView.minimumZoomScale = 1
        wv.scrollView.maximumZoomScale = 5
        // Platz unter der Mail, damit der Antworten-Knopf nichts verdeckt
        wv.scrollView.contentInset = UIEdgeInsets(top: 0, left: 0, bottom: 88, right: 0)
        wv.allowsLinkPreview = true
        wv.alpha = 0
        let coordinator = context.coordinator
        coordinator.webView = wv
        wv.onWidthChanged = { [weak coordinator] in coordinator?.widthChanged() }
        return wv
    }

    func updateUIView(_ wv: DetailFitWebView, context: Context) {
        context.coordinator.onAppLink = onAppLink
        context.coordinator.setContent(html, fontScale: fontScale)
    }

    static func dismantleUIView(_ uiView: DetailFitWebView, coordinator: Coordinator) {
        uiView.stopLoading()
        uiView.navigationDelegate = nil
        uiView.onWidthChanged = nil
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        weak var webView: DetailFitWebView?
        var onAppLink: ((URL) -> Void)?

        private var rawHtml: String?
        private var fontScale = 100
        /// Einpass-Faktor für den Mail-Inhalt (1 = unverändert).
        private var zoom: CGFloat = 1
        private var fitPasses = 0
        /// Zähler je Ladevorgang — alte Messungen verfallen.
        private var generation = 0

        func setContent(_ html: String, fontScale: Int) {
            guard html != rawHtml || fontScale != self.fontScale else { return }
            rawHtml = html
            self.fontScale = fontScale
            zoom = 1
            fitPasses = 0
            // Neue Mail: unsichtbar starten, bis die Messung fertig ist
            webView?.alpha = 0
            load()
        }

        func widthChanged() {
            guard rawHtml != nil else { return }
            zoom = 1
            fitPasses = 0
            webView?.alpha = 0
            webView?.scrollView.setZoomScale(1, animated: false)
            load()
        }

        private func wrapped(_ raw: String) -> String {
            let zoomCss = zoom < 0.999
                ? ".bm-mailbody { zoom: " + String(format: "%.3f", Double(zoom)) + "; }"
                : ""
            return """
            <!DOCTYPE html><html><head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1, shrink-to-fit=no">
            <style>
              html { -webkit-text-size-adjust: \(fontScale)%; }
              body { margin: 0; word-wrap: break-word; background: #ffffff; }
              img { max-width: 100% !important; height: auto !important; }
              \(zoomCss)
            </style>
            </head><body>\(raw)</body></html>
            """
        }

        private func load() {
            guard let wv = webView, let raw = rawHtml else { return }
            generation += 1
            wv.loadHTMLString(wrapped(raw), baseURL: nil)
            // Sicherheitsnetz: lieber uneingepasst als dauerhaft unsichtbar
            after(1.6) { [weak self] in self?.show() }
        }

        private func after(_ seconds: Double, _ block: @escaping @MainActor () -> Void) {
            Task { @MainActor in
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                block()
            }
        }

        private func show() {
            guard let wv = webView, wv.alpha < 1 else { return }
            UIView.animate(withDuration: 0.12) { wv.alpha = 1 }
        }

        private func measure(_ gen: Int) {
            guard gen == generation, let wv = webView else { return }
            let width = wv.bounds.width
            let sv = wv.scrollView
            let contentWidth = sv.contentSize.width / max(sv.zoomScale, 0.01)
            // Kleine Toleranz: 8 pt Überstand sind kein Grund zu verkleinern
            if width > 0, contentWidth > width + 8, fitPasses < 4 {
                fitPasses += 1
                let factor = min(1, max(0.25, width / contentWidth))
                // Multiplizieren: gemessen wird beim aktuellen Zoom
                zoom = min(1, max(0.25, zoom * factor))
                load()
                return
            }
            show()
        }

        // MARK: WKNavigationDelegate

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            let gen = generation
            after(0.16) { [weak self] in self?.measure(gen) }
            after(0.7) { [weak self] in self?.measure(gen) }
            after(1.2) { [weak self] in
                guard let self, gen == self.generation else { return }
                self.show()
            }
        }

        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            // Renderer weg (Speicherdruck): Inhalt frisch laden statt weiß zu bleiben
            load()
        }

        func webView(_ webView: WKWebView,
                     decidePolicyFor navigationAction: WKNavigationAction,
                     decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.cancel)
                return
            }
            let scheme = (url.scheme ?? "").lowercased()
            // App-interne Aktionen aus dem Seiten-Kopf (KI, Anhänge, „kein Phishing“)
            if scheme == "blockmail" {
                decisionHandler(.cancel)
                onAppLink?(url)
                return
            }
            let isMain = navigationAction.targetFrame?.isMainFrame ?? true
            if isMain {
                // Eigener Seitenaufbau (loadHTMLString → about:blank)
                if scheme == "about" && navigationAction.navigationType != .linkActivated {
                    decisionHandler(.allow)
                    return
                }
                decisionHandler(.cancel)
                // Nur echte Link-Tipps nach außen geben (keine Weiterleitungen)
                if navigationAction.navigationType == .linkActivated || navigationAction.targetFrame == nil {
                    Self.openExternally(url)
                }
                return
            }
            // Unterrahmen (eingebettete Inhalte) dürfen laden, Link-Tipps darin nach außen
            if navigationAction.navigationType == .linkActivated {
                decisionHandler(.cancel)
                Self.openExternally(url)
                return
            }
            decisionHandler(.allow)
        }

        private static func openExternally(_ url: URL) {
            let scheme = (url.scheme ?? "").lowercased()
            if scheme == "mailto" {
                // mailto: öffnet das eigene Verfassen-Fenster
                Task { @MainActor in AppRouter.shared.handle(url: url) }
                return
            }
            guard ["http", "https", "tel", "sms", "facetime", "maps"].contains(scheme) else { return }
            Task { @MainActor in UIApplication.shared.open(url) }
        }
    }
}
