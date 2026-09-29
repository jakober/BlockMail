import SwiftUI
import PDFKit

/// „PDF erstellen“ (Port von `NewPdfDialog.kt`): eine leere A4-Seite sofort,
/// oder den Inhalt von der KI schreiben lassen. Das fertige Dokument öffnet
/// direkt im Editor; das letzte KI-Dokument lässt sich hier per
/// Änderungswunsch überarbeiten (Quelltext liegt in Prefs.aiPdfTitle/-Body).
struct NewPdfDialog: View {
    @Environment(\.palette) private var palette
    @State private var prompt = ""
    @State private var changes = ""
    @State private var busy = false
    @State private var error: String?

    init() {}

    private var prefs: Prefs { Prefs.shared }

    private var hasLast: Bool {
        !prefs.aiPdfTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
            !prefs.aiPdfBody.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Button { createBlank() } label: {
                        Label(L("newpdf_blank"), systemImage: "doc.badge.plus")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.bordered)
                    .disabled(busy)

                    Divider()

                    Text(L("newpdf_ai_label"))
                        .font(.subheadline)
                        .foregroundStyle(palette.onSurface)
                    TextField(L("newpdf_ai_hint"), text: $prompt, axis: .vertical)
                        .lineLimit(2...6)
                        .textFieldStyle(.roundedBorder)
                        .disabled(busy)
                    Button { createWithAi() } label: {
                        Label(L("newpdf_ai_create"), systemImage: "sparkles")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 6)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy || prompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)

                    // Nacharbeit am letzten KI-Dokument: Änderungswunsch an die KI
                    if hasLast {
                        Divider()
                        let title = prefs.aiPdfTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                        Text(L("newpdf_ai_revise_label", title.isEmpty ? L("newpdf_title") : title))
                            .font(.subheadline)
                            .foregroundStyle(palette.onSurface)
                        TextField(L("newpdf_ai_revise_hint"), text: $changes, axis: .vertical)
                            .lineLimit(2...6)
                            .textFieldStyle(.roundedBorder)
                            .disabled(busy)
                        Button { reviseWithAi() } label: {
                            Label(L("newpdf_ai_revise"), systemImage: "sparkles")
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 6)
                        }
                        .buttonStyle(.bordered)
                        .disabled(busy || changes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }

                    if busy {
                        ProgressView()
                            .progressViewStyle(.linear)
                    }
                    if let error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(palette.error)
                    }
                }
                .padding(20)
            }
            .navigationTitle(L("newpdf_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("newpdf_cancel")) { AppNav.shared.showNewPdf = false }
                        .disabled(busy)
                }
            }
        }
        .interactiveDismissDisabled(busy)
        .presentationDetents([.medium, .large])
    }

    private static func stamp() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyyMMdd-HHmm"
        return f.string(from: Date())
    }

    /// Das neue Dokument geht denselben Weg wie ein Mail-Anhang in den Editor.
    /// aiDocument: nur KI-Dokumente bekommen im Editor „Mit KI überarbeiten“.
    @MainActor
    private func openInEditor(_ data: Data, name: String, aiDocument: Bool) {
        DocumentEditing.pending = DocumentEditing.Source(
            name: name, mime: "application/pdf", data: data, url: nil, replyUid: nil,
            account: "", origin: .mail, canOverwrite: false, aiDocument: aiDocument)
        AppNav.shared.showNewPdf = false
        AppNav.shared.push(.editor)
    }

    @MainActor
    private func createBlank() {
        guard !busy else { return }
        error = nil
        let data = DocumentPDF.blankData()
        if data.isEmpty {
            error = L("newpdf_failed")
        } else {
            openInEditor(data, name: "Dokument-\(Self.stamp()).pdf", aiDocument: false)
        }
    }

    /// Gemeinsamer Endweg für Erstellen und Überarbeiten: Text setzen, fürs
    /// nächste Überarbeiten merken, im Editor öffnen.
    @MainActor
    private func renderAndOpen(title: String, body: String) async {
        let data: Data? = await Task.detached(priority: .userInitiated) {
            DocumentPDF.textDocument(title: title, body: body)
        }.value
        guard let data else {
            error = L("newpdf_failed")
            return
        }
        let safe = String(title.replacingOccurrences(of: "[^\\p{L}\\p{N} _-]", with: "", options: .regularExpression)
            .trimmingCharacters(in: .whitespaces).prefix(40))
        let name = (safe.isEmpty ? "Dokument-\(Self.stamp())" : safe) + ".pdf"
        prefs.aiPdfTitle = title
        prefs.aiPdfBody = body
        openInEditor(data, name: name, aiDocument: true)
    }

    @MainActor
    private func createWithAi() {
        let p = prompt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !busy, !p.isEmpty else { return }
        busy = true
        error = nil
        Task { @MainActor in
            do {
                let (title, body) = try await ClaudeClient.composeDocument(p)
                await renderAndOpen(title: title, body: body)
            } catch {
                self.error = error.localizedDescription.isEmpty ? L("newpdf_failed") : error.localizedDescription
            }
            busy = false
        }
    }

    @MainActor
    private func reviseWithAi() {
        let c = changes.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !busy, !c.isEmpty, hasLast else { return }
        busy = true
        error = nil
        Task { @MainActor in
            do {
                let (title, body) = try await ClaudeClient.reviseDocument(
                    title: prefs.aiPdfTitle, body: prefs.aiPdfBody, changes: c)
                await renderAndOpen(title: title, body: body)
            } catch {
                self.error = error.localizedDescription.isEmpty ? L("newpdf_failed") : error.localizedDescription
            }
            busy = false
        }
    }
}
