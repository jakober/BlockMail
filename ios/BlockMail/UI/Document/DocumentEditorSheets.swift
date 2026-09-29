import SwiftUI
import PDFKit
import UIKit
import VisionKit

// MARK: - Dialoge (AlertDialog-Ersatz)

/// Alle kleinen Rückfragen des Editors: Passwort, Text, Auszug, Schutz,
/// Verkleinern, KI-Überarbeitung, Schwärzungs-Warnung.
struct EditorAlerts: ViewModifier {
    @Bindable var model: DocumentEditorModel

    func body(content: Content) -> some View {
        // Vorab festhalten: Beim Tippen auf einen Knopf wird der Merker über
        // die Bindung schon geleert, bevor die Aktion läuft
        let pendingText = model.textAsk
        let pendingRedact = model.redactWarnAction
        return content
            .alert(L("editor_password_title"), isPresented: $model.askPassword) {
                SecureField(L("editor_password_label"), text: $model.passwordInput)
                Button(L("editor_password_open")) { model.submitPassword() }
                Button(L("editor_cancel"), role: .cancel) {}
            } message: {
                Text(L(model.passwordWrong ? "editor_password_wrong" : "editor_password_hint"))
            }
            .alert(L("editor_text_title"), isPresented: Binding(
                get: { model.textAsk != nil },
                set: { if !$0 { model.textAsk = nil } }
            )) {
                TextField("", text: $model.textValue)
                Button(L("editor_text_ok")) { model.confirmText(pendingText) }
                Button(L("editor_cancel"), role: .cancel) { model.textAsk = nil }
            }
            .alert(L("editor_extract_title"), isPresented: $model.extractAsk) {
                TextField(L("editor_extract_from"), text: $model.extractFrom)
                    .keyboardType(.numberPad)
                TextField(L("editor_extract_to"), text: $model.extractTo)
                    .keyboardType(.numberPad)
                Button(L("editor_extract_ok")) { model.confirmExtract() }
                Button(L("editor_cancel"), role: .cancel) {}
            }
            .alert(L("editor_protect_title"), isPresented: $model.protectAsk) {
                SecureField(L("editor_password_label"), text: $model.protectPassword)
                Button(L("editor_protect_ok")) { model.protectAndSave() }
                Button(L("editor_cancel"), role: .cancel) { model.protectPassword = "" }
            } message: {
                Text(L("editor_protect_hint"))
            }
            .alert(L("editor_compress_title"), isPresented: $model.compressAsk) {
                Button(L("editor_compress_ok")) { model.compressAndSave() }
                Button(L("editor_cancel"), role: .cancel) {}
            } message: {
                Text(L("editor_compress_text"))
            }
            .alert(L("editor_ai_revise_title"), isPresented: $model.aiReviseAsk) {
                TextField(L("editor_ai_revise_hint"), text: $model.aiReviseText)
                Button(L("editor_ai_revise_go")) { model.aiRevise() }
                Button(L("editor_cancel"), role: .cancel) { model.aiReviseText = "" }
            }
            .alert(L("editor_redact_warn_title"), isPresented: Binding(
                get: { model.redactWarnAction != nil },
                set: { if !$0 { model.redactWarnAction = nil } }
            )) {
                Button(L("editor_redact_continue")) { model.confirmRedactWarning(pendingRedact) }
                Button(L("editor_cancel"), role: .cancel) { model.redactWarnAction = nil }
            } message: {
                Text(L("editor_redact_warn_text"))
            }
    }
}

// MARK: - Volltextsuche

struct EditorSearchSheet: View {
    @Bindable var model: DocumentEditorModel
    @Environment(\.palette) private var palette

