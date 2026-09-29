import SwiftUI
import UIKit
import UniformTypeIdentifiers

// Gemeinsame Hilfen für Mail-Detail und Anhang-Galerie (Port der
// Android-Helfer formatSize/attachmentIcon sowie open/share/saveAttachment).

/// Formatierungen wie in DetailScreen.kt / AttachmentsScreen.kt.
enum DetailFormat {
    /// Dateigröße wie `formatSize` (Android).
    static func size(_ bytes: Int) -> String {
        if bytes >= 1_000_000 {
            return String(format: "%.1f MB", locale: Locale.current, Double(bytes) / 1_000_000.0)
        }
        if bytes >= 1_000 { return "\(bytes / 1_000) KB" }
        return "\(bytes) B"
    }

    /// SF-Symbol je Dateityp (Bild / PDF / sonstiger Anhang).
    static func icon(_ mime: String) -> String {
        if mime.hasPrefix("image/") { return "photo" }
        if mime == "application/pdf" { return "doc.richtext" }
        return "paperclip"
    }

    /// „Montag, 5. März 2026, 14:03“ (Android: "EEEE, d. MMMM yyyy, HH:mm").
    static func longDate(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "EEEE, d. MMMM yyyy, HH:mm"
        return f.string(from: Date(ms: ms))
    }

    /// „5. März 2026, 14:03“ (Beantwortet-Zeile).
    static func repliedDate(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "d. MMM yyyy, HH:mm"
        return f.string(from: Date(ms: ms))
    }

    /// „5. März 2026“ (Anhang-Galerie).
    static func shortDate(_ ms: Int64) -> String {
        let f = DateFormatter()
        f.locale = Locale.current
        f.dateFormat = "d. MMM yyyy"
        return f.string(from: Date(ms: ms))
    }

    static func norm(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}

/// Präsentiert UIKit-Controller (Teilen-Blatt, Dateien-Export) über dem
/// obersten sichtbaren Controller — funktioniert auch aus Sheets heraus.
@MainActor
enum DetailPresenter {

    static func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap { $0.windows }
        let window = windows.first(where: { $0.isKeyWindow }) ?? windows.first
        var top = window?.rootViewController
        while let presented = top?.presentedViewController, !presented.isBeingDismissed {
            top = presented
        }
        return top
    }

    static func present(_ vc: UIViewController) {
        guard let top = topViewController() else { return }
        if let pop = vc.popoverPresentationController {
            pop.sourceView = top.view
            pop.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            pop.permittedArrowDirections = []
        }
        top.present(vc, animated: true)
    }

    /// Teilen-Blatt (Android: ACTION_SEND-Chooser).
    static func share(_ items: [Any]) {
        present(UIActivityViewController(activityItems: items, applicationActivities: nil))
    }

    /// „In Dateien sichern“ (Android: in Downloads speichern).
    /// `done(true)` nach erfolgreichem Sichern, `done(false)` bei Abbruch.
    static func export(_ url: URL, done: @escaping (Bool) -> Void) {
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        let delegate = DetailExportDelegate(done: done)
        picker.delegate = delegate
        DetailExportDelegate.active = delegate
        present(picker)
    }
}

/// Delegate des Export-Dialogs (bleibt bis zum Ergebnis am Leben).
@MainActor
final class DetailExportDelegate: NSObject, UIDocumentPickerDelegate {
    static var active: DetailExportDelegate?
    private let done: (Bool) -> Void

    init(done: @escaping (Bool) -> Void) { self.done = done }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        done(true)
        DetailExportDelegate.active = nil
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        done(false)
        DetailExportDelegate.active = nil
    }
}

/// Mögliche Aktionen für einen Anhang (Android: "sign"/"open"/"share"/"save").
enum DetailAttachmentAction {
    case sign, open, share, save
}

/// Auswahl im Anhang-Blatt (Identifiable für `.sheet(item:)`).
struct DetailAttachmentItem: Identifiable {
    let att: MailRepository.MailAttachment
    var id: String { att.section + "|" + att.name }
}

