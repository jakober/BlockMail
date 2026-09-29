import SwiftUI
import PDFKit
import UIKit

/// Werkzeuge des Editors (Reihenfolge = Häufigkeit, wie Android).
enum EditorTool: String, CaseIterable, Identifiable {
    case view, sign, draw, highlight, check, cross, date, text, image, shape, eraser, redact

    var id: String { rawValue }

    var labelKey: String { "editor_mode_\(rawValue)" }

    var icon: String {
        switch self {
        case .view: return "hand.raised"
        case .sign: return "signature"
        case .draw: return "pencil.tip"
        case .highlight: return "highlighter"
        case .check: return "checkmark"
        case .cross: return "xmark"
        case .date: return "calendar"
        case .text: return "textformat"
        case .image: return "photo"
        case .shape: return "square.on.circle"
        case .eraser: return "eraser"
        case .redact: return "eye.slash"
        }
    }

    /// Werkzeuge, die gezogen werden (Stift, Marker, Schwärzen, Form).
    var isDragTool: Bool { self == .draw || self == .highlight || self == .redact || self == .shape }

    /// Werkzeuge mit Farbwahl.
    var hasColor: Bool { [.draw, .check, .cross, .date, .text, .shape].contains(self) }

    var hintKey: String? {
        switch self {
        case .view: return "editor_hint_view"
        case .sign: return "editor_hint_sign"
        case .highlight: return "editor_hint_highlight"
        case .eraser: return "editor_hint_eraser"
        case .redact: return "editor_hint_redact"
        case .check, .cross: return "editor_hint_stamp"
        case .date: return "editor_hint_date"
        case .image: return "editor_hint_image"
        case .text: return "editor_hint_text"
        case .shape: return "editor_hint_shape"
        case .draw: return nil
        }
    }
}

enum InkColor: Int, CaseIterable, Identifiable {
    case black, blue, red
    var id: Int { rawValue }
    var uiColor: UIColor {
        switch self {
        case .black: return .black
        case .blue: return UIColor(red: 0x15 / 255.0, green: 0x65 / 255.0, blue: 0xC0 / 255.0, alpha: 1)
        case .red: return UIColor(red: 0xC6 / 255.0, green: 0x28 / 255.0, blue: 0x28 / 255.0, alpha: 1)
        }
    }
    var labelKey: String {
        switch self {
        case .black: return "editor_color_black"
        case .blue: return "editor_color_blue"
        case .red: return "editor_color_red"
        }
    }
}

/// Ausgewählter Aufsatz: stabile Seitenkennung + Index in deren Liste.
struct MarkSelection: Equatable {
    var page: Int
    var index: Int
}

/// Ausgabewege (Port der Aktionsschlüssel „send_mail“, „save_as“ …).
enum EditorAction { case sendMail, saveAs, overwrite, share, print }

enum InsertSource {
    case blank
    case pdf(PDFDocument)
}

enum EditorSheet: Identifiable {
    case signature(Int)
    case search
    case ai
    case outline([DocumentPDF.OutlineEntry])
    case form(DocumentPDF.Form)
    case pages
    case share(URL)
    case export(URL)
    case scan
    case insertPosition(InsertSource)

    var id: String {
        switch self {
        case .signature(let s): return "signature\(s)"
        case .search: return "search"
        case .ai: return "ai"
        case .outline: return "outline"
        case .form: return "form"
        case .pages: return "pages"
        case .share(let u): return "share" + u.path
        case .export(let u): return "export" + u.path
        case .scan: return "scan"
        case .insertPosition: return "insert"
        }
    }
}

struct TextAsk: Equatable {
    var page: Int
    var point: CGPoint
}

struct AIMessage: Identifiable {
    let id = UUID()
    let user: Bool
    let text: String
}

/// Zustand und Logik des Dokument-Editors (Port von AttachmentEditorScreen).
@MainActor
@Observable
final class DocumentEditorModel {

    let source: DocumentEditing.Source?
    let isPdf: Bool
    let snackbar = SnackbarState()

    // MARK: Dokument

    var doc: PDFDocument?
    var baseImage: UIImage?
    var workURL: URL?
    /// Passwort, mit dem das Dokument aufgeschlossen wurde.
    var password: String?
    var loading = true
    var failed = false
    var ready = false
    var protectedDoc = false
    var askPassword = false
    var passwordWrong = false
    var passwordInput = ""

    /// Stabile Seitenkennungen in aktueller Reihenfolge (siehe `PageId`).
    var pageIds: [Int] = []
    /// Maße der angezeigten Seiten in Punkten (Bild: Pixel).
    var pageSizes: [CGSize] = []
    var pageImages: [Int: UIImage] = [:]
    var thumbs: [Int: UIImage] = [:]
    /// Zählt bei jeder Seitenoperation hoch: Renderaufträge für den alten
    /// Stand dürfen ihr Ergebnis nicht mehr ablegen.
    var generation = 0
    @ObservationIgnored private var rendering = Set<Int>()
    @ObservationIgnored private var renderingThumbs = Set<Int>()
    @ObservationIgnored private var nextPageId = 0
    @ObservationIgnored private var started = false

    // MARK: Aufsätze

