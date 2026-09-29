import SwiftUI
import UIKit

/// Zustand und Befehle des Rich-Text-Editors (Ersatz für `RichTextState` der
/// Android-Bibliothek). Hält den Text im `UITextView` (bzw. vorab in einem
/// Puffer), kennt Fett/Kursiv/Unterstrichen/Aufzählung und wandelt von/nach
/// einfachem HTML (<p>, <br>, <b>, <i>, <u>, <ul><li>).
@MainActor
final class RichTextController: ObservableObject {

    /// Reiner Text (für Platzhalter, Senden-Knopf, KI-Anweisung).
    @Published private(set) var plainText: String = ""
    @Published private(set) var isBold = false
    @Published private(set) var isItalic = false
    @Published private(set) var isUnderline = false
    @Published private(set) var isList = false
    /// Änderungszähler (löst Neuberechnung der Höhe aus).
    @Published private(set) var version = 0

    /// Aufzählungszeichen am Zeilenanfang.
    static let bullet = "• "

    weak var textView: UITextView?
    private var buffer = NSAttributedString(string: "")
    private var pendingSelection = NSRange(location: 0, length: 0)

    /// Grundschrift (Systemschrift, Fließtext-Größe).
    var baseFont: UIFont { UIFont.preferredFont(forTextStyle: .body) }
    var textColor: UIColor { .label }

    var baseAttributes: [NSAttributedString.Key: Any] {
        [.font: baseFont, .foregroundColor: textColor]
    }

    /// Aktueller Inhalt (aus dem Textfeld oder dem Puffer).
    var attributedText: NSAttributedString {
        textView?.attributedText ?? buffer
    }

    // MARK: Anbindung an das UITextView

    func attach(_ tv: UITextView) {
        textView = tv
        tv.attributedText = buffer
        tv.typingAttributes = baseAttributes
        let len = (buffer.string as NSString).length
        tv.selectedRange = NSRange(location: min(pendingSelection.location, len), length: 0)
        // Nicht während des SwiftUI-Updates veröffentlichen
        DispatchQueue.main.async { [weak self] in self?.refreshState() }
    }

    /// Textfeld wird abgebaut: Inhalt im Puffer sichern.
    func detach(_ tv: UITextView) {
        guard textView === tv else { return }
        buffer = tv.attributedText ?? buffer
        pendingSelection = tv.selectedRange
        textView = nil
    }

    /// Vom Coordinator nach jeder Eingabe aufgerufen.
    func textDidChange() {
        let text = attributedText.string
        if text != plainText { plainText = text }
        version &+= 1
        refreshState()
    }

    func selectionDidChange() {
        refreshState()
    }

    // MARK: HTML setzen / lesen

    /// Ersetzt den Inhalt durch (einfaches) HTML — z. B. Entwurf, Signatur oder KI-Ergebnis.
    func setHtml(_ html: String) {
        let attr = Self.importHtml(html, font: baseFont, color: textColor)
        if let tv = textView {
            tv.attributedText = attr
            tv.typingAttributes = baseAttributes
            tv.selectedRange = NSRange(location: 0, length: 0)
        } else {
            buffer = attr
            pendingSelection = NSRange(location: 0, length: 0)
        }
        plainText = attr.string
        version &+= 1
        refreshState()
    }

    /// Inhalt als einfaches HTML (Absätze, Zeilenumbrüche, Fett/Kursiv/Unterstrichen, Listen).
    func toHtml() -> String {
        Self.exportHtml(attributedText)
    }

    // MARK: Formatierung

    func toggleBold() { toggleTrait(.traitBold) }
    func toggleItalic() { toggleTrait(.traitItalic) }

    private func makeFont(_ traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        Self.font(base: baseFont, traits: traits)
    }

    private static func font(base: UIFont, traits: UIFontDescriptor.SymbolicTraits) -> UIFont {
        let t = traits.intersection([.traitBold, .traitItalic])
        if t.isEmpty { return base }
        if let d = base.fontDescriptor.withSymbolicTraits(t) {
            return UIFont(descriptor: d, size: base.pointSize)
        }
        return base
    }

    private static func traits(of font: UIFont?) -> UIFontDescriptor.SymbolicTraits {
        guard let font else { return [] }
        return font.fontDescriptor.symbolicTraits.intersection([.traitBold, .traitItalic])
    }

