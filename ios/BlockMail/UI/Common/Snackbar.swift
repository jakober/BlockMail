import SwiftUI

/// Snackbar-Ersatz: kurze Meldung unten, optional mit Aktion („Rückgängig“).
@MainActor
@Observable
final class SnackbarState {
    struct Item: Identifiable, Equatable {
        let id = UUID()
        let text: String
        let actionLabel: String?
        static func == (a: Item, b: Item) -> Bool { a.id == b.id }
    }

    var current: Item?
    @ObservationIgnored private var action: (() -> Void)?
    @ObservationIgnored private var hideTask: Task<Void, Never>?

    /// Zeigt eine Meldung (wie `snackbar.showSnackbar`).
    func show(_ text: String, actionLabel: String? = nil, duration: Double = 3.5, action: (() -> Void)? = nil) {
        let item = Item(text: text, actionLabel: actionLabel)
        current = item
        self.action = action
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(nanoseconds: UInt64(duration * 1_000_000_000))
            if !Task.isCancelled, self.current == item { self.current = nil }
        }
    }

    func performAction() {
        action?()
        action = nil
        current = nil
    }
}

private struct SnackbarOverlay: ViewModifier {
    let state: SnackbarState
    @Environment(\.palette) private var palette

    func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let item = state.current {
                HStack(spacing: 12) {
                    Text(item.text)
                        .font(.subheadline)
                        .foregroundStyle(Color.white)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let label = item.actionLabel {
                        Button(label) { state.performAction() }
                            .font(.subheadline.bold())
                            .foregroundStyle(palette.dark ? palette.primary : palette.primaryContainer)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 12)
                .background(RoundedRectangle(cornerRadius: 10).fill(Color(white: 0.2)))
                .padding(.horizontal, 12)
                .padding(.bottom, 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
                .id(item.id)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: state.current)
    }
}

extension View {
    /// Blendet die Meldungen eines `SnackbarState` unten ein.
    func snackbar(_ state: SnackbarState) -> some View { modifier(SnackbarOverlay(state: state)) }
}
