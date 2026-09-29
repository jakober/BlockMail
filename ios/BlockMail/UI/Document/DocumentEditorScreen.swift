import SwiftUI
import PDFKit
import PhotosUI
import UniformTypeIdentifiers
import VisionKit

/// Dokument-/PDF-Editor (Port von `AttachmentEditorScreen`): unterschreiben,
/// zeichnen, markieren, Stempel, Text, Bilder, Formen, schwärzen; Seiten
/// drehen/löschen/umsortieren/einfügen; Suche, Formulare, Inhaltsverzeichnis,
/// Auszug, Passwortschutz, Verkleinern; KI-Assistent und KI-Überarbeitung.
/// Die Quelle kommt über `DocumentEditing.pending`.
struct DocumentEditorScreen: View {
    @State private var model: DocumentEditorModel
    @State private var imageItem: PhotosPickerItem?
    @State private var appendItems: [PhotosPickerItem] = []
    @Environment(\.palette) private var palette
    @Environment(\.horizontalSizeClass) private var hSize

    @MainActor
    init() {
        _model = State(initialValue: DocumentEditorModel(source: DocumentEditing.pending))
    }

    var body: some View {
        GeometryReader { geo in
            let wide = hSize == .regular && geo.size.width >= 600
            if wide {
                // Tablet/Querformat: Werkzeuge links, Dokument rechts
                HStack(spacing: 0) {
                    ScrollView {
                        EditorToolPanel(model: model, wide: true)
                            .padding(12)
                    }
                    .frame(width: 340)
                    .background(palette.surfaceContainer)
                    EditorDocArea(model: model)
                }
            } else {
                VStack(spacing: 0) {
                    EditorDocArea(model: model)
                    EditorToolPanel(model: model, wide: false)
                        .padding(12)
                        .background(palette.surfaceContainer)
                }
            }
        }
        .background(palette.surface)
        .navigationTitle(model.source?.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { toolbarContent }
        .snackbar(model.snackbar)
        .task { await model.start() }
        .onAppear {
            // Ohne Quelle gibt es nichts zu zeigen
            if model.source == nil, AppNav.shared.path.last == .editor { AppNav.shared.pop() }
        }
        .onDisappear {
            // Erst beim Verlassen den Merker leeren (wie Android onDispose)
            DocumentEditing.pending = nil
        }
        .sheet(item: $model.sheet) { sheet in
            sheetContent(sheet)
                .environment(\.palette, palette)
        }
        .modifier(EditorAlerts(model: model))
        .photosPicker(isPresented: $model.showImagePicker, selection: $imageItem, matching: .images)
        .onChange(of: imageItem) { _, item in
            guard let item else { return }
            imageItem = nil
            Task {
                let data = try? await item.loadTransferable(type: Data.self)
                model.loadPlaceImage(data)
            }
        }
        .photosPicker(isPresented: $model.showAppendImages, selection: $appendItems, maxSelectionCount: 30,
                      matching: .images)
        .onChange(of: appendItems) { _, items in
            guard !items.isEmpty else { return }
            appendItems = []
            Task {
                var datas: [Data] = []
                for item in items {
                    if let d = try? await item.loadTransferable(type: Data.self) { datas.append(d) }
                }
                model.appendImageData(datas)
            }
        }
        .fileImporter(isPresented: $model.showPdfImporter, allowedContentTypes: [.pdf]) { result in
            if case .success(let url) = result { model.pickedPdf(url) }
        }
    }

    // MARK: Werkzeugleiste oben

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            if model.aiAvailable {
                Button { model.sheet = .ai } label: {
                    Image(systemName: "sparkles")
                }
                .accessibilityLabel(L("editor_ai_title"))
            }
            if model.isPdf && model.ready {
                Button { model.sheet = .search } label: {
                    Image(systemName: "magnifyingglass")
                }
                .accessibilityLabel(L("editor_search_title"))
            }
            Button { model.undo() } label: {
                Image(systemName: "arrow.uturn.backward")
            }
            .disabled(model.history.isEmpty)
            .accessibilityLabel(L("editor_undo"))
            Menu {
                menuContent
            } label: {
                Image(systemName: "ellipsis.circle")
            }
            .accessibilityLabel(L("editor_more"))
        }
    }

    @ViewBuilder
    private var menuContent: some View {
        let ready = model.ready && !model.failed
        Button { model.runAction(.saveAs) } label: {
            Label(L("editor_menu_save_as"), systemImage: "folder")
        }
        .disabled(!ready || model.saving)
        Button { model.runAction(.share) } label: {
            Label(L("editor_menu_share"), systemImage: "square.and.arrow.up")
        }
        .disabled(!ready || model.saving)
        if model.isPdf {
            Button { model.runAction(.print) } label: {
                Label(L("editor_menu_print"), systemImage: "printer")
            }
            .disabled(!ready || model.saving)
        }
        if let src = model.source, src.origin != .mail {
            Button { model.runAction(.sendMail) } label: {
                Label(L("editor_menu_send_mail"), systemImage: "paperplane")
            }
            .disabled(!ready || model.saving)
        }
        // KI-Überarbeiten: nur bei Dokumenten, die die App selbst per KI erstellt hat
        if model.canAIRevise {
            Button {
                model.aiReviseText = ""
                model.aiReviseAsk = true
            } label: {
                Label(L("editor_menu_ai_revise"), systemImage: "sparkles")
            }
            .disabled(model.busyOp)
        }
        if model.isPdf && ready {
            Divider()
            Button { model.sheet = .pages } label: {
                Label(L("editor_menu_pages"), systemImage: "square.grid.2x2")
            }
            Button { model.openOutline() } label: {
                Label(L("editor_menu_outline"), systemImage: "list.bullet.indent")
            }
            Button { model.nightMode.toggle() } label: {
                Label(L(model.nightMode ? "editor_menu_night_off" : "editor_menu_night_on"), systemImage: "moon")
            }
            Button { model.openForm() } label: {
                Label(L("editor_menu_form"), systemImage: "list.bullet.rectangle")
            }
            if VNDocumentCameraViewController.isSupported {
                Button { model.sheet = .scan } label: {
                    Label(L("editor_menu_scan"), systemImage: "doc.viewfinder")
                }
            }
            Button { model.openExtract() } label: {
                Label(L("editor_menu_extract"), systemImage: "doc.on.doc")
            }
            .disabled(model.pageCount <= 1)
            Button {
                model.protectPassword = ""
                model.protectAsk = true
            } label: {
                Label(L("editor_menu_protect"), systemImage: "lock")
            }
            Button { model.compressAsk = true } label: {
                Label(L("editor_menu_compress"), systemImage: "arrow.down.right.and.arrow.up.left")
            }
            Divider()
            Button { model.rotatePage(model.pageIndex, cw: false) } label: {
                Label(L("editor_menu_rotate_left"), systemImage: "rotate.left")
            }
            Button { model.rotatePage(model.pageIndex, cw: true) } label: {
                Label(L("editor_menu_rotate_right"), systemImage: "rotate.right")
            }
            Button(role: .destructive) { model.deletePage(model.pageIndex) } label: {
                Label(L("editor_menu_delete_page"), systemImage: "trash")
            }
            .disabled(model.pageCount <= 1)
            Button { model.sheet = .insertPosition(.blank) } label: {
                Label(L("editor_menu_insert_blank"), systemImage: "doc.badge.plus")
            }
            Button { model.showPdfImporter = true } label: {
                Label(L("editor_menu_append_pdf"), systemImage: "doc.richtext")
            }
            Button { model.showAppendImages = true } label: {
                Label(L("editor_menu_append_images"), systemImage: "photo.on.rectangle")
            }
        }
    }

    // MARK: Blätter

    @ViewBuilder
    private func sheetContent(_ sheet: EditorSheet) -> some View {
        switch sheet {
        case .signature(let slot):
            SignaturePadSheet(slot: slot,
                              onCancel: { model.sheet = nil },
                              onSave: { img in model.saveSignature(img, slot: slot) })
        case .search:
            EditorSearchSheet(model: model)
        case .ai:
            EditorAISheet(model: model)
        case .outline(let entries):
            EditorOutlineSheet(model: model, entries: entries)
        case .form(let form):
            EditorFormSheet(model: model, form: form)
        case .pages:
            EditorPagesSheet(model: model)
        case .share(let url):
            EditorActivityView(items: [url])
                .ignoresSafeArea()
        case .export(let url):
            EditorExportPicker(url: url) { ok in
                model.sheet = nil
                model.exportFinished(ok)
            }
            .ignoresSafeArea()
        case .scan:
            EditorScannerView(onFinish: { images in
                model.sheet = nil
                model.appendImages(images)
            }, onCancel: {
                model.sheet = nil
            })
            .ignoresSafeArea()
        case .insertPosition(let src):
            EditorInsertPositionSheet(model: model, source: src)
        }
    }
}

