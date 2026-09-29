import SwiftUI
import UIKit

/// Gespeicherte Unterschriften (Port des Unterschrift-Teils von
/// `AttachmentEditing`): einmal mit dem Finger gezeichnet, danach dauerhaft
/// verfügbar. PNG mit Transparenz in Application Support (nicht im Backup).
/// Zwei Fächer: 0 = volle Unterschrift, 1 = Kürzel (Initialen).
enum SignatureStore {

    private static func directory() -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else { return nil }
        var dir = base.appendingPathComponent("signatures", isDirectory: true)
        if !FileManager.default.fileExists(atPath: dir.path) {
            try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? dir.setResourceValues(values)
        }
        return dir
    }

    private static func file(_ slot: Int) -> URL? {
        directory()?.appendingPathComponent("sig_\(slot).png")
    }

    static func load(_ slot: Int) -> UIImage? {
        guard let url = file(slot), let data = try? Data(contentsOf: url) else { return nil }
        return UIImage(data: data)
    }

    static func save(_ image: UIImage, slot: Int) {
        guard let url = file(slot), let data = image.pngData() else { return }
        try? data.write(to: url, options: [.atomic, .completeFileProtection])
    }

    static func clear() {
        if let dir = directory() { try? FileManager.default.removeItem(at: dir) }
    }

    /// Schneidet die leeren Ränder weg (Port von `trim`), damit sich die
    /// Unterschrift eng platzieren lässt.
    static func trim(_ image: UIImage) -> UIImage {
        guard let cg = image.cgImage else { return image }
        let w = cg.width, h = cg.height
        guard w > 0, h > 0 else { return image }
        var pixels = [UInt8](repeating: 0, count: w * h * 4)
        let found: (Int, Int, Int, Int)? = pixels.withUnsafeMutableBytes { buf -> (Int, Int, Int, Int)? in
            guard let ctx = CGContext(data: buf.baseAddress, width: w, height: h, bitsPerComponent: 8,
                                      bytesPerRow: w * 4, space: CGColorSpaceCreateDeviceRGB(),
                                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
            ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
            let p = buf.bindMemory(to: UInt8.self)
            var top = h, bottom = -1, left = w, right = -1
            for y in 0..<h {
                let row = y * w * 4
                for x in 0..<w where p[row + x * 4 + 3] > 8 {
                    if y < top { top = y }
                    if y > bottom { bottom = y }
                    if x < left { left = x }
                    if x > right { right = x }
                }
            }
            if bottom < 0 || right < 0 { return nil }
            return (left, top, right, bottom)
        }
        guard let box = found else { return image }
        let (left, top, right, bottom) = box
        let pad = 8
        let x0 = max(left - pad, 0), y0 = max(top - pad, 0)
        let x1 = min(right + pad, w - 1), y1 = min(bottom + pad, h - 1)
        // Der Puffer liegt mit Zeile 0 oben (CGContext-Speicher ist von oben
        // nach unten angeordnet) — also direkt ausschneidbar
        guard let cropped = cg.cropping(to: CGRect(x: x0, y: y0, width: x1 - x0 + 1, height: y1 - y0 + 1)) else { return image }
        return UIImage(cgImage: cropped, scale: 1, orientation: .up)
    }
}

/// Einmal mit dem Finger unterschreiben (Port von `SignaturePad`).
struct SignaturePadSheet: View {
    let slot: Int
    let onCancel: () -> Void
    let onSave: (UIImage) -> Void

    @Environment(\.palette) private var palette
    @State private var strokes: [[CGPoint]] = []
    @State private var current: [CGPoint] = []
    @State private var padSize: CGSize = .zero

    private var hasDrawing: Bool { !strokes.isEmpty || current.count > 1 }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 10) {
                Text(L("editor_signature_hint"))
                    .font(.footnote)
                    .foregroundStyle(palette.onSurfaceVariant)
                GeometryReader { geo in
                    Canvas { ctx, _ in
                        for pts in strokes + [current] where pts.count >= 2 {
                            var path = Path()
                            path.move(to: pts[0])
                            for p in pts.dropFirst() { path.addLine(to: p) }
                            ctx.stroke(path, with: .color(.black),
                                       style: StrokeStyle(lineWidth: 3, lineCap: .round, lineJoin: .round))
                        }
                    }
                    .background(Color.white)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0, coordinateSpace: .local)
                            .onChanged { v in current.append(v.location) }
                            .onEnded { _ in
                                if current.count > 1 { strokes.append(current) }
                                current = []
                            }
                    )
                    .onAppear { padSize = geo.size }
                    .onChange(of: geo.size) { _, s in padSize = s }
                }
                .frame(height: 200)
                .overlay(RoundedRectangle(cornerRadius: 4).stroke(palette.outlineVariant))
                Button(L("editor_signature_clear")) {
                    strokes = []
                    current = []
                }
                Spacer()
            }
            .padding(16)
            .navigationTitle(L(slot == 1 ? "editor_initials_title" : "editor_signature_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_cancel"), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("editor_signature_save")) { onSave(render()) }
                        .disabled(!hasDrawing)
                }
            }
        }
        .presentationDetents([.medium])
        // Kein Wegwischen beim Unterschreiben nach unten
        .interactiveDismissDisabled()
    }

    /// In doppelter Auflösung zeichnen: die Unterschrift wird auf der Seite
    /// oft größer dargestellt als hier im Feld.
    private func render() -> UIImage {
        let f: CGFloat = 3
        let size = CGSize(width: max(padSize.width, 1) * f, height: max(padSize.height, 1) * f)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let all = strokes + (current.count > 1 ? [current] : [])
        let img = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            let cg = ctx.cgContext
            cg.setStrokeColor(UIColor.black.cgColor)
            cg.setLineWidth(3 * f)
            cg.setLineCap(.round)
            cg.setLineJoin(.round)
            for pts in all where pts.count >= 2 {
                cg.move(to: CGPoint(x: pts[0].x * f, y: pts[0].y * f))
                for p in pts.dropFirst() { cg.addLine(to: CGPoint(x: p.x * f, y: p.y * f)) }
                cg.strokePath()
            }
        }
        return SignatureStore.trim(img)
    }
}
