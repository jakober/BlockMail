import UIKit
import PDFKit
import CoreText

/// PDF-Werkzeuge des Editors (Port von PdfSession/PdfOverlay/PdfPageOps/
/// PdfExtras/PdfTextDoc — auf PDFKit statt PDFBox).
///
/// Koordinaten: Der Editor rechnet in Punkten der ANGEZEIGTEN Seite (CropBox,
/// bereits gedreht, Ursprung oben links). [displayTransform] bündelt die
/// Umrechnung aus dem PDF-Seitenraum an genau einer Stelle.
enum DocumentPDF {

    struct OpError: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    // MARK: Geometrie

    static func normalizedRotation(_ page: PDFPage) -> Int {
        ((page.rotation % 360) + 360) % 360
    }

    /// Maße der angezeigten Seite in Punkten (gedreht).
    static func displaySize(_ page: PDFPage) -> CGSize {
        let b = page.bounds(for: .cropBox)
        let r = normalizedRotation(page)
        let w = max(b.width, 1), h = max(b.height, 1)
        return (r == 90 || r == 270) ? CGSize(width: h, height: w) : CGSize(width: w, height: h)
    }

    /// PDF-Seitenraum → Anzeige (Ursprung unten links, Y nach oben, gedreht).
    ///
    /// | Rotate | x' | y' |
    /// |---|---|---|
    /// | 0 | x − cx | y − cy |
    /// | 90 | y − cy | cx + cw − x |
    /// | 180 | cx + cw − x | cy + ch − y |
    /// | 270 | cy + ch − y | x − cx |
    static func displayTransform(_ page: PDFPage) -> CGAffineTransform {
        let b = page.bounds(for: .cropBox)
        let cx = b.minX, cy = b.minY, cw = b.width, ch = b.height
        switch normalizedRotation(page) {
        case 90: return CGAffineTransform(a: 0, b: -1, c: 1, d: 0, tx: -cy, ty: cx + cw)
        case 180: return CGAffineTransform(a: -1, b: 0, c: 0, d: -1, tx: cx + cw, ty: cy + ch)
        case 270: return CGAffineTransform(a: 0, b: 1, c: -1, d: 0, tx: cy + ch, ty: -cx)
        default: return CGAffineTransform(translationX: -cx, y: -cy)
        }
    }

    /// Rechteck im PDF-Seitenraum → Anzeige-Punkte (Y nach unten).
    static func toDisplay(_ rect: CGRect, on page: PDFPage) -> CGRect {
        let r = rect.applying(displayTransform(page))
        let h = displaySize(page).height
        return CGRect(x: r.minX, y: h - r.maxY, width: r.width, height: r.height)
    }

    /// Zeichnet den Seiteninhalt in einen UIKit-Kontext (Y nach unten), dessen
    /// Ursprung die obere linke Ecke der angezeigten Seite ist; eine Einheit =
    /// ein Seitenpunkt. Vektoren bleiben Vektoren (Text markierbar).
    static func drawPage(_ page: PDFPage, in cg: CGContext, annotations: Bool = true) {
        let size = displaySize(page)
        cg.saveGState()
        cg.translateBy(x: 0, y: size.height)
        cg.scaleBy(x: 1, y: -1)
        if let ref = page.pageRef {
            cg.concatenate(displayTransform(page))
            cg.clip(to: page.bounds(for: .cropBox))
            cg.drawPDFPage(ref)
            if annotations {
                // Formularwerte, Kommentare usw. mit einbrennen — Links und
                // Popups haben kein sichtbares Aussehen
                for a in page.annotations where a.shouldDisplay {
                    let t = (a.type ?? "").lowercased()
                    if t == "link" || t == "popup" { continue }
                    a.draw(with: .cropBox, in: cg)
                }
            }
        } else {
            page.draw(with: .cropBox, to: cg)
        }
        cg.restoreGState()
    }