// MARK: - Dokumentbereich

/// Durchgehende Seitenliste mit Zoom (Port von docArea).
struct EditorDocArea: View {
    @Bindable var model: DocumentEditorModel
    @Environment(\.palette) private var palette
    @State private var pinchBase: CGFloat = 1

    var body: some View {
        GeometryReader { geo in
            ZStack {
                palette.surfaceVariant.opacity(palette.dark ? 0.6 : 1)
                if model.loading {
                    ProgressView()
                } else if model.failed || model.pageSizes.isEmpty {
                    failure
                } else {
                    pages(geo.size)
                }
            }
            .clipped()
        }
    }

    private var failure: some View {
        VStack(spacing: 12) {
            Text(L(model.protectedDoc ? "editor_open_protected" : "editor_open_failed"))
                .font(.body)
                .multilineTextAlignment(.center)
            if model.protectedDoc {
                Button(L("editor_password_enter")) {
                    model.passwordWrong = false
                    model.passwordInput = ""
                    model.askPassword = true
                }
                .buttonStyle(.borderedProminent)
            }
        }
        .padding(24)
    }

    private func pages(_ viewport: CGSize) -> some View {
        let baseW = max(viewport.width - 16, 60) * model.zoom
        let viewing = model.mode == .view
        return ScrollView([.vertical, .horizontal]) {
            LazyVStack(spacing: 10) {
                ForEach(0..<model.pageSizes.count, id: \.self) { i in
                    let s = model.pageSizes[i]
                    let aspect = min(max(s.width / max(s.height, 1), 0.2), 5)
                    EditorPageView(model: model, index: i)
                        .frame(width: baseW, height: baseW / aspect)
                        .shadow(color: .black.opacity(0.12), radius: 2, y: 1)
                        .id(i)
                }
            }
            .scrollTargetLayout()
            .padding(.vertical, 10)
            .padding(.horizontal, 8)
            .frame(minWidth: viewport.width)
        }
        .scrollPosition(id: $model.scrolledPage, anchor: .top)
        // Im Ansehen-Modus wird geblättert und gezoomt, mit aktivem Werkzeug
        // gezeichnet — sonst kämpfen Bildlauf und Strich um dieselbe Geste
        .scrollDisabled(!viewing || model.hasSelection)
        .simultaneousGesture(
            MagnifyGesture()
                .onChanged { v in
                    guard model.mode == .view else { return }
                    model.zoom = min(max(pinchBase * v.magnification, 1), 4)
                }
                .onEnded { _ in pinchBase = model.zoom }
        )
        .onChange(of: model.scrolledPage) { _, _ in model.trimCache() }
    }
}