    private func toggleTrait(_ trait: UIFontDescriptor.SymbolicTraits) {
        guard let tv = textView else { return }
        let r = tv.selectedRange
        if r.length == 0 {
            var attrs = tv.typingAttributes
            var t = Self.traits(of: attrs[.font] as? UIFont)
            if t.contains(trait) { t.remove(trait) } else { t.insert(trait) }
            attrs[.font] = makeFont(t)
            if attrs[.foregroundColor] == nil { attrs[.foregroundColor] = textColor }
            tv.typingAttributes = attrs
        } else {
            let storage = tv.textStorage
            var allHave = true
            storage.enumerateAttribute(.font, in: r, options: []) { value, _, _ in
                if !Self.traits(of: value as? UIFont).contains(trait) { allHave = false }
            }
            var runs: [(NSRange, UIFontDescriptor.SymbolicTraits)] = []
            storage.enumerateAttribute(.font, in: r, options: []) { value, sub, _ in
                var t = Self.traits(of: value as? UIFont)
                if allHave { t.remove(trait) } else { t.insert(trait) }
                runs.append((sub, t))
            }
            storage.beginEditing()
            for (sub, t) in runs { storage.addAttribute(.font, value: makeFont(t), range: sub) }
            storage.endEditing()
            tv.selectedRange = r
        }
        textDidChange()
    }

    func toggleUnderline() {
        guard let tv = textView else { return }
        let r = tv.selectedRange
        if r.length == 0 {
            var attrs = tv.typingAttributes
            let on = ((attrs[.underlineStyle] as? Int) ?? 0) != 0
            if on { attrs.removeValue(forKey: .underlineStyle) } else { attrs[.underlineStyle] = NSUnderlineStyle.single.rawValue }
            tv.typingAttributes = attrs
        } else {
            let storage = tv.textStorage
            var allHave = true
            storage.enumerateAttribute(.underlineStyle, in: r, options: []) { value, _, _ in
                if ((value as? Int) ?? 0) == 0 { allHave = false }
            }
            storage.beginEditing()
            if allHave {
                storage.removeAttribute(.underlineStyle, range: r)
            } else {
                storage.addAttribute(.underlineStyle, value: NSUnderlineStyle.single.rawValue, range: r)
            }
            storage.endEditing()
            tv.selectedRange = r
        }
        textDidChange()
    }

    /// Aufzählung für alle Absätze der Auswahl ein-/ausschalten.
    func toggleList() {
        guard let tv = textView else { return }
        let storage = tv.textStorage
        let sel = tv.selectedRange
        let ns = storage.string as NSString
        let paraRange = ns.paragraphRange(for: sel)
        // Zeilenanfänge im Bereich sammeln
        var starts: [Int] = []
        var loc = paraRange.location
        repeat {
            starts.append(loc)
            let line = ns.paragraphRange(for: NSRange(location: loc, length: 0))
            let next = line.location + line.length
            if next <= loc { break }
            loc = next
        } while loc < paraRange.location + paraRange.length
        let bullet = Self.bullet as NSString
        let allBulleted = starts.allSatisfy { s in
            s + bullet.length <= ns.length && ns.substring(with: NSRange(location: s, length: bullet.length)) == Self.bullet
        }
        var attrs = tv.typingAttributes
        if attrs[.font] == nil { attrs[.font] = baseFont }
        if attrs[.foregroundColor] == nil { attrs[.foregroundColor] = textColor }
        attrs.removeValue(forKey: .underlineStyle)
        var firstDelta = 0
        var totalDelta = 0
        storage.beginEditing()
        for (i, s) in starts.enumerated().reversed() {
            if allBulleted {
                storage.replaceCharacters(in: NSRange(location: s, length: bullet.length), with: "")
                totalDelta -= bullet.length
                if i == 0 { firstDelta = -bullet.length }
            } else {
                let has = s + bullet.length <= ns.length &&
                    (storage.string as NSString).substring(with: NSRange(location: s, length: min(bullet.length, (storage.string as NSString).length - s))) == Self.bullet
                if !has {
                    storage.replaceCharacters(in: NSRange(location: s, length: 0),
                                              with: NSAttributedString(string: Self.bullet, attributes: attrs))
                    totalDelta += bullet.length
                    if i == 0 { firstDelta = bullet.length }
                }
            }
        }
        storage.endEditing()
        let newLen = (storage.string as NSString).length
        let newLoc = max(paraRange.location, min(newLen, sel.location + firstDelta))
        let newLength = max(0, min(newLen - newLoc, sel.length + totalDelta - firstDelta))
        tv.selectedRange = NSRange(location: newLoc, length: newLength)
        textDidChange()
    }