    /// Maßstab Seitenpunkte → Bildpunkte, lange Kante höchstens maxPx.
    static func scaleFor(_ size: CGSize, maxPx: CGFloat) -> CGFloat {
        min(maxPx / max(size.width, size.height, 1), 3)
    }

    /// Rendert eine Seite als Bild (weißer Grund) samt optionaler Aufsätze.
    static func renderImage(_ page: PDFPage, maxPx: CGFloat, marks: [Mark] = [],
                            signature: UIImage? = nil, initials: UIImage? = nil) -> UIImage {
        let size = displaySize(page)
        let s = scaleFor(size, maxPx: maxPx)
        let px = CGSize(width: max(1, (size.width * s).rounded()), height: max(1, (size.height * s).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: px, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: px))
            let cg = ctx.cgContext
            cg.saveGState()
            cg.scaleBy(x: s, y: s)
            drawPage(page, in: cg)
            cg.restoreGState()
            if !marks.isEmpty {
                MarkRenderer.draw(marks, in: cg, signature: signature, initials: initials, scale: s)
            }
        }
    }

    // MARK: Neue Dokumente

    /// Leeres PDF mit A4-Seiten (Port von `createBlank`).
    static func blankData(pages: Int = 1, size: CGSize = CGSize(width: 595, height: 842)) -> Data {
        let bounds = CGRect(origin: .zero, size: size)
        return UIGraphicsPDFRenderer(bounds: bounds).pdfData { ctx in
            for _ in 0..<max(pages, 1) { ctx.beginPage() }
        }
    }

    /// Verkleinert ein Foto (lange Kante ≤ maxPx) und richtet es auf.
    static func normalized(_ image: UIImage, maxPx: CGFloat) -> UIImage {
        let pxW = image.size.width * image.scale, pxH = image.size.height * image.scale
        let s = min(1, maxPx / max(pxW, pxH, 1))
        let target = CGSize(width: max(1, (pxW * s).rounded()), height: max(1, (pxH * s).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: target))
        }
    }

    /// Je Bild eine Seite; die lange Kante entspricht A4 (842 pt), das Bild
    /// wird als JPEG eingebettet (Port von `addImagePages`).
    static func imagesData(_ images: [UIImage]) -> Data? {
        let prepared: [(UIImage, CGSize)] = images.compactMap { img in
            let small = normalized(img, maxPx: 2000)
            guard let jpeg = small.jpegData(compressionQuality: 0.85), let j = UIImage(data: jpeg) else { return nil }
            let scale = 842 / max(small.size.width, small.size.height, 1)
            return (j, CGSize(width: small.size.width * scale, height: small.size.height * scale))
        }
        guard !prepared.isEmpty else { return nil }
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842))
        return renderer.pdfData { ctx in
            for (img, size) in prepared {
                let r = CGRect(origin: .zero, size: size)
                ctx.beginPage(withBounds: r, pageInfo: [:])
                img.draw(in: r)
            }
        }
    }

    /// Sauberes A4-PDF aus Überschrift und Fließtext (Port von `PdfTextDoc`):
    /// echter, markierbarer Text, Blocksatz, Umbruch über mehrere Seiten.
    static func textDocument(title: String, body: String) -> Data? {
        let pageW: CGFloat = 595, pageH: CGFloat = 842, margin: CGFloat = 60
        let contentW = pageW - 2 * margin
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)

        let titleStyle = NSMutableParagraphStyle()
        titleStyle.lineHeightMultiple = 1.2
        let titleAttr = NSAttributedString(string: trimmedTitle, attributes: [
            .font: UIFont.systemFont(ofSize: 19, weight: .medium),
            .foregroundColor: UIColor.black,
            .paragraphStyle: titleStyle
        ])
        let bodyStyle = NSMutableParagraphStyle()
        bodyStyle.alignment = .justified
        bodyStyle.lineHeightMultiple = 1.45
        bodyStyle.hyphenationFactor = 0.8
        let bodyText = body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? " " : body
        let bodyAttr = NSAttributedString(string: bodyText, attributes: [
            .font: UIFont.systemFont(ofSize: 11),
            .foregroundColor: UIColor.black,
            .paragraphStyle: bodyStyle
        ])
        let framesetter = CTFramesetterCreateWithAttributedString(bodyAttr as CFAttributedString)
        let total = bodyAttr.length
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: pageW, height: pageH))
        let data = renderer.pdfData { ctx in
            var start = 0
            var pageNo = 0
            repeat {
                ctx.beginPage()
                pageNo += 1
                let cg = ctx.cgContext
                var y = margin
                if pageNo == 1 && !trimmedTitle.isEmpty {
                    let r = titleAttr.boundingRect(with: CGSize(width: contentW, height: .greatestFiniteMagnitude),
                                                   options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
                    titleAttr.draw(with: CGRect(x: margin, y: y, width: contentW, height: ceil(r.height)),
                                   options: [.usesLineFragmentOrigin, .usesFontLeading], context: nil)
                    y += ceil(r.height) + 12
                    // Feine Trennlinie unter der Überschrift
                    cg.saveGState()
                    cg.setStrokeColor(UIColor(white: 120.0 / 255.0, alpha: 1).cgColor)
                    cg.setLineWidth(0.8)
                    cg.move(to: CGPoint(x: margin, y: y))
                    cg.addLine(to: CGPoint(x: pageW - margin, y: y))
                    cg.strokePath()
                    cg.restoreGState()
                    y += 20
                }
                let bottom = pageH - margin
                let frameRect = CGRect(x: margin, y: pageH - bottom, width: contentW, height: bottom - y)
                cg.saveGState()
                cg.textMatrix = .identity
                cg.translateBy(x: 0, y: pageH)
                cg.scaleBy(x: 1, y: -1)
                let path = CGPath(rect: frameRect, transform: nil)
                let frame = CTFramesetterCreateFrame(framesetter, CFRange(location: start, length: 0), path, nil)
                CTFrameDraw(frame, cg)
                cg.restoreGState()
                let visible = CTFrameGetVisibleStringRange(frame)
                // Passt nicht einmal eine Zeile: weiter statt endlos schleifen
                start += max(visible.length, 1)
            } while start < total && pageNo < 500
        }
        return data.isEmpty ? nil : data
    }

    // MARK: Seitenoperationen (am geöffneten Dokument)

    static func insertBlank(into doc: PDFDocument, at index: Int) -> Bool {
        let at = min(max(index, 0), doc.pageCount)
        var size = CGSize(width: 595, height: 842)
        if doc.pageCount > 0, let neighbor = doc.page(at: min(max(at - 1, 0), doc.pageCount - 1)) {
            size = neighbor.bounds(for: .mediaBox).size
        }
        guard let blank = PDFDocument(data: blankData(pages: 1, size: size)), let page = blank.page(at: 0) else { return false }
        doc.insert(page, at: at)
        return true
    }

    /// Fügt alle Seiten von `other` an Position `index` ein; liefert die Anzahl.
    static func insert(_ other: PDFDocument, into doc: PDFDocument, at index: Int) -> Int {
        var at = min(max(index, 0), doc.pageCount)
        var added = 0
        for i in 0..<other.pageCount {
            guard let p = other.page(at: i) else { continue }
            let copy = (p.copy() as? PDFPage) ?? p
            doc.insert(copy, at: at)
            at += 1
            added += 1
        }
        return added
    }

    static func move(in doc: PDFDocument, from: Int, to: Int) -> Bool {
        let n = doc.pageCount
        guard from != to, from >= 0, from < n, to >= 0, to < n, let page = doc.page(at: from) else { return false }
        doc.removePage(at: from)
        doc.insert(page, at: to)
        return true
    }

    // MARK: Ergebnis schreiben

    /// Baut das Ergebnis-PDF (Port von `produce` + `PdfOverlay.write`).
    ///
    /// Unmarkierte Seiten bleiben unangetastet. Seiten mit Aufsätzen werden
    /// als Vektorseite neu gesetzt (Inhalt + Aufsätze), Seiten mit Schwärzung
    /// werden GERASTERT ersetzt — nur so verschwindet der Text darunter
    /// wirklich; zusätzlich werden die Dokumentinformationen geleert.
    /// Geschützte Dokumente werden komplett neu gesetzt (Schutz entfällt,
    /// wie PdfUnlock unter Android).
    static func writeOutput(doc: PDFDocument, password: String?, marks: [Int: [Mark]],
                            signature: UIImage?, initials: UIImage?, to url: URL) throws {
        try? FileManager.default.removeItem(at: url)
        let marked = marks.filter { !$0.value.isEmpty && $0.key >= 0 && $0.key < doc.pageCount }
        let redactPages = Set(marked.filter { $0.value.contains { $0.isRedact } }.keys)

        if doc.isEncrypted {
            let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
                for i in 0..<doc.pageCount {
                    guard let page = doc.page(at: i) else { continue }
                    let size = displaySize(page)
                    ctx.beginPage(withBounds: CGRect(origin: .zero, size: size), pageInfo: [:])
                    drawMarkedPage(page, marks: marked[i] ?? [], burn: redactPages.contains(i),
                                   signature: signature, initials: initials, in: ctx.cgContext)
                }
            }
            try data.write(to: url, options: .atomic)
            return
        }

        if marked.isEmpty {
            guard doc.write(to: url) else { throw OpError(message: "PDF konnte nicht geschrieben werden") }
            return
        }

        guard let data = doc.dataRepresentation(), let out = PDFDocument(data: data) else {
            throw OpError(message: "PDF konnte nicht geschrieben werden")
        }
        if out.isLocked, let pw = password { _ = out.unlock(withPassword: pw) }
        var keepAlive: [PDFDocument] = []
        for (index, list) in marked.sorted(by: { $0.key < $1.key }) {
            guard index < out.pageCount, let page = doc.page(at: index) else { continue }
            let size = displaySize(page)
            let one = UIGraphicsPDFRenderer(bounds: CGRect(origin: .zero, size: size)).pdfData { ctx in
                ctx.beginPage()
                drawMarkedPage(page, marks: list, burn: redactPages.contains(index),
                               signature: signature, initials: initials, in: ctx.cgContext)
            }
            guard let oneDoc = PDFDocument(data: one), let newPage = oneDoc.page(at: 0) else {
                throw OpError(message: "Seite \(index + 1) nicht lesbar")
            }
            keepAlive.append(oneDoc)
            out.removePage(at: index)
            out.insert(newPage, at: index)
        }
        if !redactPages.isEmpty {
            // Titel/Autor/ursprünglicher Dateiname haben in einem
            // geschwärzten Dokument nichts verloren
            out.documentAttributes = [:]
        }
        guard out.write(to: url) else { throw OpError(message: "PDF konnte nicht geschrieben werden") }
        _ = keepAlive.count
    }

    /// Zeichnet eine Seite samt Aufsätzen in eine PDF-Seite gleicher Größe.
    private static func drawMarkedPage(_ page: PDFPage, marks: [Mark], burn: Bool,
                                       signature: UIImage?, initials: UIImage?, in cg: CGContext) {
        let size = displaySize(page)
        if burn {
            let img = renderImage(page, maxPx: 2000, marks: marks, signature: signature, initials: initials)
            let jpeg = img.jpegData(compressionQuality: 0.85).flatMap { UIImage(data: $0) } ?? img
            UIGraphicsPushContext(cg)
            jpeg.draw(in: CGRect(origin: .zero, size: size))
            UIGraphicsPopContext()
        } else {
            drawPage(page, in: cg)
            MarkRenderer.draw(marks, in: cg, signature: signature, initials: initials, scale: 1)
        }
    }

    /// PDF verkleinern: jede Seite als JPEG neu setzen (Port von `PdfCompress`).
    static func compress(_ src: URL, to out: URL) -> Bool {
        guard let doc = PDFDocument(url: src), doc.pageCount > 0 else { return false }
        let data = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 595, height: 842)).pdfData { ctx in
            for i in 0..<doc.pageCount {
                guard let page = doc.page(at: i) else { continue }
                let size = displaySize(page)
                let img = renderImage(page, maxPx: 1400)
                let jpeg = img.jpegData(compressionQuality: 0.6).flatMap { UIImage(data: $0) } ?? img
                ctx.beginPage(withBounds: CGRect(origin: .zero, size: size), pageInfo: [:])
                jpeg.draw(in: CGRect(origin: .zero, size: size))
            }
        }
        try? FileManager.default.removeItem(at: out)
        return (try? data.write(to: out, options: .atomic)) != nil && !data.isEmpty
    }

    /// PDF mit Passwort verschlüsseln (Port von `PdfCrypt`).
    static func protect(_ src: URL, to out: URL, password: String) -> Bool {
        guard let doc = PDFDocument(url: src) else { return false }
        try? FileManager.default.removeItem(at: out)
        let options: [PDFDocumentWriteOption: Any] = [
            .userPasswordOption: password,
            .ownerPasswordOption: password
        ]
        return doc.write(to: out, withOptions: options)
    }

    /// Seiten from...to (einschließlich) in ein neues PDF (Port von `extract`).
    static func extract(_ src: URL, to out: URL, from: Int, to last: Int) -> Bool {
        guard let doc = PDFDocument(url: src), from >= 0, last < doc.pageCount, from <= last else { return false }
        let dst = PDFDocument()
        for i in from...last {
            guard let p = doc.page(at: i) else { continue }
            dst.insert((p.copy() as? PDFPage) ?? p, at: dst.pageCount)
        }
        try? FileManager.default.removeItem(at: out)
        return dst.pageCount > 0 && dst.write(to: out)
    }

    // MARK: Text

    struct SearchHit: Identifiable {
        let id = UUID()
        let page: Int
        let snippet: String
    }

    /// Volltextsuche (Port von `PdfTextSearch`).
    static func search(_ doc: PDFDocument, query: String, maxHits: Int = 60) -> [SearchHit] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard q.count >= 2 else { return [] }
        var hits: [SearchHit] = []
        for i in 0..<doc.pageCount {
            if hits.count >= maxHits { break }
            guard let text = doc.page(at: i)?.string else { continue }
            let ns = text as NSString
            let lower = text.lowercased() as NSString
            var range = lower.range(of: q)
            while range.location != NSNotFound && hits.count < maxHits {
                let start = min(max(range.location - 36, 0), ns.length)
                let end = min(range.location + range.length + 36, ns.length)
                let snippet = end > start
                    ? ns.substring(with: NSRange(location: start, length: end - start))
                        .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                        .trimmingCharacters(in: .whitespaces)
                    : ""
                hits.append(SearchHit(page: i, snippet: snippet))
                let next = range.location + range.length
                if next >= lower.length { break }
                range = lower.range(of: q, options: [], range: NSRange(location: next, length: lower.length - next))
            }
        }
        return hits
    }

    /// Reiner Text für Zusammenfassung & Fragen (Port von `PdfTextExtract`).
    static func text(_ doc: PDFDocument, maxChars: Int = 8000) -> String {
        var s = ""
        for i in 0..<doc.pageCount {
            if s.count >= maxChars { break }
            if let t = doc.page(at: i)?.string { s += t + "\n" }
        }
        return String(s.prefix(maxChars))
    }

    struct Box {
        let page: Int
        let rect: CGRect // Anzeige-Punkte
    }

    /// Fundstellen samt Position (Port von `PdfTextLocate`) — je Zeile ein Kasten.
    static func locate(_ doc: PDFDocument, regex: NSRegularExpression, maxBoxes: Int = 400) -> [Box] {
        var boxes: [Box] = []
        for i in 0..<doc.pageCount {
            if boxes.count >= maxBoxes { break }
            guard let page = doc.page(at: i), let text = page.string else { continue }
            let ns = text as NSString
            for m in regex.matches(in: text, range: NSRange(location: 0, length: ns.length)) {
                if boxes.count >= maxBoxes { break }
                guard m.range.length > 0, let sel = page.selection(for: m.range) else { continue }
                for line in sel.selectionsByLine() {
                    let r = line.bounds(for: page)
                    if r.width > 0 && r.height > 0 {
                        boxes.append(Box(page: i, rect: toDisplay(r, on: page)))
                    }
                }
            }
        }
        return boxes
    }

    // MARK: Formulare

    struct FormText: Identifiable {
        var id: String { name }
        let name: String
        let label: String
        let value: String
    }

    struct FormCheck: Identifiable {
        var id: String { name }
        let name: String
        let label: String
        let checked: Bool
    }

    struct Form {
        let texts: [FormText]
        let checks: [FormCheck]
        var isEmpty: Bool { texts.isEmpty && checks.isEmpty }
    }

    /// Echte Formularfelder lesen (Port von `PdfFormOps.read`).
    static func readForm(_ doc: PDFDocument) -> Form {
        var texts: [FormText] = []
        var checks: [FormCheck] = []
        var seen = Set<String>()
        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            for a in page.annotations {
                guard let name = a.fieldName, !name.isEmpty, !seen.contains(name) else { continue }
                let alt = (a.value(forAnnotationKey: .widgetTextLabelUI) as? String) ?? ""
                let label = alt.trimmingCharacters(in: .whitespaces).isEmpty ? name : alt
                if a.widgetFieldType == .text {
                    seen.insert(name)
                    texts.append(FormText(name: name, label: label, value: a.widgetStringValue ?? ""))
                } else if a.widgetFieldType == .button && a.widgetControlType == .checkBoxControl {
                    seen.insert(name)
                    checks.append(FormCheck(name: name, label: label, checked: a.buttonWidgetState == .onState))
                }
            }
        }
        return Form(texts: texts, checks: checks)
    }

    /// Formular ausfüllen — direkt am geöffneten Dokument.
    static func fillForm(_ doc: PDFDocument, texts: [String: String], checks: [String: Bool]) -> Bool {
        var changed = false
        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            for a in page.annotations {
                guard let name = a.fieldName else { continue }
                if a.widgetFieldType == .text, let v = texts[name] {
                    a.widgetStringValue = v
                    changed = true
                } else if a.widgetFieldType == .button, a.widgetControlType == .checkBoxControl, let c = checks[name] {
                    a.buttonWidgetState = c ? .onState : .offState
                    changed = true
                }
            }
        }
        return changed
    }

    // MARK: Inhaltsverzeichnis

    struct OutlineEntry: Identifiable {
        let id = UUID()
        let title: String
        let page: Int
        let depth: Int
    }

    /// Lesezeichen mit Sprungzielen (Port von `PdfOutline`).
    static func outline(_ doc: PDFDocument) -> [OutlineEntry] {
        guard let root = doc.outlineRoot else { return [] }
        var entries: [OutlineEntry] = []
        func walk(_ node: PDFOutline, depth: Int) {
            for i in 0..<node.numberOfChildren {
                guard entries.count < 300, let child = node.child(at: i) else { continue }
                let title = (child.label ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
                if let page = child.destination?.page {
                    let idx = doc.index(for: page)
                    if idx >= 0 && idx < doc.pageCount && !title.isEmpty {
                        entries.append(OutlineEntry(title: title, page: idx, depth: depth))
                    }
                }
                walk(child, depth: depth + 1)
            }
        }
        walk(root, depth: 0)
        return entries
    }
}