    var marks: [Int: [Mark]] = [:]
    /// Verlauf über ALLE Seiten: wo zuletzt etwas entstand.
    var history: [Int] = []
    var selected: MarkSelection?

    var signature: UIImage?
    var initials: UIImage?
    var signSlot = 0
    var mode: EditorTool = .view
    var placeImage: UIImage?
    var shapeKind = "rect"
    var inkColor: InkColor = .black
    var widthFactor: CGFloat = 1
    var nightMode = false

    // MARK: Anzeige

    var zoom: CGFloat = 1
    var scrolledPage: Int? = 0

    // MARK: Abläufe & Dialoge

    var saving = false
    var busyOp = false
    var redactAccepted = false
    var redactWarnAction: EditorAction?

    var sheet: EditorSheet?
    var textAsk: TextAsk?
    var textValue = ""
    var extractAsk = false
    var extractFrom = ""
    var extractTo = ""
    var protectAsk = false
    var protectPassword = ""
    var compressAsk = false
    var aiReviseAsk = false
    var aiReviseText = ""
    var showImagePicker = false
    var showAppendImages = false
    var showPdfImporter = false

    var searchQuery = ""
    var searchBusy = false
    var searchResults: [DocumentPDF.SearchHit]?

    var aiInput = ""
    var aiBusy = false
    var aiMessages: [AIMessage] = []

    init(source: DocumentEditing.Source?) {
        self.source = source
        if let s = source {
            isPdf = DocumentEditing.isPdf(mime: s.mime, name: s.name)
        } else {
            isPdf = true
        }
    }

    // MARK: Abgeleitete Werte

    var pageIndex: Int {
        let last = max(pageSizes.count - 1, 0)
        return min(max(scrolledPage ?? 0, 0), last)
    }

    var pageCount: Int { pageSizes.count }

    var aiAvailable: Bool { EditorAI.isAvailable && isPdf && ready }

    var canAIRevise: Bool { isPdf && ready && (source?.aiDocument ?? false) }

    func idFor(_ index: Int) -> Int { index >= 0 && index < pageIds.count ? pageIds[index] : index }

    func marksFor(_ index: Int) -> [Mark] { marks[idFor(index)] ?? [] }

    func slotImage(_ slot: Int) -> UIImage? { slot == 1 ? initials : signature }

    var sigAspect: CGFloat? { signature.map { MarkRenderer.aspect($0) } }
    var iniAspect: CGFloat? { initials.map { MarkRenderer.aspect($0) } }

    func selectionIndex(onPage index: Int) -> Int? {
        guard let sel = selected, sel.page == idFor(index) else { return nil }
        return sel.index < marksFor(index).count ? sel.index : nil
    }

    var hasSelection: Bool {
        guard let sel = selected, let list = marks[sel.page] else { return false }
        return sel.index < list.count
    }

    var hasRedact: Bool { marks.values.contains { $0.contains { $0.isRedact } } }

    // MARK: Öffnen

    func start() async {
        guard !started else { return }
        started = true
        DocumentEditing.cleanupOldFiles()
        signature = SignatureStore.load(0)
        initials = SignatureStore.load(1)
        guard let src = source else {
            loading = false
            failed = true
            return
        }
        let url: URL? = await Task.detached(priority: .userInitiated) {
            try? DocumentEditing.materialize(src)
        }.value
        guard let url else {
            loading = false
            failed = true
            return
        }
        workURL = url
        if isPdf {
            guard let d = PDFDocument(url: url) else {
                loading = false
                failed = true
                return
            }
            // Nur gegen Bearbeiten geschützt (typisch: Kontoauszüge)? Dann
            // reicht ein leeres Passwort — erst danach fragen.
            if d.isLocked && !d.unlock(withPassword: "") {
                doc = d
                protectedDoc = true
                askPassword = true
                loading = false
                failed = true
                return
            }
            adopt(d, ids: nil)
        } else {
            let img: UIImage? = await Task.detached(priority: .userInitiated) {
                UIImage(contentsOfFile: url.path).map { DocumentPDF.normalized($0, maxPx: 2400) }
            }.value
            guard let img else {
                loading = false
                failed = true
                return
            }
            baseImage = img
            pageIds = [freshId()]
            pageSizes = [CGSize(width: img.size.width * img.scale, height: img.size.height * img.scale)]
            pageImages = [0: img]
            loading = false
            failed = false
            ready = true
        }
    }

    func submitPassword() {
        guard let d = doc else { return }
        let pw = passwordInput
        if d.unlock(withPassword: pw) {
            password = pw
            askPassword = false
            passwordWrong = false
            protectedDoc = false
            passwordInput = ""
            adopt(d, ids: nil)
        } else {
            passwordWrong = true
            passwordInput = ""
            // Der Hinweis schließt sich beim Tippen — kurz danach erneut zeigen
            Task {
                try? await Task.sleep(nanoseconds: 400_000_000)
                askPassword = true
            }
        }
    }

    private func freshId() -> Int {
        let id = nextPageId
        nextPageId += 1
        return id
    }

    private func adopt(_ d: PDFDocument, ids: [Int]?) {
        doc = d
        if let ids, ids.count == d.pageCount {
            pageIds = ids
        } else {
            pageIds = (0..<d.pageCount).map { _ in freshId() }
        }
        refreshPages()
        loading = false
        failed = d.pageCount == 0
        ready = !failed
    }