    /// Eingabetaste in einer Aufzählungszeile: nächsten Punkt beginnen bzw.
    /// bei leerem Punkt die Aufzählung beenden. Liefert true, wenn behandelt.
    func handleReturn(in range: NSRange) -> Bool {
        guard let tv = textView else { return false }
        let storage = tv.textStorage
        let ns = storage.string as NSString
        let line = ns.paragraphRange(for: NSRange(location: range.location, length: 0))
        let lineText = ns.substring(with: line).trimmingCharacters(in: .newlines)
        guard lineText.hasPrefix(Self.bullet) else { return false }
        if lineText == Self.bullet || lineText == Self.bullet.trimmingCharacters(in: .whitespaces) {
            // Leerer Punkt: Aufzählung beenden
            let len = min((Self.bullet as NSString).length, ns.length - line.location)
            storage.replaceCharacters(in: NSRange(location: line.location, length: len), with: "")
            tv.selectedRange = NSRange(location: line.location, length: 0)
        } else {
            var attrs = tv.typingAttributes
            if attrs[.font] == nil { attrs[.font] = baseFont }
            if attrs[.foregroundColor] == nil { attrs[.foregroundColor] = textColor }
            let insert = NSAttributedString(string: "\n" + Self.bullet, attributes: attrs)
            storage.replaceCharacters(in: range, with: insert)
            tv.selectedRange = NSRange(location: range.location + insert.length, length: 0)
            tv.typingAttributes = attrs
        }
        textDidChange()
        return true
    }

    private func refreshState() {
        guard let tv = textView else {
            isBold = false; isItalic = false; isUnderline = false; isList = false
            return
        }
        let r = tv.selectedRange
        let attrs: [NSAttributedString.Key: Any]
        if r.length == 0 || tv.textStorage.length == 0 {
            attrs = tv.typingAttributes
        } else {
            attrs = tv.textStorage.attributes(at: min(r.location, tv.textStorage.length - 1), effectiveRange: nil)
        }
        let t = Self.traits(of: attrs[.font] as? UIFont)
        let b = t.contains(.traitBold), i = t.contains(.traitItalic)
        let u = ((attrs[.underlineStyle] as? Int) ?? 0) != 0
        let ns = tv.textStorage.string as NSString
        var l = false
        if ns.length > 0 || r.location > 0 {
            let loc = min(r.location, ns.length)
            let line = ns.paragraphRange(for: NSRange(location: loc, length: 0))
            l = ns.substring(with: line).hasPrefix(Self.bullet)
        }
        if b != isBold { isBold = b }
        if i != isItalic { isItalic = i }
        if u != isUnderline { isUnderline = u }
        if l != isList { isList = l }
    }

    // MARK: HTML → NSAttributedString

    private static func replace(_ s: String, _ pattern: String, _ with: String) -> String {
        s.replacingOccurrences(of: pattern, with: with, options: [.regularExpression, .caseInsensitive])
    }