/// Eine Seite mit ihren Aufsätzen (Port von PageItem/PageCanvas).
struct EditorPageView: View {
    @Bindable var model: DocumentEditorModel
    let index: Int

    @State private var live: Mark?
    @State private var moveDelta: CGPoint = .zero
    /// 0 = offen, 1 = Auswahl verschieben, 2 = zeichnen, 3 = ignorieren
    @State private var dragKind = 0

    private var pageSize: CGSize {
        index < model.pageSizes.count ? model.pageSizes[index] : CGSize(width: 595, height: 842)
    }

    var body: some View {
        GeometryReader { geo in
            let scale = geo.size.width / max(pageSize.width, 1)
            ZStack {
                (model.nightMode ? Color(white: 0.12) : Color.white)
                if let img = model.pageImages[index] {
                    pageImage(img)
                        .frame(width: geo.size.width, height: geo.size.height)
                    marksCanvas(scale: scale)
                        .allowsHitTesting(false)
                } else {
                    ProgressView()
                }
            }
            .contentShape(Rectangle())
            .gesture(dragGesture(scale: scale), including: dragEnabled ? .all : .subviews)
            .simultaneousGesture(
                SpatialTapGesture(coordinateSpace: .local).onEnded { v in
                    model.tap(at: CGPoint(x: v.location.x / scale, y: v.location.y / scale), page: index)
                }
            )
        }
        // Nicht nur beim ersten Aufbau: Nach Seitenoperationen wird der
        // Bildspeicher geleert — eine sichtbare Seite fordert dann neu an
        .task(id: "\(index)-\(model.generation)-\(model.pageImages[index] == nil)") {
            if model.pageImages[index] == nil { model.ensurePage(index) }
        }
    }

