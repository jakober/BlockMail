import SwiftUI
import UIKit
import PhotosUI
import UniformTypeIdentifiers

/// Verfassen-Fenster (Port von `ComposeScreen.kt`): Neue Mail, Antwort,
/// Weiterleiten, Entwurf fortsetzen oder von außen vorbefüllt. Wird als
/// Sheet gezeigt; geschlossen über `dismiss` bzw. `AppNav.shared.compose = nil`.
struct ComposeScreen: View {
    @StateObject private var model: ComposeModel

    init(request: ComposeRequest) {
        _model = StateObject(wrappedValue: ComposeModel(request: request))
    }

    var body: some View {
        ComposeContent(model: model, editor: model.editor)
            .blockMailTheme()
            .environment(Prefs.shared)
    }
}

private struct ComposeContent: View {
    @ObservedObject var model: ComposeModel
    @ObservedObject var editor: RichTextController

    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase
    @FocusState private var focus: ComposeModel.Field?

    var body: some View {
        NavigationStack {
            ZStack {
                palette.surface.ignoresSafeArea()
                form
                if model.busy || model.sending {
                    progressOverlay
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) { bottomBar }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { toolbarContent }
            .toolbarBackground(palette.surface, for: .navigationBar)
        }
        .snackbar(model.snackbar)
        .interactiveDismissDisabled(model.isMeaningful || model.sending)
        .task { await model.start() }
        .onChange(of: model.finished) { _, done in
            if done { dismiss() }
        }
        .onChange(of: scenePhase) { _, phase in
            // App geht in den Hintergrund: Entwurf vorsorglich sichern
            if phase == .background && !model.finished { model.storeDraft() }
        }
        .onDisappear { model.onDisappear() }
        .confirmationDialog(L("compose_discard_title"), isPresented: $model.showDiscardDialog, titleVisibility: .visible) {
            Button(L("compose_save_draft")) { model.closeSavingDraft() }
            Button(L("compose_discard"), role: .destructive) { model.discard() }
            Button(L("compose_cancel"), role: .cancel) {}
        } message: {
            Text(L("compose_discard_message"))
        }
        .sheet(isPresented: $model.showScheduleDialog) {
            ComposeScheduleSheet(
                onPick: { model.schedule(at: $0) },
                onCancel: { model.showScheduleDialog = false }
            )
            .environment(\.palette, palette)
        }
        .sheet(isPresented: $model.showPromptDialog) {
            ComposePromptSheet(
                text: $model.promptText,
                onConfirm: { model.aiComposeMail() },
                onCancel: { model.showPromptDialog = false }
            )
            .environment(\.palette, palette)
        }
        .fileImporter(isPresented: $model.showFileImporter, allowedContentTypes: [.item],
                      allowsMultipleSelection: true) { result in
            model.handleImport(result)
        }
        .photosPicker(isPresented: $model.showPhotoPicker, selection: $model.photoItems,
                      maxSelectionCount: 10, matching: .images)
        .onChange(of: model.photoItems) { _, items in
            model.loadPhotos(items)
        }
    }

    // MARK: Kopfzeile

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .cancellationAction) {
            Button {
                focus = nil
                _ = model.requestClose()
            } label: {
                Image(systemName: "xmark")
            }
            .accessibilityLabel(L("compose_close_saves_draft"))
            .disabled(model.sending)
        }
        ToolbarItem(placement: .principal) {
            VStack(spacing: 1) {
                Text(model.titleText)
                    .font(.headline)
                    .foregroundStyle(palette.onSurface)
                fromAccountView
            }
        }
        ToolbarItemGroup(placement: .confirmationAction) {
            Button {
                focus = nil
                model.showScheduleDialog = true
            } label: {
                Image(systemName: "clock")
                    .foregroundStyle(model.canSend ? palette.onSurfaceVariant : palette.onSurfaceVariant.opacity(0.4))
            }
            .accessibilityLabel(L("compose_send_later"))
            .disabled(!model.canSend)
            Button {
                focus = nil
                model.send()
            } label: {
                Image(systemName: "paperplane.fill")
                    .foregroundStyle(model.canSend ? palette.primary : palette.onSurfaceVariant.opacity(0.4))
            }
            .accessibilityLabel(L("compose_send"))
            .disabled(!model.canSend)
        }
    }

    /// Absender-Wähler: bei mehreren Konten antippbar (nicht bei Antworten).
    @ViewBuilder
    private var fromAccountView: some View {
        if model.canChooseAccount {
            Menu {
                ForEach(model.accounts) { acc in
                    Button {
                        model.fromAccount = acc.email
                    } label: {
                        if acc.email.caseInsensitiveCompare(model.fromAccount) == .orderedSame {
                            Label(acc.email, systemImage: "checkmark")
                        } else {
                            Text(acc.email)
                        }
                    }
                }
            } label: {
                HStack(spacing: 2) {
                    Text(model.fromAccount)
                        .font(.caption)
                        .lineLimit(1)
                    Image(systemName: "chevron.down")
                        .font(.caption2.weight(.semibold))
                }
                .foregroundStyle(palette.primary)
            }
            .accessibilityLabel(L("compose_choose_from_account"))
        } else if !model.fromAccount.isEmpty {
            Text(model.fromAccount)
                .font(.caption)
                .foregroundStyle(palette.primary)
                .lineLimit(1)
        }
    }

    // MARK: Formular

    private var form: some View {
        ScrollView {
            VStack(spacing: 0) {
                ComposeRecipientRow(label: L("compose_to"), value: $model.to, focus: $focus, field: .to) {
                    if !model.showCcBcc {
                        Button(L("compose_cc_bcc")) { model.showCcBcc = true }
                            .font(.subheadline)
                            .foregroundStyle(palette.primary)
                    }
                }
                ComposeSuggestionList(visible: focus == .to, input: model.to, contacts: model.contacts) {
                    model.to = $0
                }
                divider
                if model.showCcBcc {
                    ComposeRecipientRow(label: L("compose_cc"), value: $model.cc, focus: $focus, field: .cc) {
                        EmptyView()
                    }
                    ComposeSuggestionList(visible: focus == .cc, input: model.cc, contacts: model.contacts) {
                        model.cc = $0
                    }
                    divider
                    ComposeRecipientRow(label: L("compose_bcc"), value: $model.bcc, focus: $focus, field: .bcc) {
                        EmptyView()
                    }
                    ComposeSuggestionList(visible: focus == .bcc, input: model.bcc, contacts: model.contacts) {
                        model.bcc = $0
                    }
                    divider
                }

                TextField(L("compose_subject"), text: $model.subject)
                    .font(.title3.weight(.semibold))
                    .foregroundStyle(palette.onSurface)
                    .focused($focus, equals: .subject)
                    .submitLabel(.next)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 14)
                divider

                ZStack(alignment: .topLeading) {
                    if editor.plainText.isEmpty {
                        Text(L("compose_message_hint"))
                            .font(.body)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .allowsHitTesting(false)
                    }
                    RichTextView(controller: editor, tint: UIColor(palette.primary), version: editor.version)
                        .frame(maxWidth: .infinity, minHeight: 300, alignment: .topLeading)
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 14)

                Spacer().frame(height: 80)
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .overlay(alignment: .bottomTrailing) { aiButton }
    }

    private var divider: some View {
        Rectangle()
            .fill(palette.outlineVariant)
            .frame(height: 0.5)
    }

    // MARK: Fußleiste (Anhänge, Formatierung, Vorlagen)

    private var bottomBar: some View {
        VStack(alignment: .leading, spacing: 0) {
            Rectangle().fill(palette.outlineVariant).frame(height: 0.5)
            if let lang = model.lastLanguage {
                Text(L("compose_ai_language_detected", lang))
                    .font(.caption)
                    .foregroundStyle(palette.primary)
                    .padding(.leading, 16)
                    .padding(.top, 6)
            }
            if !model.pickedFiles.isEmpty || !model.fwdAttachments.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        // Original-Anhänge der weitergeleiteten Mail (abwählbar)
                        ForEach(model.fwdAttachments, id: \.self) { att in
                            ComposeAttachmentChip(name: att.name, size: att.size, selected: true) {
                                model.removeForwarded(att)
                            }
                        }
                        ForEach(model.pickedFiles) { f in
                            ComposeAttachmentChip(name: f.name, size: Int(f.size), selected: false) {
                                model.removePicked(f)
                            }
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                }
            }
            HStack(spacing: 2) {
                Menu {
                    Button {
                        model.showFileImporter = true
                    } label: {
                        Label(L("compose_attach_file"), systemImage: "doc")
                    }
                    Button {
                        model.showPhotoPicker = true
                    } label: {
                        Label(L("compose_attach_photo"), systemImage: "photo")
                    }
                } label: {
                    Image(systemName: "paperclip")
                        .font(.system(size: 18))
                        .foregroundStyle(palette.onSurfaceVariant)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(L("compose_attachment"))

                ComposeFormatButton(systemImage: "bold", description: L("compose_format_bold"),
                                    active: editor.isBold) { editor.toggleBold() }
                ComposeFormatButton(systemImage: "italic", description: L("compose_format_italic"),
                                    active: editor.isItalic) { editor.toggleItalic() }
                ComposeFormatButton(systemImage: "underline", description: L("compose_format_underline"),
                                    active: editor.isUnderline) { editor.toggleUnderline() }
                ComposeFormatButton(systemImage: "list.bullet", description: L("compose_format_list"),
                                    active: editor.isList) { editor.toggleList() }

                templatesMenu
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
        }
        .background(palette.surfaceContainer.ignoresSafeArea(edges: .bottom))
    }

    private var templatesMenu: some View {
        Menu {
            let templates = Prefs.shared.mailTemplates()
            if templates.isEmpty {
                Button(L("compose_templates_empty")) {}
                    .disabled(true)
            } else {
                ForEach(templates.indices, id: \.self) { i in
                    Button(templates[i].0) {
                        model.insertTemplate(templates[i].1)
                    }
                }
            }
        } label: {
            Image(systemName: "doc.text")
                .font(.system(size: 18))
                .foregroundStyle(palette.onSurfaceVariant)
                .frame(width: 40, height: 40)
                .contentShape(Rectangle())
        }
        .accessibilityLabel(L("compose_templates"))
    }

    // MARK: KI-Knopf

    /// KI-Menü (immer verfügbar): Antwort entwerfen, Mail formulieren, Rechtschreibung.
    private var aiButton: some View {
        Menu {
            if model.original != nil {
                Button {
                    model.aiDraftReply()
                } label: {
                    Label(L("compose_ai_draft_reply"), systemImage: "sparkles")
                }
            }
            Button {
                model.showPromptDialog = true
            } label: {
                Label(L("compose_ai_compose_mail"), systemImage: "sparkles")
            }
            Button {
                model.aiProofread()
            } label: {
                Label(L("compose_ai_proofread"), systemImage: "textformat.abc.dottedunderline")
            }
        } label: {
            Image(systemName: "sparkles")
                .font(.system(size: 22, weight: .medium))
                .foregroundStyle(palette.onPrimaryContainer)
                .frame(width: 56, height: 56)
                .background(RoundedRectangle(cornerRadius: 16).fill(palette.primaryContainer))
                .shadow(color: .black.opacity(0.2), radius: 4, y: 2)
        }
        .accessibilityLabel(L("compose_ai_functions"))
        .disabled(model.busy || model.sending)
        .padding(.trailing, 16)
        .padding(.bottom, 16)
    }

    // MARK: Fortschritt

    private var progressOverlay: some View {
        ZStack {
            Color.black.opacity(0.08).ignoresSafeArea()
            HStack(spacing: 16) {
                ProgressView()
                Text(model.sending ? L("compose_sending") : model.busyLabel)
                    .font(.body)
                    .foregroundStyle(palette.onSurface)
            }
            .padding(20)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(palette.surfaceContainerHigh)
                    .shadow(color: .black.opacity(0.25), radius: 8, y: 3)
            )
            .padding(32)
        }
    }
}