    /// Wandelt einfaches HTML in formatierten Text um. Nutzt den HTML-Import
    /// von UIKit (nur auf dem Main-Thread erlaubt) und normalisiert danach
    /// alles auf die Systemschrift — übrig bleiben nur Fett/Kursiv/Unterstrichen.
    static func importHtml(_ html: String, font base: UIFont, color: UIColor) -> NSAttributedString {
        let baseAttrs: [NSAttributedString.Key: Any] = [.font: base, .foregroundColor: color]
        let trimmed = html.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty { return NSAttributedString(string: "", attributes: baseAttrs) }
        let keepLeading = trimmed.lowercased().hasPrefix("<br")

        // Blöcke vorab in Zeilenumbrüche übersetzen: So entstehen genau die
        // Leerzeilen, die der Export später wieder zu Absätzen macht.
        var s = html
        s = replace(s, "(?s)<(style|script|head|title)[^>]*>.*?</(style|script|head|title)\\s*>", "")
        s = replace(s, "(?s)<!--.*?-->", "")
        s = replace(s, "</?(html|body)[^>]*>", "")
        s = replace(s, "<p(\\s[^>]*)?>", "")
        s = replace(s, "</p\\s*>", "<br><br>")
        s = replace(s, "<div(\\s[^>]*)?>", "")
        s = replace(s, "</div\\s*>", "<br>")
        s = replace(s, "<h[1-6](\\s[^>]*)?>", "<b>")
        s = replace(s, "</h[1-6]\\s*>", "</b><br><br>")
        s = replace(s, "<(ul|ol)(\\s[^>]*)?>", "<br>")
        s = replace(s, "</(ul|ol)\\s*>", "<br>")
        s = replace(s, "<li(\\s[^>]*)?>", Self.bullet)
        s = replace(s, "</li\\s*>", "<br>")
        let wrapped = "<html><head><meta charset=\"utf-8\"></head><body>" + s + "</body></html>"

        guard let data = wrapped.data(using: .utf8),
              let parsed = try? NSAttributedString(
                data: data,
                options: [.documentType: NSAttributedString.DocumentType.html,
                          .characterEncoding: String.Encoding.utf8.rawValue],
                documentAttributes: nil)
        else {
            return NSAttributedString(string: HTMLText.visibleText(html), attributes: baseAttrs)
        }

        let out = NSMutableAttributedString()
        let src = parsed.string as NSString
        parsed.enumerateAttributes(in: NSRange(location: 0, length: parsed.length), options: []) { attrs, range, _ in
            var text = src.substring(with: range)
            text = text.replacingOccurrences(of: "\u{2028}", with: "\n")
                .replacingOccurrences(of: "\u{2029}", with: "\n")
                .replacingOccurrences(of: "\r", with: "")
                .replacingOccurrences(of: "\u{FFFC}", with: "")
            if text.isEmpty { return }
            var na: [NSAttributedString.Key: Any] = [
                .font: font(base: base, traits: traits(of: attrs[.font] as? UIFont)),
                .foregroundColor: color
            ]
            if let u = attrs[.underlineStyle] as? Int, u != 0 {
                na[.underlineStyle] = NSUnderlineStyle.single.rawValue
            }
            out.append(NSAttributedString(string: text, attributes: na))
        }

        // Listenmarken des HTML-Imports ("\t•\t") vereinheitlichen
        normalizeListMarkers(out)
        // Mehr als eine Leerzeile zusammenfassen
        collapse(out, pattern: "[ \\t]+\\n", with: "\n")
        collapse(out, pattern: "\\n{3,}", with: "\n\n")
        // Enden säubern
        while out.length > 0, let last = out.string.unicodeScalars.last,
              CharacterSet.whitespacesAndNewlines.contains(last) {
            out.deleteCharacters(in: NSRange(location: out.length - 1, length: 1))
        }
        if !keepLeading {
            while out.length > 0, let first = out.string.unicodeScalars.first,
                  CharacterSet.whitespacesAndNewlines.contains(first) {
                out.deleteCharacters(in: NSRange(location: 0, length: 1))
            }
        }
        return out
    }

    private static func collapse(_ s: NSMutableAttributedString, pattern: String, with: String) {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return }
        let matches = re.matches(in: s.string, range: NSRange(location: 0, length: s.length))
        for m in matches.reversed() {
            s.replaceCharacters(in: m.range, with: with)
        }
    }

    private static func normalizeListMarkers(_ s: NSMutableAttributedString) {
        guard let re = try? NSRegularExpression(pattern: "(?m)^[ \\t]*[•◦▪·‣][ \\t]+") else { return }
        let matches = re.matches(in: s.string, range: NSRange(location: 0, length: s.length))
        for m in matches.reversed() {
            s.replaceCharacters(in: m.range, with: bullet)
        }
    }

    // MARK: NSAttributedString → HTML

    static func exportHtml(_ a: NSAttributedString) -> String {
        let ns = a.string as NSString
        if ns.length == 0 { return "" }
        var blocks: [String] = []
        var lines: [String] = []
        var items: [String] = []
        func flushParagraph() {
            if !lines.isEmpty { blocks.append("<p>" + lines.joined(separator: "<br>") + "</p>") }
            lines = []
        }
        func flushList() {
            if !items.isEmpty { blocks.append("<ul>" + items.map { "<li>\($0)</li>" }.joined() + "</ul>") }
            items = []
        }
        let bulletLen = (bullet as NSString).length
        ns.enumerateSubstrings(in: NSRange(location: 0, length: ns.length),
                               options: [.byLines, .substringNotRequired]) { _, lineRange, _, _ in
            let text = ns.substring(with: lineRange)
            if text.trimmingCharacters(in: .whitespaces).isEmpty {
                flushParagraph(); flushList()
            } else if text.hasPrefix(bullet) {
                flushParagraph()
                let r = NSRange(location: lineRange.location + bulletLen, length: lineRange.length - bulletLen)
                items.append(inlineHtml(a, r))
            } else {
                flushList()
                lines.append(inlineHtml(a, lineRange))
            }
        }
        flushParagraph(); flushList()
        return blocks.joined()
    }

    private static func inlineHtml(_ a: NSAttributedString, _ range: NSRange) -> String {
        guard range.length > 0 else { return "" }
        let ns = a.string as NSString
        var out = ""
        a.enumerateAttributes(in: range, options: []) { attrs, sub, _ in
            var text = HTMLText.escape(ns.substring(with: sub))
            // Mehrfache Leerzeichen erhalten
            text = text.replacingOccurrences(of: "  ", with: " &nbsp;")
            let t = traits(of: attrs[.font] as? UIFont)
            let b = t.contains(.traitBold), i = t.contains(.traitItalic)
            let u = ((attrs[.underlineStyle] as? Int) ?? 0) != 0
            var open = "", close = ""
            if b { open += "<b>"; close = "</b>" + close }
            if i { open += "<i>"; close = "</i>" + close }
            if u { open += "<u>"; close = "</u>" + close }
            out += open + text + close
        }
        // Aneinandergrenzende gleiche Tags zusammenfassen
        for tag in ["b", "i", "u"] {
            out = out.replacingOccurrences(of: "</\(tag)><\(tag)>", with: "")
        }
        return out
    }
}

