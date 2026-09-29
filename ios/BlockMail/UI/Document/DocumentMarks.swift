import UIKit

/// Ein Aufsatz auf einer Dokumentseite (Port von `Mark` aus DocumentAnnotations.kt):
/// Strich/Textmarker, Unterschrift, Häkchen/Kreuz, Text/Datum, Schwärzung,
/// eingefügtes Bild oder Form.
///
/// Alle Koordinaten liegen in PUNKTEN DER ANGEZEIGTEN SEITE (bereits gedreht,
/// Ursprung oben links, Y nach unten) — unabhängig von Zoom und Bildschirm.
/// Beim Speichern wird mit Maßstab 1 in eine gleich große PDF-Seite gezeichnet;
/// so landet alles exakt dort, wo es auf dem Bildschirm lag.
enum Mark {
    case stroke(points: [CGPoint], width: CGFloat, color: UIColor, highlight: Bool)
    /// slot 0 = volle Unterschrift, 1 = Kürzel
    case sign(center: CGPoint, width: CGFloat, slot: Int)
    /// Häkchen (check = true) oder Kreuz; size = Kantenlänge
    case stamp(check: Bool, center: CGPoint, size: CGFloat, color: UIColor)
    /// Kurzer Text (Datumsstempel, Text-Werkzeug)
    case label(text: String, center: CGPoint, size: CGFloat, color: UIColor)
    /// Schwärzung — wird beim Speichern eingebrannt (Seite gerastert).
    case redact(a: CGPoint, b: CGPoint)
    case image(center: CGPoint, width: CGFloat, image: UIImage)
    /// kind: "rect", "oval", "line", "arrow"
    case shape(kind: String, a: CGPoint, b: CGPoint, color: UIColor, width: CGFloat)

    var isRedact: Bool {
        if case .redact = self { return true }
        return false
    }

    /// Wendet eine Punktabbildung auf alle Koordinaten an (Verschieben, Drehen).
    func mapped(_ t: (CGPoint) -> CGPoint) -> Mark {
        switch self {
        case let .stroke(points, width, color, highlight):
            return .stroke(points: points.map(t), width: width, color: color, highlight: highlight)
        case let .sign(center, width, slot):
            return .sign(center: t(center), width: width, slot: slot)
        case let .stamp(check, center, size, color):
            return .stamp(check: check, center: t(center), size: size, color: color)
        case let .label(text, center, size, color):
            return .label(text: text, center: t(center), size: size, color: color)
        case let .redact(a, b):
            return .redact(a: t(a), b: t(b))
        case let .image(center, width, image):
            return .image(center: t(center), width: width, image: image)
        case let .shape(kind, a, b, color, width):
            return .shape(kind: kind, a: t(a), b: t(b), color: color, width: width)
        }
    }

    func moved(by d: CGPoint) -> Mark {
        mapped { CGPoint(x: $0.x + d.x, y: $0.y + d.y) }
    }

    /// Skaliert um den Mittelpunkt (Port von `scaleMark`).
    func scaled(_ f: CGFloat) -> Mark {
        func around(_ c: CGPoint, _ p: CGPoint) -> CGPoint {
            CGPoint(x: c.x + (p.x - c.x) * f, y: c.y + (p.y - c.y) * f)
        }
        switch self {
        case let .stroke(points, width, color, highlight):
            guard !points.isEmpty else { return self }
            var cx: CGFloat = 0, cy: CGFloat = 0
            for p in points { cx += p.x; cy += p.y }
            let c = CGPoint(x: cx / CGFloat(points.count), y: cy / CGFloat(points.count))
            return .stroke(points: points.map { around(c, $0) }, width: width * f, color: color, highlight: highlight)
        case let .sign(center, width, slot):
            return .sign(center: center, width: width * f, slot: slot)
        case let .stamp(check, center, size, color):
            return .stamp(check: check, center: center, size: size * f, color: color)
        case let .label(text, center, size, color):
            return .label(text: text, center: center, size: size * f, color: color)
        case let .redact(a, b):
            let c = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            return .redact(a: around(c, a), b: around(c, b))
        case let .image(center, width, image):
            return .image(center: center, width: width * f, image: image)
        case let .shape(kind, a, b, color, width):
            let c = CGPoint(x: (a.x + b.x) / 2, y: (a.y + b.y) / 2)
            return .shape(kind: kind, a: around(c, a), b: around(c, b), color: color, width: width * f)
        }
    }

