import SwiftUI

// Vorübergehende Platzhalter, bis der Dokument-Editor eingebunden ist.
struct DocumentEditorScreen: View {
    var body: some View { Text("…") }
}

struct NewPdfDialog: View {
    var body: some View { Text("…") }
}

extension DocumentEditing {
    @MainActor static func openExternal(_ url: URL) -> Bool { false }
}
