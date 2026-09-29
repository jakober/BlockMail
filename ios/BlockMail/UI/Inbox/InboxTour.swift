import SwiftUI
import Observation

/// Live-Einführungs-Tour (Port von `InboxTour` + `TourOverlay`): dunkelt den
/// Posteingang ab und hebt die echten Bedienelemente nacheinander per
/// Spotlight hervor. Startet einmalig nach der Einrichtung; erneut über
/// Einstellungen („Einführung ansehen“ → `InboxTour.start()`).
@MainActor
@Observable
final class InboxTour {
    static let shared = InboxTour()

    var active = false
    var step = 0
    /// Positionen der markierten Ziele (globale Fensterkoordinaten).
    var targets: [String: CGRect] = [:]

    private init() {}

    /// Startet die Tour (wie Android `InboxTour.start()`).
    static func start() {
        shared.step = 0
        shared.active = true
    }

    /// Beendet die Tour und merkt sie als gesehen.
    static func finish() {
        shared.active = false
        Prefs.shared.tourShown = true
    }

    struct Step {
        let key: String
        let title: String
        let text: String
    }

    /// Schritte der Tour (Texte ohne Abo-/Pro-Hinweise).
    static var steps: [Step] {
        [
            Step(key: "headerLeft", title: L("tour_step_theme_title"), text: L("tour_step_theme_text")),
            Step(key: "headerRight", title: L("tour_step_menu_title"), text: L("inbox_ios_tour_menu_text")),
            Step(key: "folderMenu", title: L("tour_step_attachments_title"), text: L("tour_step_attachments_text")),
            Step(key: "search", title: L("tour_2_title"), text: L("inbox_ios_tour_ask_text")),
            Step(key: "aiFab", title: L("tour_step_ai_title"), text: L("inbox_ios_tour_ai_text")),
            Step(key: "list", title: L("tour_3_title"), text: L("tour_3_text")),
            Step(key: "", title: L("tour_step_extras_title"), text: L("tour_step_extras_text")),
            Step(key: "fab", title: L("tour_step_compose_title"), text: L("tour_step_compose_text"))
        ]
    }
}

/// Markiert ein Bedienelement als Tour-Ziel (Position wird mitgeschrieben).
private struct InboxTourTarget: ViewModifier {
    let key: String

    func body(content: Content) -> some View {
        content.background(
            GeometryReader { g in
                Color.clear
                    .onAppear { InboxTour.shared.targets[key] = g.frame(in: .global) }
                    .onChange(of: g.frame(in: .global)) { _, new in
                        if InboxTour.shared.active { InboxTour.shared.targets[key] = new }
                    }
            }
        )
    }
}

extension View {
    func inboxTourTarget(_ key: String) -> some View { modifier(InboxTourTarget(key: key)) }
}

/// Spotlight-Overlay der Tour. Tipp irgendwo (oder „Weiter“) springt weiter.
struct InboxTourOverlay: View {
    @Environment(\.palette) private var palette
    private var tour: InboxTour { InboxTour.shared }

    var body: some View {
        let steps = InboxTour.steps
        let idx = min(max(tour.step, 0), steps.count - 1)
        let s = steps[idx]
        GeometryReader { geo in
            let origin = geo.frame(in: .global).origin
            let rect: CGRect? = tour.targets[s.key].map { raw in
                var r = raw.offsetBy(dx: -origin.x, dy: -origin.y)
                // Wisch-Schritt: nur die oberste Mail-Zeile ausstanzen
                if s.key == "list" { r.size.height = min(r.height, 116) }
                return r
            }
            let hole = rect?.insetBy(dx: -12, dy: -12)
            ZStack(alignment: .top) {
                Path { p in
                    p.addRect(CGRect(origin: .zero, size: geo.size))
                    if let hole { p.addRoundedRect(in: hole, cornerSize: CGSize(width: 14, height: 14)) }
                }
                .fill(Color.black.opacity(0.7), style: FillStyle(eoFill: true))
                if let hole {
                    RoundedRectangle(cornerRadius: 14)
                        .stroke(Color.white.opacity(0.85), lineWidth: 2)
                        .frame(width: hole.width, height: hole.height)
                        .position(x: hole.midX, y: hole.midY)
                }
                card(s, idx: idx, count: steps.count, rect: rect, size: geo.size)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(Rectangle())
            .onTapGesture { next(idx, steps.count) }
        }
        .ignoresSafeArea()
    }

    @ViewBuilder
    private func card(_ s: InboxTour.Step, idx: Int, count: Int, rect: CGRect?, size: CGSize) -> some View {
        let placement: String = {
            guard let rect else { return "center" }
            if s.key == "headerRight" || s.key == "folderMenu" { return "bottom" }
            return (size.height - rect.maxY) >= rect.minY ? "below" : "above"
        }()
        let content = VStack(alignment: .leading, spacing: 6) {
            Text(s.title).font(.headline.bold())
            Text(s.text).font(.subheadline)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Button(L("tour_skip")) { InboxTour.finish() }
                Spacer()
                Text("\(idx + 1)/\(count)")
                    .font(.caption)
                    .foregroundStyle(palette.onSurfaceVariant)
                Button(idx == count - 1 ? L("tour_done") : L("tour_next")) { next(idx, count) }
                    .buttonStyle(.borderedProminent)
                    .padding(.leading, 12)
            }
            .padding(.top, 6)
        }
        .foregroundStyle(palette.onSurface)
        .padding(18)
        .frame(maxWidth: 520, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 16).fill(palette.surface))
        .padding(.horizontal, 20)

        VStack {
            switch placement {
            case "below":
                Spacer().frame(height: (rect?.maxY ?? 0) + 28)
                content
                Spacer()
            case "above":
                Spacer()
                content
                Spacer().frame(height: max(0, size.height - (rect?.minY ?? 0) + 28))
            case "bottom":
                Spacer()
                content
                Spacer().frame(height: 48)
            default:
                Spacer()
                content
                Spacer()
            }
        }
        .frame(width: size.width, height: size.height)
    }

    private func next(_ idx: Int, _ count: Int) {
        if idx >= count - 1 { InboxTour.finish() } else { InboxTour.shared.step = idx + 1 }
    }
}