    private var canSearch: Bool {
        model.searchQuery.trimmingCharacters(in: .whitespaces).count >= 2 && !model.searchBusy
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack {
                        TextField(L("editor_search_title"), text: $model.searchQuery)
                            .submitLabel(.search)
                            .onSubmit { if canSearch { model.runSearch() } }
                        Button(L("editor_search_go")) { model.runSearch() }
                            .disabled(!canSearch)
                    }
                }
                if model.searchBusy {
                    HStack { Spacer(); ProgressView(); Spacer() }
                } else if let hits = model.searchResults {
                    if hits.isEmpty {
                        Text(L("editor_search_none"))
                            .foregroundStyle(palette.onSurfaceVariant)
                    } else {
                        Section {
                            ForEach(hits) { hit in
                                Button {
                                    model.sheet = nil
                                    model.scrollTo(hit.page)
                                } label: {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(L("editor_search_page", hit.page + 1))
                                            .font(.subheadline.weight(.semibold))
                                            .foregroundStyle(palette.onSurface)
                                        Text(hit.snippet)
                                            .font(.footnote)
                                            .foregroundStyle(palette.onSurfaceVariant)
                                            .lineLimit(2)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle(L("editor_search_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_back")) { model.sheet = nil }
                }
            }
        }
    }
}

// MARK: - KI-Assistent

struct EditorAISheet: View {
    @Bindable var model: DocumentEditorModel
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollViewReader { proxy in
                    ScrollView {
                        VStack(alignment: .leading, spacing: 10) {
                            if model.aiMessages.isEmpty {
                                Text(L("editor_ai_hint"))
                                    .font(.footnote)
                                    .foregroundStyle(palette.onSurfaceVariant)
                            }
                            ForEach(model.aiMessages) { m in
                                Text(m.text)
                                    .font(.body)
                                    .foregroundStyle(m.user ? palette.primary : palette.onSurface)
                                    .textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .id(m.id)
                            }
                            if model.aiBusy {
                                ProgressView().padding(.top, 4)
                            }
                        }
                        .padding(16)
                    }
                    .onChange(of: model.aiMessages.count) { _, _ in
                        if let last = model.aiMessages.last {
                            withAnimation { proxy.scrollTo(last.id, anchor: .bottom) }
                        }
                    }
                }
                Divider()
                HStack(spacing: 8) {
                    TextField(L("editor_ai_placeholder"), text: $model.aiInput)
                        .textFieldStyle(.roundedBorder)
                        .submitLabel(.send)
                        .onSubmit { model.sendAI() }
                    Button {
                        model.sendAI()
                    } label: {
                        if model.aiBusy {
                            ProgressView()
                        } else {
                            Text(L("editor_ai_send"))
                        }
                    }
                    .disabled(model.aiInput.trimmingCharacters(in: .whitespaces).isEmpty || model.aiBusy)
                }
                .padding(12)
            }
            .navigationTitle(L("editor_ai_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_back")) { model.sheet = nil }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - Inhaltsverzeichnis

struct EditorOutlineSheet: View {
    let model: DocumentEditorModel
    let entries: [DocumentPDF.OutlineEntry]

    var body: some View {
        NavigationStack {
            List(entries) { e in
                Button {
                    model.sheet = nil
                    model.scrollTo(e.page)
                } label: {
                    Text(e.title)
                        .lineLimit(1)
                        .padding(.leading, CGFloat(e.depth) * 16)
                }
            }
            .navigationTitle(L("editor_menu_outline"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_cancel")) { model.sheet = nil }
                }
            }
        }
    }
}

// MARK: - Formular ausfüllen

struct EditorFormSheet: View {
    let model: DocumentEditorModel
    let form: DocumentPDF.Form
    @State private var texts: [String: String]
    @State private var checks: [String: Bool]

    init(model: DocumentEditorModel, form: DocumentPDF.Form) {
        self.model = model
        self.form = form
        var t: [String: String] = [:]
        for e in form.texts { t[e.name] = e.value }
        var c: [String: Bool] = [:]
        for e in form.checks { c[e.name] = e.checked }
        _texts = State(initialValue: t)
        _checks = State(initialValue: c)
    }

    var body: some View {
        NavigationStack {
            Form {
                if !form.texts.isEmpty {
                    Section {
                        ForEach(form.texts) { e in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(e.label)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                TextField("", text: Binding(
                                    get: { texts[e.name] ?? "" },
                                    set: { texts[e.name] = $0 }
                                ))
                            }
                        }
                    }
                }
                if !form.checks.isEmpty {
                    Section {
                        ForEach(form.checks) { e in
                            Toggle(e.label, isOn: Binding(
                                get: { checks[e.name] ?? false },
                                set: { checks[e.name] = $0 }
                            ))
                        }
                    }
                }
            }
            .navigationTitle(L("editor_form_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_cancel")) { model.sheet = nil }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("editor_form_apply")) {
                        let t = texts
                        let c = checks
                        model.sheet = nil
                        model.applyForm(texts: t, checks: c)
                    }
                }
            }
        }
    }
}

// MARK: - Seitenübersicht