    /// Umgrenzung in Seitenpunkten (Auswahlrahmen, Treffertest).
    func bounds(sigAspect: CGFloat?, iniAspect: CGFloat?) -> CGRect {
        switch self {
        case let .stroke(points, width, _, _):
            guard let first = points.first else { return .zero }
            var r = CGRect(origin: first, size: .zero)
            for p in points { r = r.union(CGRect(origin: p, size: .zero)) }
            return r.insetBy(dx: -width, dy: -width)
        case let .sign(center, width, slot):
            let aspect = (slot == 1 ? iniAspect : sigAspect) ?? 0.4
            let h = width * aspect
            return CGRect(x: center.x - width / 2, y: center.y - h / 2, width: width, height: h)
        case let .stamp(_, center, size, _):
            return CGRect(x: center.x - size / 2, y: center.y - size / 2, width: size, height: size)
        case let .label(text, center, size, _):
            let w = MarkRenderer.labelWidth(text, size: size)
            let h = size * 1.4
            return CGRect(x: center.x - w / 2, y: center.y - h / 2, width: w, height: h)
        case let .redact(a, b):
            return CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(a.x - b.x), height: abs(a.y - b.y))
        case let .image(center, width, image):
            let h = width * MarkRenderer.aspect(image)
            return CGRect(x: center.x - width / 2, y: center.y - h / 2, width: width, height: h)
        case let .shape(_, a, b, _, width):
            return CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(a.x - b.x), height: abs(a.y - b.y))
                .insetBy(dx: -width, dy: -width)
        }
    }
}

/// Zeichnet Aufsätze — für die Anzeige UND fürs Speichern, damit beides
/// garantiert gleich aussieht (Port von `drawMarks`).
enum MarkRenderer {
    /// Deckkraft des Textmarkers (wie HIGHLIGHT_ALPHA = 110/255).
    static let highlightAlpha: CGFloat = 110.0 / 255.0
    /// Farbe des Textmarkers (kräftiges Gelb).
    static let highlightColor = UIColor(red: 1, green: 0xEB / 255.0, blue: 0x3B / 255.0, alpha: 1)

    static func aspect(_ image: UIImage) -> CGFloat {
        image.size.width > 0 ? image.size.height / image.size.width : 1
    }

    static func labelFont(_ size: CGFloat) -> UIFont {
        UIFont.boldSystemFont(ofSize: max(size, 1))
    }

    static func labelWidth(_ text: String, size: CGFloat) -> CGFloat {
        (text as NSString).size(withAttributes: [.font: labelFont(size)]).width
    }

    /// - Parameters:
    ///   - cg: Kontext im UIKit-Koordinatensystem (Y nach unten)
    ///   - scale: Seitenpunkte → Zielpunkte
    static func draw(_ marks: [Mark], in cg: CGContext, signature: UIImage?, initials: UIImage?,
                     scale: CGFloat, dx: CGFloat = 0, dy: CGFloat = 0) {
        UIGraphicsPushContext(cg)
        defer { UIGraphicsPopContext() }
        func pt(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * scale + dx, y: p.y * scale + dy) }

