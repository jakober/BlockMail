import SwiftUI

/// Empfängerzeile („AN:“, „CC:“, „BCC:“) mit einzeiligem Adressfeld.
struct ComposeRecipientRow<Trailing: View>: View {
    let label: String
    @Binding var value: String
    var focus: FocusState<ComposeModel.Field?>.Binding
    let field: ComposeModel.Field
    @ViewBuilder var trailing: () -> Trailing

    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 10) {
            Text(label)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(palette.onSurfaceVariant)
            TextField("", text: $value)
                .font(.body)
                .foregroundStyle(palette.onSurface)
                .keyboardType(.emailAddress)
                .textContentType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.next)
                .focused(focus, equals: field)
                .padding(.vertical, 10)
            trailing()
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 6)
    }
}

/// Vorschlagsliste bekannter Empfänger unterhalb eines Empfängerfeldes.
struct ComposeSuggestionList: View {
    let visible: Bool
    let input: String
    let contacts: [RecipientSuggestion]
    let onPick: (String) -> Void

    @Environment(\.palette) private var palette

    private var hits: [RecipientSuggestion] {
        guard visible else { return [] }
        let token = (input.components(separatedBy: ",").last ?? "").trimmingCharacters(in: .whitespaces)
        let alreadyUsed = Set(input.components(separatedBy: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() })
        // Leeres Feld: direkt die bekannten Kontakte anbieten; sonst passend filtern
        if token.isEmpty {
            return Array(contacts.filter { !alreadyUsed.contains($0.address) }.prefix(4))
        }
        return Array(contacts.filter { c in
            !alreadyUsed.contains(c.address) &&
                (c.address.range(of: token, options: .caseInsensitive) != nil ||
                    c.name.range(of: token, options: .caseInsensitive) != nil)
        }.prefix(6))
    }

    var body: some View {
        let list = hits
        if !list.isEmpty {
            VStack(spacing: 0) {
                ForEach(list) { c in
                    Button {
                        if let idx = input.lastIndex(of: ",") {
                            onPick(String(input[...idx]) + " " + c.address)
                        } else {
                            onPick(c.address)
                        }
                    } label: {
                        HStack(spacing: 12) {
                            SenderAvatar(name: c.name.isBlankText ? c.address : c.name, address: c.address, size: 32)
                            VStack(alignment: .leading, spacing: 1) {
                                if !c.name.isBlankText {
                                    Text(c.name)
                                        .font(.subheadline)
                                        .foregroundStyle(palette.onSurface)
                                        .lineLimit(1)
                                }
                                Text(c.address)
                                    .font(.caption)
                                    .foregroundStyle(palette.onSurfaceVariant)
                                    .lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.horizontal, 20)
                        .padding(.vertical, 8)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

/// Anhang-Chip (abwählbar) — Ersatz für `InputChip`.
struct ComposeAttachmentChip: View {
    let name: String
    let size: Int
    let selected: Bool
    let onRemove: () -> Void

    @Environment(\.palette) private var palette

    var body: some View {
        Button(action: onRemove) {
            HStack(spacing: 6) {
                Image(systemName: "paperclip")
                    .font(.caption)
                Text(name + (size > 0 ? L("compose_attachment_size_kb", size / 1024) : ""))
                    .font(.footnote)
                    .lineLimit(1)
                Image(systemName: "xmark")
                    .font(.caption2.weight(.bold))
                    .accessibilityLabel(L("compose_attachment_remove"))
            }
            .foregroundStyle(selected ? palette.onSecondaryContainer : palette.onSurfaceVariant)
            .padding(.horizontal, 10)
            .padding(.vertical, 7)
            .background(
                RoundedRectangle(cornerRadius: 8)
                    .fill(selected ? palette.secondaryContainer : Color.clear)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 8)
                    .stroke(selected ? Color.clear : palette.outline, lineWidth: 1)
            )
        }
        .buttonStyle(.plain)
    }
}

/// Knopf der Formatierungsleiste (aktiv = Primärfarbe).
struct ComposeFormatButton: View {
    let systemImage: String
    let description: String
    let active: Bool
    let action: () -> Void

    @Environment(\.palette) private var palette

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 18, weight: active ? .semibold : .regular))
                .foregroundStyle(active ? palette.primary : palette.onSurfaceVariant)
                .frame(width: 40, height: 40)
                .background(
                    RoundedRectangle(cornerRadius: 8)
                        .fill(active ? palette.primary.opacity(0.12) : Color.clear)
                )
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(description)
    }
}

/// „Später senden“: Schnellauswahl wie Android plus freie Wahl von Datum und Uhrzeit.
struct ComposeScheduleSheet: View {
    let onPick: (Int64) -> Void
    let onCancel: () -> Void

    @Environment(\.palette) private var palette
    @State private var custom = Date().addingTimeInterval(2 * 60 * 60)
    @State private var choices: [(String, Int64)] = ComposeModel.scheduleChoices()

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(L("compose_schedule_description"))
                        .font(.footnote)
                        .foregroundStyle(palette.onSurfaceVariant)
                    ForEach(choices.indices, id: \.self) { i in
                        Button {
                            onPick(choices[i].1)
                        } label: {
                            HStack {
                                Text(choices[i].0)
                                    .foregroundStyle(palette.primary)
                                Spacer()
                                Text(Date(ms: choices[i].1).formatted(date: .abbreviated, time: .shortened))
                                    .font(.footnote)
                                    .foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                    }
                }
                Section(L("compose_schedule_custom")) {
                    DatePicker(
                        L("compose_schedule_custom"),
                        selection: $custom,
                        in: Date()...,
                        displayedComponents: [.date, .hourAndMinute]
                    )
                    .datePickerStyle(.graphical)
                    .labelsHidden()
                    Button {
                        onPick(max(custom, Date().addingTimeInterval(60)).ms)
                    } label: {
                        Text(L("compose_schedule_confirm"))
                            .fontWeight(.semibold)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .navigationTitle(L("compose_send_later"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("compose_cancel"), action: onCancel)
                }
            }
        }
        .presentationDetents([.large])
    }
}

/// „Mail formulieren“-Dialog: Wunsch an die KI eingeben.
struct ComposePromptSheet: View {
    @Binding var text: String
    let onConfirm: () -> Void
    let onCancel: () -> Void

    @Environment(\.palette) private var palette
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 12) {
                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text(L("compose_prompt_placeholder"))
                            .foregroundStyle(palette.onSurfaceVariant)
                            .padding(.horizontal, 5)
                            .padding(.vertical, 8)
                            .allowsHitTesting(false)
                    }
                    TextEditor(text: $text)
                        .focused($focused)
                        .scrollContentBackground(.hidden)
                        .frame(minHeight: 120)
                }
                .padding(8)
                .background(RoundedRectangle(cornerRadius: 10).stroke(palette.outline, lineWidth: 1))
                Spacer(minLength: 0)
            }
            .padding(16)
            .navigationTitle(L("compose_prompt_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("compose_cancel"), action: onCancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("compose_prompt_confirm"), action: onConfirm)
                        .disabled(text.isBlankText)
                }
            }
            .onAppear { focused = true }
        }
        .presentationDetents([.medium, .large])
    }
}