    private var dragEnabled: Bool {
        model.mode.isDragTool || model.selectionIndex(onPage: index) != nil
    }

    @ViewBuilder
    private func pageImage(_ img: UIImage) -> some View {
        // Nachtmodus: nur die ANZEIGE invertieren — gespeichert wird das Original
        if model.nightMode {
            Image(uiImage: img).resizable().interpolation(.high).colorInvert()
        } else {
            Image(uiImage: img).resizable().interpolation(.high)
        }
    }

    private func marksCanvas(scale: CGFloat) -> some View {
        var list = model.marksFor(index)
        let selIdx = model.selectionIndex(onPage: index)
        if let si = selIdx, si < list.count, moveDelta != .zero {
            list[si] = list[si].moved(by: moveDelta)
        }
        if let live { list.append(live) }
        let sig = model.signature
        let ini = model.initials
        let sigAspect = model.sigAspect
        let iniAspect = model.iniAspect
        let drawn = list
        return Canvas { ctx, _ in
            ctx.withCGContext { cg in
                let list = drawn
                MarkRenderer.draw(list, in: cg, signature: sig, initials: ini, scale: scale)
                if let si = selIdx, si < list.count {
                    // Auswahlrahmen: gestrichelt um das angetippte Element
                    let b = list[si].bounds(sigAspect: sigAspect, iniAspect: iniAspect)
                    let r = CGRect(x: b.minX * scale - 6, y: b.minY * scale - 6,
                                   width: b.width * scale + 12, height: b.height * scale + 12)
                    cg.setStrokeColor(InkColor.blue.uiColor.cgColor)
                    cg.setLineWidth(2)
                    cg.setLineDash(phase: 0, lengths: [8, 6])
                    cg.stroke(r)
                }
            }
        }
    }

    private func extend(_ m: Mark, _ p: CGPoint) -> Mark {
        switch m {
        case let .stroke(points, width, color, highlight):
            return .stroke(points: points + [p], width: width, color: color, highlight: highlight)
        case .redact(let a, _):
            return .redact(a: a, b: p)
        case let .shape(kind, a, _, color, width):
            return .shape(kind: kind, a: a, b: p, color: color, width: width)
        default:
            return m
        }
    }