        for mark in marks {
            cg.saveGState()
            cg.setLineCap(.round)
            cg.setLineJoin(.round)
            switch mark {
            case let .stroke(points, width, color, highlight):
                guard points.count >= 2 else { cg.restoreGState(); continue }
                let path = CGMutablePath()
                path.move(to: pt(points[0]))
                for p in points.dropFirst() { path.addLine(to: pt(p)) }
                cg.setStrokeColor(color.cgColor)
                cg.setLineWidth(width * scale)
                if highlight {
                    // Eine Ebene je Strich: innen deckend, als Ganzes halb
                    // durchsichtig — Überlappungen dunkeln nicht nach
                    cg.setLineCap(.square)
                    cg.setBlendMode(.multiply)
                    cg.setAlpha(highlightAlpha)
                    cg.beginTransparencyLayer(auxiliaryInfo: nil)
                    cg.addPath(path)
                    cg.strokePath()
                    cg.endTransparencyLayer()
                } else {
                    cg.addPath(path)
                    cg.strokePath()
                }

            case let .sign(center, width, slot):
                if let sig = (slot == 1 ? initials : signature) {
                    let w = width * scale
                    let h = w * aspect(sig)
                    let c = pt(center)
                    sig.draw(in: CGRect(x: c.x - w / 2, y: c.y - h / 2, width: w, height: h))
                }

            case let .stamp(check, center, size, color):
                let s = size * scale
                let c = pt(center)
                cg.setStrokeColor(color.cgColor)
                cg.setLineWidth(s / 7)
                if check {
                    cg.move(to: CGPoint(x: c.x - 0.38 * s, y: c.y + 0.02 * s))
                    cg.addLine(to: CGPoint(x: c.x - 0.10 * s, y: c.y + 0.30 * s))
                    cg.addLine(to: CGPoint(x: c.x + 0.40 * s, y: c.y - 0.30 * s))
                } else {
                    cg.move(to: CGPoint(x: c.x - 0.32 * s, y: c.y - 0.32 * s))
                    cg.addLine(to: CGPoint(x: c.x + 0.32 * s, y: c.y + 0.32 * s))
                    cg.move(to: CGPoint(x: c.x - 0.32 * s, y: c.y + 0.32 * s))
                    cg.addLine(to: CGPoint(x: c.x + 0.32 * s, y: c.y - 0.32 * s))
                }
                cg.strokePath()

            case let .label(text, center, size, color):
                let font = labelFont(size * scale)
                let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color]
                let w = (text as NSString).size(withAttributes: attrs).width
                let c = pt(center)
                // Grundlinie wie Android: Mitte + 0,35 × Schriftgröße
                let baseline = c.y + font.pointSize * 0.35
                (text as NSString).draw(at: CGPoint(x: c.x - w / 2, y: baseline - font.ascender), withAttributes: attrs)

            case let .redact(a, b):
                let p1 = pt(a), p2 = pt(b)
                cg.setFillColor(UIColor.black.cgColor)
                cg.fill(CGRect(x: min(p1.x, p2.x), y: min(p1.y, p2.y), width: abs(p1.x - p2.x), height: abs(p1.y - p2.y)))

            case let .image(center, width, image):
                let w = width * scale
                let h = w * aspect(image)
                let c = pt(center)
                image.draw(in: CGRect(x: c.x - w / 2, y: c.y - h / 2, width: w, height: h))

            case let .shape(kind, a, b, color, width):
                let p1 = pt(a), p2 = pt(b)
                cg.setStrokeColor(color.cgColor)
                cg.setLineWidth(max(width * scale, 0.3))
                let rect = CGRect(x: min(p1.x, p2.x), y: min(p1.y, p2.y), width: abs(p1.x - p2.x), height: abs(p1.y - p2.y))
                switch kind {
                case "rect":
                    cg.stroke(rect)
                case "oval":
                    cg.strokeEllipse(in: rect)
                default:
                    cg.move(to: p1)
                    cg.addLine(to: p2)
                    if kind == "arrow" {
                        // Pfeilspitze: zwei Schenkel, 25 Grad zur Linie
                        let ang = atan2(p2.y - p1.y, p2.x - p1.x)
                        let len = max(width * scale * 5, 6 * scale)
                        for s: CGFloat in [1, -1] {
                            let t = ang + .pi - s * 0.44
                            cg.move(to: p2)
                            cg.addLine(to: CGPoint(x: p2.x + len * cos(t), y: p2.y + len * sin(t)))
                        }
                    }
                    cg.strokePath()
                }
            }
            cg.restoreGState()
        }
    }

    /// Oberster Aufsatz nahe p (Port von `hitMark`), sonst nil.
    static func hit(_ marks: [Mark], _ p: CGPoint, tolerance: CGFloat) -> Int? {
        for i in marks.indices.reversed() {
            let hit: Bool
            switch marks[i] {
            case let .stroke(points, _, _, _):
                hit = points.contains { abs($0.x - p.x) < tolerance && abs($0.y - p.y) < tolerance }
            case let .sign(center, width, _):
                hit = abs(center.x - p.x) < width / 2 && abs(center.y - p.y) < width / 2
            case let .stamp(_, center, size, _):
                hit = abs(center.x - p.x) < size * 0.6 && abs(center.y - p.y) < size * 0.6
            case let .label(text, center, size, _):
                hit = abs(center.x - p.x) < max(labelWidth(text, size: size) / 2, size) && abs(center.y - p.y) < size
            case let .redact(a, b):
                hit = p.x >= min(a.x, b.x) && p.x <= max(a.x, b.x) && p.y >= min(a.y, b.y) && p.y <= max(a.y, b.y)
            case let .image(center, width, image):
                let h = width * aspect(image)
                hit = abs(center.x - p.x) < width / 2 && abs(center.y - p.y) < h / 2
            case let .shape(kind, a, b, _, _):
                if kind == "line" || kind == "arrow" {
                    // Abstand zum Segment, sonst träfe man sie kaum
                    let dx = b.x - a.x, dy = b.y - a.y
                    let len2 = dx * dx + dy * dy
                    let t = len2 <= 0 ? 0 : min(max(((p.x - a.x) * dx + (p.y - a.y) * dy) / len2, 0), 1)
                    let nx = a.x + t * dx, ny = a.y + t * dy
                    hit = abs(p.x - nx) < tolerance && abs(p.y - ny) < tolerance
                } else {
                    hit = p.x >= min(a.x, b.x) - tolerance && p.x <= max(a.x, b.x) + tolerance &&
                        p.y >= min(a.y, b.y) - tolerance && p.y <= max(a.y, b.y) + tolerance
                }
            }
            if hit { return i }
        }
        return nil
    }
}