    /// Nach Seitenoperationen: Maße neu holen, Bildspeicher leeren.
    private func refreshPages() {
        generation += 1
        pageImages = [:]
        thumbs = [:]
        rendering = []
        renderingThumbs = []
        guard let doc else { pageSizes = []; return }
        pageSizes = (0..<doc.pageCount).map { i in
            doc.page(at: i).map { DocumentPDF.displaySize($0) } ?? CGSize(width: 595, height: 842)
        }
        if let p = scrolledPage, p >= pageSizes.count { scrolledPage = max(pageSizes.count - 1, 0) }
    }

    // MARK: Rendern

    func ensurePage(_ index: Int) {
        guard isPdf, pageImages[index] == nil, !rendering.contains(index),
              let page = doc?.page(at: index) else { return }
        rendering.insert(index)
        let gen = generation
        Task { [weak self] in
            let img = await Self.render(page, maxPx: 1600)
            guard let self else { return }
            self.rendering.remove(index)
            guard gen == self.generation else { return }
            self.pageImages[index] = img
            self.trimCache()
        }
    }

    func ensureThumb(_ index: Int) {
        guard isPdf, thumbs[index] == nil, !renderingThumbs.contains(index),
              let page = doc?.page(at: index) else { return }
        renderingThumbs.insert(index)
        let gen = generation
        Task { [weak self] in
            let img = await Self.render(page, maxPx: 300)
            guard let self else { return }
            self.renderingThumbs.remove(index)
            guard gen == self.generation else { return }
            self.thumbs[index] = img
        }
    }

    /// Eine Seite nach der anderen rendern: PDFKit ist nicht für
    /// gleichzeitige Zugriffe auf dasselbe Dokument gebaut.
    nonisolated private static let renderQueue = DispatchQueue(label: "blockmail.editor.render", qos: .userInitiated)

    nonisolated private static func render(_ page: PDFPage, maxPx: CGFloat) async -> UIImage {
        await withCheckedContinuation { (cont: CheckedContinuation<UIImage, Never>) in
            renderQueue.async {
                cont.resume(returning: DocumentPDF.renderImage(page, maxPx: maxPx))
            }
        }
    }

    /// Speicher begrenzen: nur Seiten in Sichtweite behalten.
    func trimCache() {
        guard isPdf, pageSizes.count > 4 else { return }
        let keep = (pageIndex - 2)...(pageIndex + 3)
        for key in pageImages.keys where !keep.contains(key) {
            pageImages.removeValue(forKey: key)
        }
    }

    func scrollTo(_ page: Int) {
        guard !pageSizes.isEmpty else { return }
        let p = min(max(page, 0), pageSizes.count - 1)
        withAnimation(.easeInOut(duration: 0.25)) { scrolledPage = p }
    }

    // MARK: Aufsätze bearbeiten

    func addMark(_ m: Mark, page index: Int, select: Bool) {
        let id = idFor(index)
        var list = marks[id] ?? []
        list.append(m)
        marks[id] = list
        history.append(id)
        if select { selected = MarkSelection(page: id, index: list.count - 1) }
    }

    func undo() {
        guard let id = history.popLast() else { return }
        if var list = marks[id], !list.isEmpty {
            list.removeLast()
            marks[id] = list
        }
        selected = nil
    }

    private func dropHistoryEntry(_ id: Int) {
        if let li = history.lastIndex(of: id) { history.remove(at: li) }
    }

    func moveSelected(by d: CGPoint) {
        guard let sel = selected, var list = marks[sel.page], sel.index < list.count else { return }
        list[sel.index] = list[sel.index].moved(by: d)
        marks[sel.page] = list
    }

    func resizeSelected(_ f: CGFloat) {
        guard let sel = selected, var list = marks[sel.page], sel.index < list.count else { return }
        list[sel.index] = list[sel.index].scaled(f)
        marks[sel.page] = list
    }

    func deleteSelected() {
        guard let sel = selected, var list = marks[sel.page], sel.index < list.count else {
            selected = nil
            return
        }
        list.remove(at: sel.index)
        marks[sel.page] = list
        dropHistoryEntry(sel.page)
        selected = nil
    }

    static func todayString() -> String {
        let f = DateFormatter()
        f.dateFormat = "dd.MM.yyyy"
        return f.string(from: Date())
    }