    private func dragGesture(scale: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 2, coordinateSpace: .local)
            .onChanged { v in
                let p = CGPoint(x: v.location.x / scale, y: v.location.y / scale)
                if dragKind == 0 {
                    let start = CGPoint(x: v.startLocation.x / scale, y: v.startLocation.y / scale)
                    let size = pageSize
                    // Ziehen am ausgewählten Element verschiebt es — in jedem Modus
                    if let si = model.selectionIndex(onPage: index) {
                        let tol = size.width / 40
                        let b = model.marksFor(index)[si]
                            .bounds(sigAspect: model.sigAspect, iniAspect: model.iniAspect)
                            .insetBy(dx: -tol, dy: -tol)
                        if b.contains(start) { dragKind = 1 }
                    }
                    if dragKind == 0 {
                        let stroke = size.width / 250
                        switch model.mode {
                        case .draw, .highlight:
                            let hl = model.mode == .highlight
                            live = .stroke(points: [start], width: hl ? size.width / 45 : stroke * model.widthFactor,
                                           color: hl ? MarkRenderer.highlightColor : model.inkColor.uiColor,
                                           highlight: hl)
                            dragKind = 2
                        case .redact:
                            live = .redact(a: start, b: start)
                            dragKind = 2
                        case .shape:
                            live = .shape(kind: model.shapeKind, a: start, b: start, color: model.inkColor.uiColor,
                                          width: stroke * 1.2 * model.widthFactor)
                            dragKind = 2
                        default:
                            dragKind = 3
                        }
                    }
                }
                switch dragKind {
                case 1:
                    moveDelta = CGPoint(x: v.translation.width / scale, y: v.translation.height / scale)
                case 2:
                    if let m = live { live = extend(m, p) }
                default:
                    break
                }
            }
            .onEnded { _ in
                if dragKind == 1 {
                    model.moveSelected(by: moveDelta)
                } else if dragKind == 2, let m = live {
                    model.addMark(m, page: index, select: false)
                }
                live = nil
                moveDelta = .zero
                dragKind = 0
            }
    }
}

// MARK: - Werkzeugleiste

/// Chip im Stil von Material `FilterChip`.
struct EditorChip: View {
    let title: String
    var icon: String? = nil
    var swatch: Color? = nil
    let selected: Bool
    let action: () -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let icon {
                    Image(systemName: icon).font(.footnote)
                }
                if let swatch {
                    Circle().fill(swatch).frame(width: 12, height: 12)
                }
                Text(title).lineLimit(1)
            }
            .font(.subheadline)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurface)
            .background(Capsule().fill(selected ? palette.secondaryContainer : Color.clear))
            .overlay(Capsule().stroke(selected ? Color.clear : palette.outlineVariant))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