/// Bottom-Sheet mit Dateikopf und Aktionszeilen (Android: ModalBottomSheet).
struct DetailAttachmentSheet: View {
    let att: MailRepository.MailAttachment
    let editable: Bool
    let onAction: (DetailAttachmentAction) -> Void

    @Environment(\.palette) private var palette

    var body: some View {
        let mime = MailRepository.effectiveMime(att.name, att.mime)
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 14) {
                RoundedRectangle(cornerRadius: 12)
                    .fill(palette.secondaryContainer)
                    .frame(width: 48, height: 48)
                    .overlay(
                        Image(systemName: DetailFormat.icon(mime))
                            .font(.title3)
                            .foregroundStyle(palette.onSecondaryContainer)
                    )
                VStack(alignment: .leading, spacing: 2) {
                    Text(att.name)
                        .font(.headline)
                        .foregroundStyle(palette.onSurface)
                        .lineLimit(2)
                    Text(DetailFormat.size(att.size))
                        .font(.caption)
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)
            .padding(.top, 24)
            .padding(.bottom, 8)
            Divider().padding(.vertical, 8)
            if editable {
                // Ganz oben: Vertrag öffnen, unterschreiben, zurückschicken
                row("signature", L("detail_attachment_sign"), tint: palette.primary, .sign)
            }
            row("arrow.up.forward.square",
                L(editable ? "detail_attachment_open_edit" : "detail_attachment_open"),
                tint: nil, editable ? .sign : .open)
            if editable {
                // Zusätzlich die reine Vorschau (QuickLook)
                row("eye", L("detail_attachment_open"), tint: nil, .open)
            }
            row("square.and.arrow.up", L("detail_attachment_share"), tint: nil, .share)
            row("folder", L("detail_attachment_save_files"), tint: nil, .save)
            Spacer(minLength: 16)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surface)
    }

    private func row(_ icon: String, _ label: String, tint: Color?, _ action: DetailAttachmentAction) -> some View {
        Button {
            onAction(action)
        } label: {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .frame(width: 24)
                    .foregroundStyle(tint ?? palette.onSurfaceVariant)
                Text(label)
                    .font(.body)
                    .foregroundStyle(tint ?? palette.onSurface)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Auswählbarer Klartext (Android: SelectionContainer) — echte
/// Bereichsauswahl, erkannte Links/Telefonnummern antippbar.
struct DetailSelectableText: UIViewRepresentable {
    let text: String
    let color: UIColor

    func makeUIView(context: Context) -> UITextView {
        let tv = UITextView()
        tv.isEditable = false
        tv.isSelectable = true
        tv.isScrollEnabled = false
        tv.backgroundColor = .clear
        tv.textContainerInset = .zero
        tv.textContainer.lineFragmentPadding = 0
        tv.dataDetectorTypes = [.link, .phoneNumber]
        tv.font = UIFont.preferredFont(forTextStyle: .body)
        tv.adjustsFontForContentSizeCategory = true
        tv.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
        tv.setContentHuggingPriority(.defaultLow, for: .horizontal)
        return tv
    }

    func updateUIView(_ tv: UITextView, context: Context) {
        if tv.text != text { tv.text = text }
        tv.textColor = color
    }

    func sizeThatFits(_ proposal: ProposedViewSize, uiView: UITextView, context: Context) -> CGSize? {
        let width = proposal.width ?? uiView.window?.bounds.width ?? 375
        let fitted = uiView.sizeThatFits(CGSize(width: width, height: CGFloat.greatestFiniteMagnitude))
        return CGSize(width: width, height: ceil(fitted.height))
    }
}

/// Filter-Chip (Android: FilterChip).
struct DetailFilterChip: View {
    let label: String
    let selected: Bool
    let action: () -> Void

    @Environment(\.palette) private var palette

    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if selected {
                    Image(systemName: "checkmark").font(.caption.bold())
                }
                Text(label).font(.subheadline)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 7)
            .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurfaceVariant)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(selected ? palette.secondaryContainer : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(selected ? Color.clear : palette.outlineVariant, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}