/// UITextView mit formatiertem Text als SwiftUI-View. Scrollt nicht selbst —
/// wächst mit dem Inhalt und liegt in der ScrollView des Verfassen-Fensters.
struct RichTextView: UIViewRepresentable {
    @ObservedObject var controller: RichTextController
    var tint: UIColor
    /// Nur für SwiftUI: bei Änderungen Höhe neu berechnen.
    var version: Int

    func makeCoordinator() -> Coordinator { Coordinator(controller: controller) }

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.isScrollEnabled = false
        tv.backgroundColor = .clear
        tv.textContainerInset = .zero
        tv.textContainer.lineFragmentPadding = 0
        tv.adjustsFontForContentSizeCategory = false
        tv.allowsEditingTextAttributes = true
        tv.autocapitalizationType = .sentences
        tv.dataDetectorTypes = []
        tv.tintColor = tint
        tv.delegate = context.coordinator
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        controller.attach(tv)
        return tv
    }

    func updateUIView(_ uiView: UITextView, context: Context) {
        context.coordinator.controller = controller
        if uiView.tintColor != tint { uiView.tintColor = tint }
        if controller.textView !== uiView { controller.attach(uiView) }
        uiView.invalidateIntrinsicContentSize()
    }

    static func dismantleUIView(_ uiView: UITextView, coordinator: Coordinator) {
        coordinator.controller.detach(uiView)
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? uiView.bounds.width
        guard width > 0, width.isFinite else { return nil }
        let fit = uiView.sizeThatFits(CGSize(width: width, height: .greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fit.height))
    }

    @MainActor
    final class Coordinator: NSObject, UITextViewDelegate {
        var controller: RichTextController

        init(controller: RichTextController) {
            self.controller = controller
        }

        func textViewDidChange(_ textView: UITextView) {
            controller.textDidChange()
            scrollCaretVisible(textView)
        }

        func textViewDidChangeSelection(_ textView: UITextView) {
            controller.selectionDidChange()
        }

        func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
            if text == "\n", range.length == 0, controller.handleReturn(in: range) {
                scrollCaretVisible(textView)
                return false
            }
            return true
        }

        /// Die umgebende ScrollView folgt der Schreibmarke (das Textfeld scrollt nicht selbst).
        private func scrollCaretVisible(_ textView: UITextView) {
            DispatchQueue.main.async {
                guard let pos = textView.selectedTextRange?.end else { return }
                var sv: UIView? = textView.superview
                while let v = sv, !(v is UIScrollView) { sv = v.superview }
                guard let scroll = sv as? UIScrollView else { return }
                let caret = textView.caretRect(for: pos)
                let rect = textView.convert(caret, to: scroll).insetBy(dx: 0, dy: -24)
                scroll.scrollRectToVisible(rect, animated: true)
            }
        }
    }
}