    /// Antippen einer Seite (Port des Tipp-Blocks aus PageCanvas).
    func tap(at p: CGPoint, page index: Int) {
        guard index < pageSizes.count else { return }
        let size = pageSizes[index]
        let tol = size.width / 40
        let id = idFor(index)
        let list = marksFor(index)
        let hit = MarkRenderer.hit(list, p, tolerance: tol)
        switch mode {
        case .eraser:
            if let hit {
                var l = list
                l.remove(at: hit)
                marks[id] = l
                dropHistoryEntry(id)
                selected = nil
            }
            return
        case .view:
            selected = hit.map { MarkSelection(page: id, index: $0) }
            return
        default:
            break
        }
        // Vorhandenes hat Vorrang: Ein Tipp auf ein Element wählt es aus
        if let hit {
            selected = MarkSelection(page: id, index: hit)
            return
        }
        let color = inkColor.uiColor
        switch mode {
        case .sign:
            guard slotImage(signSlot) != nil else {
                sheet = .signature(signSlot)
                return
            }
            let w = signSlot == 1 ? size.width / 6 : size.width / 3
            addMark(.sign(center: p, width: w, slot: signSlot), page: index, select: true)
        case .check, .cross:
            addMark(.stamp(check: mode == .check, center: p, size: size.width / 14, color: color), page: index, select: true)
        case .date:
            addMark(.label(text: Self.todayString(), center: p, size: size.width / 32, color: color), page: index, select: true)
        case .image:
            guard let img = placeImage else {
                showImagePicker = true
                return
            }
            addMark(.image(center: p, width: size.width / 3, image: img), page: index, select: true)
        case .text:
            textValue = ""
            textAsk = TextAsk(page: index, point: p)
        default:
            // Stift, Marker, Schwärzen: Tipp auf freie Fläche hebt nur die Auswahl auf
            selected = nil
        }
    }

    func confirmText(_ ask: TextAsk?) {
        guard let ask else { return }
        let t = textValue.trimmingCharacters(in: .whitespacesAndNewlines)
        textAsk = nil
        guard !t.isEmpty, ask.page < pageSizes.count else { return }
        let base = pageSizes[ask.page].width
        addMark(.label(text: t, center: ask.point, size: base / 28, color: inkColor.uiColor), page: ask.page, select: true)
    }

    /// Werkzeug wählen (Port des Chip-onClick).
    func selectTool(_ tool: EditorTool) {
        if tool == .sign && slotImage(signSlot) == nil {
            mode = .sign
            sheet = .signature(signSlot)
        } else if tool == .image && placeImage == nil {
            showImagePicker = true
        } else {
            mode = tool
        }
    }

    func selectSignSlot(_ slot: Int) {
        signSlot = slot
        if slotImage(slot) == nil { sheet = .signature(slot) }
    }

    func saveSignature(_ img: UIImage, slot: Int) {
        SignatureStore.save(img, slot: slot)
        if slot == 1 { initials = img } else { signature = img }
        sheet = nil
        mode = .sign
        signSlot = slot
    }

    func loadPlaceImage(_ data: Data?) {
        guard let data, let raw = UIImage(data: data) else {
            snackbar.show(L("editor_image_failed"))
            return
        }
        placeImage = DocumentPDF.normalized(raw, maxPx: 1600)
        mode = .image
    }

    // MARK: Seitenoperationen

    /// Dreht die Aufsätze einer Seite mit (Breite/Höhe tauschen).
    private func rotateMarks(_ index: Int, cw: Bool) {
        guard index < pageSizes.count else { return }
        let w = pageSizes[index].width, h = pageSizes[index].height
        let id = idFor(index)
        guard let list = marks[id] else { return }
        marks[id] = list.map { m in
            m.mapped { p in cw ? CGPoint(x: h - p.y, y: p.x) : CGPoint(x: p.y, y: w - p.x) }
        }
    }

    func rotatePage(_ index: Int, cw: Bool) {
        guard isPdf, !busyOp, let doc, let page = doc.page(at: index) else { return }
        rotateMarks(index, cw: cw)
        page.rotation = ((page.rotation + (cw ? 90 : -90)) % 360 + 360) % 360
        refreshPages()
    }

    func deletePage(_ index: Int) {
        guard isPdf, !busyOp, let doc else { return }
        guard pageSizes.count > 1 else {
            snackbar.show(L("editor_delete_last_page"))
            return
        }
        guard index >= 0, index < doc.pageCount else { return }
        let id = idFor(index)
        marks[id] = nil
        history.removeAll { $0 == id }
        if selected?.page == id { selected = nil }
        doc.removePage(at: index)
        pageIds.remove(at: min(index, pageIds.count - 1))
        refreshPages()
    }

    func movePage(_ from: Int, _ to: Int) {
        guard isPdf, !busyOp, let doc, from != to,
              from >= 0, from < pageIds.count, to >= 0, to < pageIds.count else { return }
        guard DocumentPDF.move(in: doc, from: from, to: to) else {
            snackbar.show(L("editor_page_op_failed"))
            return
        }
        let id = pageIds.remove(at: from)
        pageIds.insert(id, at: to)
        refreshPages()
    }

    private func insertIds(_ count: Int, at: Int) {
        let pos = min(max(at, 0), pageIds.count)
        pageIds.insert(contentsOf: (0..<count).map { _ in freshId() }, at: pos)
    }

    func insertBlank(at: Int) {
        guard isPdf, !busyOp, let doc else { return }
        let pos = min(max(at, 0), doc.pageCount)
        guard DocumentPDF.insertBlank(into: doc, at: pos) else {
            snackbar.show(L("editor_page_op_failed"))
            return
        }
        insertIds(1, at: pos)
        refreshPages()
    }

    func insertPdf(_ other: PDFDocument, at: Int) {
        guard isPdf, !busyOp, let doc else { return }
        let pos = min(max(at, 0), doc.pageCount)
        let added = DocumentPDF.insert(other, into: doc, at: pos)
        guard added > 0 else {
            snackbar.show(L("editor_page_op_failed"))
            return
        }
        insertIds(added, at: pos)
        refreshPages()
    }