/// Werkzeuge, Unterwerkzeuge, Seitenzahl, Auswahl-Aktionen, Hauptknopf.
struct EditorToolPanel: View {
    @Bindable var model: DocumentEditorModel
    let wide: Bool
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            tools
            if model.mode.hasColor { colorRow }
            if model.mode == .sign { signSlotRow }
            if model.pageCount > 1 { pageRow }
            if let hint = hintKey {
                Text(L(hint))
                    .font(.footnote)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if model.mode == .shape { shapeRow }
            if model.mode == .image {
                EditorChip(title: L("editor_image_pick"), icon: "photo", selected: false) {
                    model.showImagePicker = true
                }
            }
            if model.mode == .sign {
                Button(L(model.slotImage(model.signSlot) == nil ? "editor_create_signature" : "editor_new_signature")) {
                    model.sheet = .signature(model.signSlot)
                }
                .font(.subheadline)
            }
            if model.hasSelection { selectionRow }
            mainButton
        }
    }

    private static let widths: [(CGFloat, String)] = [
        (0.6, "editor_width_thin"), (1, "editor_width_medium"), (1.8, "editor_width_thick")
    ]
    private static let shapes: [(String, String)] = [
        ("rect", "editor_shape_rect"), ("oval", "editor_shape_oval"),
        ("arrow", "editor_shape_arrow"), ("line", "editor_shape_line")
    ]

    private var hintKey: String? {
        if model.mode == .sign && model.slotImage(model.signSlot) == nil { return "editor_hint_no_signature" }
        return model.mode.hintKey
    }

    private var toolChips: some View {
        ForEach(EditorTool.allCases) { tool in
            EditorChip(title: L(tool.labelKey), icon: tool.icon, selected: model.mode == tool) {
                model.selectTool(tool)
            }
        }
    }

    @ViewBuilder
    private var tools: some View {
        if wide {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 8, alignment: .leading)],
                      alignment: .leading, spacing: 8) {
                toolChips
            }
        } else {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) { toolChips }
            }
        }
    }

    private var colorRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(InkColor.allCases) { c in
                    EditorChip(title: L(c.labelKey), swatch: Color(uiColor: c.uiColor),
                               selected: model.inkColor == c) {
                        model.inkColor = c
                    }
                }
                if model.mode == .draw {
                    ForEach(0..<Self.widths.count, id: \.self) { i in
                        let item = Self.widths[i]
                        EditorChip(title: L(item.1), selected: model.widthFactor == item.0) {
                            model.widthFactor = item.0
                        }
                    }
                }
            }
        }
    }

    private var signSlotRow: some View {
        HStack(spacing: 8) {
            EditorChip(title: L("editor_sign_full"), selected: model.signSlot == 0) {
                model.selectSignSlot(0)
            }
            EditorChip(title: L("editor_sign_initials"), selected: model.signSlot == 1) {
                model.selectSignSlot(1)
            }
        }
    }

    private var shapeRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(0..<Self.shapes.count, id: \.self) { i in
                    let item = Self.shapes[i]
                    EditorChip(title: L(item.1), selected: model.shapeKind == item.0) {
                        model.shapeKind = item.0
                    }
                }
            }
        }
    }

    private var pageRow: some View {
        HStack {
            Spacer()
            Button { model.scrollTo(model.pageIndex - 1) } label: {
                Image(systemName: "chevron.left")
            }
            .disabled(model.pageIndex <= 0)
            .accessibilityLabel(L("editor_prev_page"))
            Text(L("editor_page_of", model.pageIndex + 1, model.pageCount))
                .font(.subheadline)
                .lineLimit(1)
                .fixedSize()
                .padding(.horizontal, 12)
            Button { model.scrollTo(model.pageIndex + 1) } label: {
                Image(systemName: "chevron.right")
            }
            .disabled(model.pageIndex >= model.pageCount - 1)
            .accessibilityLabel(L("editor_next_page"))
            Spacer()
        }
        .padding(.vertical, 2)
    }

    private var selectionRow: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                Text(L("editor_selection"))
                    .font(.footnote)
                    .foregroundStyle(palette.onSurfaceVariant)
                Button("−") { model.resizeSelected(0.8) }
                    .buttonStyle(.bordered)
                Button("+") { model.resizeSelected(1.25) }
                    .buttonStyle(.bordered)
                Button(L("editor_delete"), role: .destructive) { model.deleteSelected() }
                    .foregroundStyle(palette.error)
                Button(L("editor_deselect")) { model.selected = nil }
            }
        }
    }

    private var mainButton: some View {
        let enabled = !model.saving && !model.loading && !model.failed && !model.busyOp
        return Button { model.mainAction() } label: {
            HStack(spacing: 8) {
                if model.saving {
                    ProgressView().tint(palette.onPrimary)
                } else {
                    Image(systemName: model.mainActionIsMail ? "paperplane.fill" : "square.and.arrow.down")
                }
                Text(model.mainActionLabel)
                    .fontWeight(.semibold)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 12)
            .foregroundStyle(palette.onPrimary)
            .background(RoundedRectangle(cornerRadius: 22).fill(palette.primary.opacity(enabled ? 1 : 0.4)))
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .padding(.top, 2)
    }
}