/// Miniaturen aller Seiten: antippen springt hin, Pfeile oder Ziehen &
/// Ablegen verschieben, Plus-Kachel fügt eine leere Seite ein.
struct EditorPagesSheet: View {
    @Bindable var model: DocumentEditorModel
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 8) {
                    Text(L("editor_pages_hint"))
                        .font(.footnote)
                        .foregroundStyle(palette.onSurfaceVariant)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 110), spacing: 12)], spacing: 4) {
                        ForEach(0..<model.pageCount, id: \.self) { i in
                            tile(i)
                        }
                        addTile
                    }
                }
                .padding(12)
            }
            .navigationTitle(L("editor_pages_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_back")) { model.sheet = nil }
                }
            }
        }
    }

    private func tile(_ i: Int) -> some View {
        let s = i < model.pageSizes.count ? model.pageSizes[i] : CGSize(width: 595, height: 842)
        let aspect = min(max(s.width / max(s.height, 1), 0.2), 5)
        return VStack(spacing: 2) {
            ZStack {
                Color.white
                if let img = model.thumbs[i] {
                    Image(uiImage: img).resizable()
                } else {
                    ProgressView()
                }
            }
            .aspectRatio(aspect, contentMode: .fit)
            .overlay(Rectangle().stroke(palette.outlineVariant))
            .contentShape(Rectangle())
            .onTapGesture {
                model.sheet = nil
                model.scrollTo(i)
            }
            .draggable("\(i)")
            .dropDestination(for: String.self) { items, _ in
                guard let s = items.first, let from = Int(s), from != i else { return false }
                model.movePage(from, i)
                return true
            }
            .task(id: "\(i)-\(model.generation)") { model.ensureThumb(i) }
            HStack(spacing: 4) {
                Button { model.movePage(i, i - 1) } label: {
                    Image(systemName: "chevron.left").padding(6)
                }
                .disabled(i == 0 || model.busyOp)
                .accessibilityLabel(L("editor_page_move_forward"))
                Text("\(i + 1)").font(.subheadline.weight(.medium))
                Button { model.movePage(i, i + 1) } label: {
                    Image(systemName: "chevron.right").padding(6)
                }
                .disabled(i >= model.pageCount - 1 || model.busyOp)
                .accessibilityLabel(L("editor_page_move_back"))
            }
        }
    }

    private var addTile: some View {
        VStack(spacing: 2) {
            Button {
                model.sheet = .insertPosition(.blank)
            } label: {
                ZStack {
                    RoundedRectangle(cornerRadius: 4).fill(palette.surfaceVariant.opacity(0.6))
                    Image(systemName: "plus")
                        .font(.system(size: 34))
                        .foregroundStyle(palette.primary)
                }
                .aspectRatio(0.7, contentMode: .fit)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("editor_menu_insert_blank"))
            Text(L("editor_pages_add"))
                .font(.caption)
                .foregroundStyle(palette.onSurfaceVariant)
                .padding(.vertical, 8)
        }
    }
}

// MARK: - Einfügeposition

/// Fragt, wo neue Seiten hin sollen (0 = vorne, Seitenzahl = hinten).
struct EditorInsertPositionSheet: View {
    let model: DocumentEditorModel
    let source: InsertSource
    @State private var text: String

    init(model: DocumentEditorModel, source: InsertSource) {
        self.model = model
        self.source = source
        _text = State(initialValue: "\(model.pageIndex + 1)")
    }

    private func pick(_ at: Int) {
        let pos = min(max(at, 0), model.pageCount)
        model.sheet = nil
        switch source {
        case .blank: model.insertBlank(at: pos)
        case .pdf(let other): model.insertPdf(other, at: pos)
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Button(L("editor_insert_front")) { pick(0) }
                    Button(L("editor_insert_current")) { pick(model.pageIndex + 1) }
                    Button(L("editor_insert_end")) { pick(model.pageCount) }
                }
                Section {
                    TextField(L("editor_insert_after_label"), text: $text)
                        .keyboardType(.numberPad)
                        .onChange(of: text) { _, v in
                            let digits = String(v.filter { $0.isNumber }.prefix(4))
                            if digits != v { text = digits }
                        }
                    Button(L("editor_insert_do")) { pick(Int(text) ?? model.pageCount) }
                }
            }
            .navigationTitle(L("editor_insert_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("editor_cancel")) { model.sheet = nil }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

// MARK: - UIKit-Brücken

/// Teilen-Blatt.
struct EditorActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// „In Dateien sichern“ (Ersatz für CreateDocument).
struct EditorExportPicker: UIViewControllerRepresentable {
    let url: URL
    let onDone: (Bool) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let vc = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: UIDocumentPickerViewController, context: Context) {}

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onDone: (Bool) -> Void
        init(onDone: @escaping (Bool) -> Void) { self.onDone = onDone }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            onDone(true)
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            onDone(false)
        }
    }
}

/// Dokumentenscanner (VisionKit) — Port von „Scannen“ (Kamera).
struct EditorScannerView: UIViewControllerRepresentable {
    let onFinish: ([UIImage]) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish, onCancel: onCancel) }

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let vc = VNDocumentCameraViewController()
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let onFinish: ([UIImage]) -> Void
        let onCancel: () -> Void

        init(onFinish: @escaping ([UIImage]) -> Void, onCancel: @escaping () -> Void) {
            self.onFinish = onFinish
            self.onCancel = onCancel
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFinishWith scan: VNDocumentCameraScan) {
            var images: [UIImage] = []
            for i in 0..<scan.pageCount { images.append(scan.imageOfPage(at: i)) }
            onFinish(images)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController,
                                          didFailWithError error: Error) {
            onCancel()
        }
    }
}