    /// PDF-Datei aus der Dateiauswahl zum Einfügen vorbereiten.
    func pickedPdf(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url), let other = PDFDocument(data: data) else {
            snackbar.show(L("editor_page_op_failed"))
            return
        }
        if other.isLocked && !other.unlock(withPassword: "") {
            snackbar.show(L("editor_open_protected"))
            return
        }
        sheet = .insertPosition(.pdf(other))
    }

    /// Fotos/Scans als neue Seiten ans Ende hängen.
    func appendImages(_ images: [UIImage]) {
        guard isPdf, !images.isEmpty else { return }
        busyOp = true
        Task {
            let data: Data? = await Task.detached(priority: .userInitiated) {
                DocumentPDF.imagesData(images)
            }.value
            busyOp = false
            guard let data, let other = PDFDocument(data: data) else {
                snackbar.show(L("editor_page_op_failed"))
                return
            }
            insertPdf(other, at: pageIds.count)
            scrollTo(pageSizes.count - 1)
        }
    }

    func appendImageData(_ datas: [Data]) {
        let imgs = datas.compactMap { UIImage(data: $0) }
        if imgs.isEmpty {
            snackbar.show(L("editor_image_failed"))
        } else {
            appendImages(imgs)
        }
    }

    func applyForm(texts: [String: String], checks: [String: Bool]) {
        guard let doc else { return }
        _ = DocumentPDF.fillForm(doc, texts: texts, checks: checks)
        refreshPages()
    }

    func openForm() {
        guard let doc else { return }
        let form = DocumentPDF.readForm(doc)
        if form.isEmpty {
            snackbar.show(L("editor_form_none"))
        } else {
            sheet = .form(form)
        }
    }

    func openOutline() {
        guard let doc else { return }
        let entries = DocumentPDF.outline(doc)
        if entries.isEmpty {
            snackbar.show(L("editor_outline_none"))
        } else {
            sheet = .outline(entries)
        }
    }

    func runSearch() {
        guard let doc, !searchBusy else { return }
        let q = searchQuery
        searchBusy = true
        Task {
            let hits = await Task.detached(priority: .userInitiated) {
                DocumentPDF.search(doc, query: q)
            }.value
            searchResults = hits
            searchBusy = false
        }
    }

    // MARK: KI-Überarbeitung (nur per KI erstellte Dokumente)

    /// Die KI setzt das Dokument komplett neu — Seitenzahl kann sich ändern,
    /// deshalb frische Kennungen und alle Aufsätze weg.
    func aiRevise() {
        let changes = aiReviseText.trimmingCharacters(in: .whitespacesAndNewlines)
        aiReviseText = ""
        guard !changes.isEmpty, !busyOp else { return }
        snackbar.show(L("editor_ai_revise_busy"))
        busyOp = true
        Task {
            do {
                let prefs = Prefs.shared
                let (t, b) = try await ClaudeClient.reviseDocument(title: prefs.aiPdfTitle, body: prefs.aiPdfBody, changes: changes)
                let data: Data? = await Task.detached(priority: .userInitiated) {
                    DocumentPDF.textDocument(title: t, body: b)
                }.value
                guard let data, let nd = PDFDocument(data: data) else {
                    throw DocumentPDF.OpError(message: L("editor_page_op_failed"))
                }
                prefs.aiPdfTitle = t
                prefs.aiPdfBody = b
                marks = [:]
                history = []
                selected = nil
                password = nil
                busyOp = false
                adopt(nd, ids: nil)
                scrolledPage = 0
            } catch {
                busyOp = false
                let msg = error.localizedDescription
                snackbar.show(msg.isEmpty ? L("editor_page_op_failed") : msg)
            }
        }
    }

    // MARK: Speichern & Ausgabe

    /// Dateiname des Ergebnisses.
    func outputName() -> String {
        guard let src = source else { return "Dokument.pdf" }
        if isPdf { return DocumentEditing.safeFileName(DocumentEditing.signedName(src.name)) }
        let lower = src.name.lowercased()
        if lower.hasSuffix(".png") || lower.hasSuffix(".jpg") || lower.hasSuffix(".jpeg") {
            return DocumentEditing.safeFileName(DocumentEditing.signedName(src.name))
        }
        let base = (src.name as NSString).deletingPathExtension
        return DocumentEditing.safeFileName(DocumentEditing.signedName((base.isEmpty ? "Bild" : base) + ".jpg"))
    }

    private var outputIsPng: Bool { !isPdf && (source?.name.lowercased().hasSuffix(".png") ?? false) }

    /// Basisname der Quelle ohne Endung (für abgeleitete Dateinamen).
    private func baseName() -> String {
        let n = (source?.name ?? "Dokument") as NSString
        let b = n.deletingPathExtension
        return b.isEmpty ? "Dokument" : b
    }

    /// Aufsätze nach Seitenindex (Abzug für den Hintergrund).
    private func snapshotByIndex() -> [Int: [Mark]] {
        var out: [Int: [Mark]] = [:]
        for (id, list) in marks where !list.isEmpty {
            if let idx = pageIds.firstIndex(of: id) { out[idx] = list }
        }
        return out
    }

    /// Baut das Ergebnis nach `url`; wirft bei Fehlern.
    func produce(to url: URL) async throws {
        let snapshot = snapshotByIndex()
        let sig = signature
        let ini = initials
        if isPdf {
            guard let doc else { throw DocumentPDF.OpError(message: "Dokument nicht offen") }
            let pw = password
            try await Task.detached(priority: .userInitiated) {
                try DocumentPDF.writeOutput(doc: doc, password: pw, marks: snapshot,
                                            signature: sig, initials: ini, to: url)
            }.value
        } else {
            guard let base = baseImage, let imgSize = pageSizes.first else {
                throw DocumentPDF.OpError(message: "Bild nicht lesbar")
            }
            let png = outputIsPng
            let list = snapshot[0] ?? []
            let data: Data? = await Task.detached(priority: .userInitiated) { () -> Data? in
                let format = UIGraphicsImageRendererFormat()
                format.scale = 1
                format.opaque = !png
                let img = UIGraphicsImageRenderer(size: imgSize, format: format).image { ctx in
                    base.draw(in: CGRect(origin: .zero, size: imgSize))
                    MarkRenderer.draw(list, in: ctx.cgContext, signature: sig, initials: ini, scale: 1)
                }
                return png ? img.pngData() : img.jpegData(compressionQuality: 0.92)
            }.value
            guard let data else { throw DocumentPDF.OpError(message: "Bild nicht lesbar") }
            try? FileManager.default.removeItem(at: url)
            try data.write(to: url, options: .atomic)
        }
        if Self.fileSize(url) <= 0 { throw DocumentPDF.OpError(message: "Datei ist leer") }
    }

    nonisolated static func fileSize(_ url: URL) -> Int64 {
        let attrs = (try? FileManager.default.attributesOfItem(atPath: url.path)) ?? [:]
        return (attrs[.size] as? NSNumber)?.int64Value ?? 0
    }

    private func produceToExports() async throws -> URL {
        let out = DocumentEditing.cacheDir("exports").appendingPathComponent(outputName())
        try await produce(to: out)
        return out
    }

    /// Gemeinsamer Rahmen aller Speicherwege: Sperre + Fehlermeldung.
    private func runSaving(_ block: @escaping @MainActor () async throws -> Void) {
        guard !saving else { return }
        saving = true
        Task {
            do {
                try await block()
            } catch {
                // Grund mitgeben — sonst sähe es aus, als passiere nichts
                let reason = String(error.localizedDescription.prefix(120))
                let text = L("editor_save_failed")
                snackbar.show(reason.isEmpty ? text : "\(text) (\(reason))")
            }
            saving = false
        }
    }

    func runAction(_ action: EditorAction) {
        guard !saving else { return }
        if hasRedact && !redactAccepted {
            redactWarnAction = action
        } else {
            execute(action)
        }
    }

    func confirmRedactWarning(_ action: EditorAction?) {
        guard let a = action ?? redactWarnAction else { return }
        redactAccepted = true
        redactWarnAction = nil
        execute(a)
    }

    private func execute(_ action: EditorAction) {
        switch action {
        case .sendMail: sendAsMail()
        case .saveAs: saveAs()
        case .overwrite: overwriteOriginal()
        case .share: share()
        case .print: printResult()
        }
    }

    /// Hauptknopf: je Herkunft der Weg, der Zeit spart.
    func mainAction() {
        guard let src = source else { return }
        switch src.origin {
        case .mail, .externalShare: runAction(.sendMail)
        case .externalEdit: runAction(src.canOverwrite ? .overwrite : .saveAs)
        case .externalView: runAction(.saveAs)
        }
    }

    var mainActionIsMail: Bool {
        guard let src = source else { return true }
        return src.origin == .mail || src.origin == .externalShare
    }

    var mainActionLabel: String {
        if saving { return L("editor_saving") }
        guard let src = source else { return L("editor_attach") }
        switch src.origin {
        case .externalShare: return L("editor_menu_send_mail")
        case .externalEdit: return L(src.canOverwrite ? "editor_save_overwrite" : "editor_menu_save_as")
        case .externalView: return L("editor_menu_save_as")
        case .mail: return L(src.replyUid != nil ? "editor_send_reply" : "editor_attach")
        }
    }

    /// Fertiges Dokument ans Verfassen-Fenster übergeben.
    private func sendAsMail() {
        runSaving { [weak self] in
            guard let self else { return }
            let dir = FileManager.default.temporaryDirectory.appendingPathComponent("attachments", isDirectory: true)
            try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
            let out = dir.appendingPathComponent(self.outputName())
            try await self.produce(to: out)
            DocumentEditing.pendingResult = DocumentEditing.Result(url: out, name: out.lastPathComponent,
                                                                   size: Self.fileSize(out))
            let repo = MailRepository.shared
            let account = self.source?.account ?? ""
            var reply: MailMessage?
            if let uid = self.source?.replyUid {
                reply = repo.messages.first { $0.uid == uid && repo.sameAccount($0.account, account) }
            }
            let nav = AppNav.shared
            // Editor aus dem Verlauf nehmen: Zurück aus dem Verfassen-Fenster
            // soll zur Mail führen
            if nav.path.last == .editor { nav.pop() }
            nav.compose = ComposeRequest(replyTo: reply, prefill: ComposePrefill(attachments: [out]))
        }
    }

    private func share() {
        runSaving { [weak self] in
            guard let self else { return }
            let out = try await self.produceToExports()
            self.sheet = .share(out)
        }
    }

    private func saveAs() {
        runSaving { [weak self] in
            guard let self else { return }
            let out = try await self.produceToExports()
            self.sheet = .export(out)
        }
    }

    private func printResult() {
        runSaving { [weak self] in
            guard let self else { return }
            let out = try await self.produceToExports()
            let pc = UIPrintInteractionController.shared
            let info = UIPrintInfo(dictionary: nil)
            info.jobName = out.lastPathComponent
            info.outputType = .general
            pc.printInfo = info
            pc.printingItem = out
            pc.present(animated: true, completionHandler: nil)
        }
    }

    /// Ausgangsdatei überschreiben (nur „Bearbeiten“ mit Schreibrecht).
    private func overwriteOriginal() {
        runSaving { [weak self] in
            guard let self else { return }
            guard let target = self.source?.url else { throw DocumentPDF.OpError(message: "Keine Zieldatei") }
            let out = try await self.produceToExports()
            let data = try Data(contentsOf: out)
            let scoped = target.startAccessingSecurityScopedResource()
            defer { if scoped { target.stopAccessingSecurityScopedResource() } }
            try data.write(to: target, options: .atomic)
            self.snackbar.show(L("editor_saved"))
        }
    }

    /// Ergebnis der Dateien-Sicherung melden.
    func exportFinished(_ ok: Bool) {
        if ok { snackbar.show(L("editor_saved")) }
    }

    // Abgeleitete Ergebnisse: erst mit allen Aufsätzen bauen, dann ableiten

    private func preparedURL(_ name: String) -> URL {
        let dir = DocumentEditing.cacheDir("exports").appendingPathComponent("prepared", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent(DocumentEditing.safeFileName(name))
    }

    func extractPages(_ from: Int, _ to: Int) {
        runSaving { [weak self] in
            guard let self else { return }
            let annotated = try await self.produceToExports()
            let out = self.preparedURL("\(self.baseName())-S\(from + 1)-\(to + 1).pdf")
            let ok = await Task.detached(priority: .userInitiated) {
                DocumentPDF.extract(annotated, to: out, from: from, to: to)
            }.value
            if !ok { throw DocumentPDF.OpError(message: L("editor_page_op_failed")) }
            self.sheet = .export(out)
        }
    }

    func confirmExtract() {
        let from = Int(extractFrom.trimmingCharacters(in: .whitespaces)).map { $0 - 1 }
        let to = Int(extractTo.trimmingCharacters(in: .whitespaces)).map { $0 - 1 }
        guard let from, let to, from >= 0, to < pageSizes.count, from <= to else {
            snackbar.show(L("editor_extract_invalid"))
            return
        }
        extractPages(from, to)
    }

    func openExtract() {
        extractFrom = "\(pageIndex + 1)"
        extractTo = "\(pageIndex + 1)"
        extractAsk = true
    }

    func protectAndSave() {
        let pw = protectPassword
        protectPassword = ""
        guard !pw.isEmpty else { return }
        runSaving { [weak self] in
            guard let self else { return }
            let annotated = try await self.produceToExports()
            let out = self.preparedURL("\(self.baseName())-\(L("editor_suffix_protected")).pdf")
            let ok = await Task.detached(priority: .userInitiated) {
                DocumentPDF.protect(annotated, to: out, password: pw)
            }.value
            if !ok { throw DocumentPDF.OpError(message: L("editor_save_failed")) }
            self.sheet = .export(out)
        }
    }

    func compressAndSave() {
        runSaving { [weak self] in
            guard let self else { return }
            let annotated = try await self.produceToExports()
            let out = self.preparedURL("\(self.baseName())-\(L("editor_suffix_small")).pdf")
            let ok = await Task.detached(priority: .userInitiated) {
                DocumentPDF.compress(annotated, to: out)
            }.value
            if !ok { throw DocumentPDF.OpError(message: L("editor_save_failed")) }
            self.sheet = .export(out)
        }
    }

    // MARK: KI-Assistent

    func sendAI() {
        let q = aiInput.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty, !aiBusy else { return }
        aiInput = ""
        aiMessages.append(AIMessage(user: true, text: q))
        aiBusy = true
        Task {
            // Abkürzung für den häufigsten Wunsch: „fasse zusammen“ braucht
            // keine Übersetzung durch das Modell
            let lower = q.lowercased()
            let cmd: [String: Any]?
            if lower.range(of: "zusammenfass|fasse |summar|fass ", options: .regularExpression) != nil {
                cmd = ["aktion": "zusammenfassen"]
            } else {
                cmd = await EditorAI.command(q, pageCount: pageSizes.count, currentPage: pageIndex + 1)
            }
            let answer: String
            if let cmd {
                answer = await executeAI(cmd)
            } else {
                answer = L("editor_ai_fail")
            }
            aiMessages.append(AIMessage(user: false, text: answer))
            aiBusy = false
        }
    }

    /// „Markiere alle …“: Fundstellen suchen und als Textmarker auflegen.
    private func aiHighlight(_ muster: String, _ begriff: String) async -> String {
        guard let doc else { return L("editor_ai_fail") }
        let pattern: String
        var options: NSRegularExpression.Options = []
        switch muster {
        case "geld":
            // Währung vor ODER nach dem Betrag
            pattern = #"(?:[€£$]|EUR|USD|GBP|CHF)\s*\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?"# +
                #"|\d{1,3}(?:[.,]\d{3})*(?:[.,]\d{1,2})?\s*(?:[€£$]|(?:EUR|USD|GBP|CHF)\b)"#
        case "datum":
            pattern = #"\b\d{1,2}\.\s?\d{1,2}\.\s?\d{2,4}\b"#
        case "iban":
            pattern = #"\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]{4}){3,7}(?:\s?[A-Z0-9]{1,3})?\b"#
        case "email":
            pattern = #"[\w.+-]+@[\w-]+\.[A-Za-z]{2,}"#
        default:
            let term = begriff.trimmingCharacters(in: .whitespacesAndNewlines)
            guard term.count >= 2 else { return L("editor_ai_fail") }
            pattern = NSRegularExpression.escapedPattern(for: term)
            options = [.caseInsensitive]
        }
        guard let regex = try? NSRegularExpression(pattern: pattern, options: options) else { return L("editor_ai_fail") }
        let boxes = await Task.detached(priority: .userInitiated) {
            DocumentPDF.locate(doc, regex: regex)
        }.value
        guard let first = boxes.first else { return L("editor_ai_no_matches") }
        for b in boxes where b.page < pageSizes.count {
            let yMid = b.rect.midY
            addMark(.stroke(points: [CGPoint(x: b.rect.minX, y: yMid), CGPoint(x: b.rect.maxX, y: yMid)],
                            width: b.rect.height, color: MarkRenderer.highlightColor, highlight: true),
                    page: b.page, select: false)
        }
        scrollTo(first.page)
        return L("editor_ai_marked", boxes.count)
    }

    /// Führt einen KI-Befehl aus und liefert die Chat-Antwort.
    private func executeAI(_ cmd: [String: Any]) async -> String {
        func pageArg(_ key: String) -> Int {
            let v = cmd.optInt(key, 0)
            return (v >= 1 && v <= pageSizes.count) ? v - 1 : pageIndex
        }
        switch cmd.optString("aktion") {
        case "gehe_zu":
            let p = pageArg("seite")
            scrollTo(p)
            return L("editor_search_page", p + 1)
        case "drehen":
            let p = pageArg("seite")
            rotatePage(p, cw: cmd.optString("richtung", "rechts") != "links")
            return L("editor_ai_rotated", p + 1)
        case "seite_loeschen":
            let p = pageArg("seite")
            deletePage(p)
            return L("editor_ai_deleted", p + 1)
        case "leere_seite":
            let raw = cmd.optInt("position", 0)
            let pos = (raw >= 1 && raw <= pageSizes.count) ? raw : pageSizes.count
            insertBlank(at: pos)
            return L("editor_ai_inserted")
        case "nachtmodus":
            nightMode = cmd.optBool("an", true)
            return L(nightMode ? "editor_ai_night_on" : "editor_ai_night_off")
        case "suchen":
            let term = cmd.optString("begriff")
            guard term.count >= 2, let doc else { return L("editor_ai_fail") }
            let hits = await Task.detached(priority: .userInitiated) {
                DocumentPDF.search(doc, query: term)
            }.value
            searchQuery = term
            searchResults = hits
            if let h = hits.first { scrollTo(h.page) }
            return L("editor_ai_search", hits.count)
        case "markieren":
            return await aiHighlight(cmd.optString("muster"), cmd.optString("begriff"))
        case "datum_stempel":
            let p = pageArg("seite")
            guard p < pageSizes.count else { return L("editor_ai_fail") }
            let s = pageSizes[p]
            addMark(.label(text: Self.todayString(), center: CGPoint(x: s.width * 0.5, y: s.height * 0.92),
                           size: s.width / 32, color: inkColor.uiColor), page: p, select: false)
            scrollTo(p)
            return L("editor_ai_stamp")
        case "auszug":
            let from = cmd.optInt("von", 1) - 1
            let to = cmd.optInt("bis", pageSizes.count) - 1
            if from < 0 || to >= pageSizes.count || from > to { return L("editor_extract_invalid") }
            extractPages(from, to)
            return L("editor_ai_saveflow")
        case "verkleinern":
            compressAndSave()
            return L("editor_ai_saveflow")
        case "zusammenfassen":
            guard let doc else { return L("editor_ai_fail") }
            let text = DocumentPDF.text(doc)
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return L("editor_ai_no_text") }
            return await EditorAI.summarize(text) ?? L("editor_ai_fail")
        case "frage":
            let frage = cmd.optString("frage")
            guard !frage.trimmingCharacters(in: .whitespaces).isEmpty, let doc else { return L("editor_ai_fail") }
            let text = DocumentPDF.text(doc)
            if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { return L("editor_ai_no_text") }
            return await EditorAI.answerQuestion(text, question: frage) ?? L("editor_ai_fail")
        case "keine":
            let a = cmd.optString("antwort")
            return a.trimmingCharacters(in: .whitespaces).isEmpty ? L("editor_ai_fail") : a
        default:
            return L("editor_ai_fail")
        }
    }
}
