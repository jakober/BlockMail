import SwiftUI
import UIKit
import QuickLook

/// Anhang-Galerie (Port von `ui/AttachmentsScreen.kt`): alle Anhänge der
/// geladenen Mails an einem Ort — mit Filter (Bilder/Dokumente), Öffnen per
/// Tipp, Teilen und Sprung zur zugehörigen Mail.
struct AttachmentsScreen: View {

    init() {}

    @Environment(AppNav.self) private var nav
    @Environment(\.palette) private var palette

    @State private var snackbar = SnackbarState()
    @State private var entries: [MailRepository.AttachmentIndexEntry]?
    /// "all" | "images" | "docs"
    @State private var filter = "all"
    @State private var previewURL: URL?

    private var repo: MailRepository { MailRepository.shared }

    private var shown: [MailRepository.AttachmentIndexEntry] {
        (entries ?? []).filter { e in
            let isImage = MailRepository.effectiveMime(e.att.name, e.att.mime).hasPrefix("image/")
            switch filter {
            case "images": return isImage
            case "docs": return !isImage
            default: return true
            }
        }
        .sorted { $0.mail.date > $1.mail.date }
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 8) {
                DetailFilterChip(label: L("att_filter_all"), selected: filter == "all") { filter = "all" }
                DetailFilterChip(label: L("att_filter_images"), selected: filter == "images") { filter = "images" }
                DetailFilterChip(label: L("att_filter_docs"), selected: filter == "docs") { filter = "docs" }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 8)

            if entries == nil {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                let list = shown
                if list.isEmpty {
                    VStack(spacing: 6) {
                        Text(L("att_empty_title"))
                            .font(.body)
                            .foregroundStyle(palette.onSurface)
                        Text(L("att_empty_text"))
                            .font(.caption)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .multilineTextAlignment(.center)
                    }
                    .padding(32)
                    .frame(maxWidth: .infinity)
                    Spacer()
                } else {
                    ScrollView {
                        LazyVStack(spacing: 8) {
                            ForEach(list) { e in
                                row(e)
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.vertical, 4)
                    }
                }
            }
        }
        .background(palette.surface.ignoresSafeArea())
        .navigationTitle(L("att_title"))
        .navigationBarTitleDisplayMode(.inline)
        .snackbar(snackbar)
        .quickLookPreview($previewURL)
        .task {
            entries = repo.attachmentIndex()
            // Fehlende Inhalte gleich nachladen und die Liste auffrischen — so
            // füllt sich die Galerie auch direkt nach Update oder Kontowechsel
            await repo.prefetchAttachmentBodies()
            entries = repo.attachmentIndex()
        }
    }

    private func row(_ e: MailRepository.AttachmentIndexEntry) -> some View {
        let att = e.att
        let mail = e.mail
        let mime = MailRepository.effectiveMime(att.name, att.mime)
        var meta = [mail.from.isEmpty ? mail.fromAddress : mail.from, DetailFormat.shortDate(mail.date)]
        if att.size > 0 { meta.append(DetailFormat.size(att.size)) }
        return HStack(spacing: 12) {
            Button {
                act(e, share: false)
            } label: {
                Image(systemName: DetailFormat.icon(mime))
                    .font(.title2)
                    .foregroundStyle(palette.primary)
                    .frame(width: 32, height: 32)
            }
            .buttonStyle(.plain)
            VStack(alignment: .leading, spacing: 2) {
                Button {
                    act(e, share: false)
                } label: {
                    Text(att.name)
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(palette.onSurface)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                // Absender antippen öffnet die zugehörige Mail
                Button {
                    openMail(e)
                } label: {
                    Text(meta.joined(separator: " · "))
                        .font(.caption)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .lineLimit(1)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            Button {
                act(e, share: true)
            } label: {
                Image(systemName: "square.and.arrow.up")
                    .foregroundStyle(palette.onSurfaceVariant)
                    .frame(width: 40, height: 40)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("att_share"))
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(RoundedRectangle(cornerRadius: 14).fill(palette.surfaceContainer))
        .contextMenu {
            Button { act(e, share: false) } label: {
                Label(L("detail_attachment_open"), systemImage: "eye")
            }
            Button { act(e, share: true) } label: {
                Label(L("att_share"), systemImage: "square.and.arrow.up")
            }
            Button { save(e) } label: {
                Label(L("detail_attachment_save_files"), systemImage: "folder")
            }
            Button { openMail(e) } label: {
                Label(L("att_open_mail"), systemImage: "envelope")
            }
        }
    }

    private func openMail(_ e: MailRepository.AttachmentIndexEntry) {
        nav.push(.detail(uid: e.mail.uid, account: e.mail.account, folder: nil, fallback: e.mail))
    }

    /// Lädt den Anhang und öffnet (QuickLook) bzw. teilt ihn.
    private func act(_ e: MailRepository.AttachmentIndexEntry, share: Bool) {
        snackbar.show(L("att_loading", e.att.name))
        Task {
            do {
                let data = try await repo.getAttachmentData(e.mail.uid, e.att, account: e.mail.account, folder: .INBOX)
                let url = try MailRepository.writeTempFile(name: e.att.name, data: data)
                snackbar.current = nil
                if share {
                    DetailPresenter.share([url])
                } else {
                    previewURL = url
                }
            } catch {
                snackbar.show(L(share ? "att_share_failed" : "att_open_failed", error.localizedDescription))
            }
        }
    }

    private func save(_ e: MailRepository.AttachmentIndexEntry) {
        snackbar.show(L("att_loading", e.att.name))
        Task {
            do {
                let data = try await repo.getAttachmentData(e.mail.uid, e.att, account: e.mail.account, folder: .INBOX)
                let url = try MailRepository.writeTempFile(name: e.att.name, data: data)
                snackbar.current = nil
                DetailPresenter.export(url) { saved in
                    if saved { snackbar.show(L("detail_attachment_saved", e.att.name)) }
                }
            } catch {
                snackbar.show(L("detail_attachment_action_failed", error.localizedDescription))
            }
        }
    }
}
