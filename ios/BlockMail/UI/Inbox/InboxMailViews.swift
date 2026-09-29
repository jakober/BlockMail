import SwiftUI
import UIKit

// Mail-Zeilen und -Kacheln des Posteingangs (Port von MailRow, MailRowContent,
// MailBlock, MiniBlocksLogo, SectionHeader und SwipeableMailBlock).

/// Abschnitts-Überschrift mit feinen Linien links und rechts.
struct InboxSectionHeader: View {
    let text: String
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: 10) {
            Rectangle().fill(palette.outlineVariant).frame(width: 64, height: 1)
            Text(text)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(palette.primary)
                .lineLimit(1)
            Rectangle().fill(palette.outlineVariant).frame(height: 1)
        }
        .padding(.horizontal, 10)
        .padding(.top, 16)
        .padding(.bottom, 6)
    }
}

/// Hintergrund-Verlauf der Mail-Einträge (wie Android bgBrush).
private func inboxCardFill(_ palette: Palette, seen: Bool, selected: Bool, plain: Bool) -> AnyShapeStyle {
    let isLight = !palette.dark
    if selected { return AnyShapeStyle(palette.primaryContainer) }
    if !seen {
        return AnyShapeStyle(LinearGradient(
            colors: [palette.secondaryContainer.opacity(isLight ? 0.9 : 0.55), palette.surfaceContainer],
            startPoint: .top, endPoint: .bottom))
    }
    if plain { return AnyShapeStyle(palette.background) }
    return AnyShapeStyle(LinearGradient(
        colors: [isLight ? palette.surfaceContainerHigh : palette.surfaceContainer, palette.background],
        startPoint: .top, endPoint: .bottom))
}

/// Kleine Status-Symbole (Phishing, beantwortet, Stern, Anhang).
private struct InboxStatusIcons: View {
    let mail: MailMessage
    let size: CGFloat
    let spacing: CGFloat
    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(spacing: spacing) {
            if prefs.isPhishing(mail.account, mail.uid) {
                Image(systemName: "exclamationmark.circle.fill")
                    .font(.system(size: size))
                    .foregroundStyle(palette.error)
                    .accessibilityLabel(L("inbox_phishing_warning"))
            }
            if InboxAnswered.isAnswered(mail) {
                Image(systemName: "arrowshape.turn.up.left.fill")
                    .font(.system(size: size))
                    .foregroundStyle(palette.primary)
                    .accessibilityLabel(L("inbox_answered_mail"))
            }
            if mail.flagged {
                Image(systemName: "star.fill")
                    .font(.system(size: size))
                    .foregroundStyle(starGold)
                    .accessibilityLabel(L("inbox_starred_mail"))
            }
            if mail.hasAttachments {
                Image(systemName: "paperclip")
                    .font(.system(size: size))
                    .foregroundStyle(palette.onSurfaceVariant)
                    .accessibilityLabel(L("inbox_has_attachment"))
            }
        }
    }
}

/// Konto-Farbe (nur im Sammel-Posteingang „Alle Konten“).
@MainActor
private func inboxAccountColor(_ mail: MailMessage, prefs: Prefs, repo: MailRepository) -> Color? {
    guard repo.unified else { return nil }
    _ = prefs.accountColorsVersion
    let acc = mail.account.trimmingCharacters(in: .whitespaces).isEmpty ? prefs.email : mail.account
    return prefs.accountColor(acc).map { Color(inboxArgbInt: $0) }
}

/// Zähler-Plakette für Konversations-Bündel.
private struct InboxThreadBadge: View {
    let count: Int
    @Environment(\.palette) private var palette
    var body: some View {
        Text("\(count)")
            .font(.caption2)
            .foregroundStyle(palette.onSecondaryContainer)
            .padding(.horizontal, 6)
            .padding(.vertical, 1)
            .background(RoundedRectangle(cornerRadius: 8).fill(palette.secondaryContainer))
    }
}

/// Vorschauzeile: nil = noch nicht geladen (leer), "" = „Kein Inhalt“.
private struct InboxSnippetText: View {
    let snippet: String?
    let lines: Int
    @Environment(\.palette) private var palette
    var body: some View {
        let empty = snippet != nil && snippet!.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        let text = empty ? L("inbox_no_content") : (snippet ?? "")
        // Feste Zeilenzahl: Platzhalter-Zeilen halten alle Einträge gleich hoch
        let padded = text + String(repeating: "\n ", count: max(0, lines - 1))
        Text(lines > 1 ? padded : (text.isEmpty ? " " : text))
            .font(.system(size: 11))
            .italic(empty)
            .foregroundStyle(palette.onSurfaceVariant.opacity(0.65))
            .lineLimit(lines)
    }
}

// MARK: - Listen-Zeile

struct InboxMailRow: View {
    let mail: MailMessage
    var selected: Bool = false
    var selectionMode: Bool = false
    var threadCount: Int? = nil

    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo
    @Environment(\.palette) private var palette

    var body: some View {
        let plain = prefs.plainDesign
        let accountColor = inboxAccountColor(mail, prefs: prefs, repo: repo)
        HStack(alignment: .center, spacing: 0) {
            if selectionMode && selected {
                Circle().fill(palette.primary)
                    .frame(width: 44, height: 44)
                    .overlay(Image(systemName: "checkmark").font(.headline).foregroundStyle(palette.onPrimary))
            } else {
                SenderAvatar(name: mail.from, address: mail.fromAddress, size: 44)
            }
            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 6) {
                    Text(mail.from)
                        .font(.body.weight(mail.seen ? .regular : .bold))
                        .foregroundStyle(palette.onSurface)
                        .lineLimit(1)
                    if let n = threadCount, n > 1 { InboxThreadBadge(count: n) }
                }
                Text(mail.subject)
                    .font(.subheadline.weight(mail.seen ? .regular : .medium))
                    .foregroundStyle(mail.seen ? palette.onSurfaceVariant : palette.onSurface)
                    .lineLimit(1)
                InboxSnippetText(snippet: mail.snippet, lines: 1)
                    .padding(.top, 2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 14)
            VStack(alignment: .trailing, spacing: 2) {
                HStack(spacing: 3) {
                    if let c = accountColor {
                        Circle().fill(c).frame(width: 9, height: 9).padding(.trailing, 1)
                    }
                    InboxStatusIcons(mail: mail, size: 11, spacing: 3)
                    Text(InboxFormat.mailDate(mail.date))
                        .font(.caption2.weight(mail.seen ? .regular : .bold))
                        .foregroundStyle(mail.seen ? palette.onSurfaceVariant : palette.primary)
                        .lineLimit(1)
                }
                Text(InboxFormat.mailTime(mail.date))
                    .font(.caption2)
                    .foregroundStyle(palette.onSurfaceVariant)
                if !mail.seen {
                    Circle().fill(palette.primary).frame(width: 9, height: 9).padding(.top, 4)
                }
            }
            .padding(.leading, 8)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, plain ? 7 : 12)
        .background(RoundedRectangle(cornerRadius: 16)
            .fill(inboxCardFill(palette, seen: mail.seen, selected: selected, plain: plain)))
        .overlay(alignment: .leading) {
            // Balken vorne: nur Ungelesene in Primärfarbe
            if !mail.seen && !selected {
                Rectangle().fill(palette.primary).frame(width: 4)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .contentShape(RoundedRectangle(cornerRadius: 16))
    }
}

// MARK: - Kachel

/// Mini-Ausgabe des BlockMail-Logos: vier kleine Blöcke.
struct InboxMiniBlocksLogo: View {
    let color: Color
    var body: some View {
        let alphas: [Double] = [1, 0.7, 0.45, 0.85]
        VStack(spacing: 1.5) {
            ForEach([0, 2], id: \.self) { start in
                HStack(spacing: 1.5) {
                    ForEach(start...(start + 1), id: \.self) { i in
                        RoundedRectangle(cornerRadius: 1.5)
                            .fill(color.opacity(alphas[i]))
                            .frame(width: 5, height: 5)
                    }
                }
            }
        }
    }
}

struct InboxMailBlock: View {
    let mail: MailMessage
    var selected: Bool = false
    var selectionMode: Bool = false
    var threadCount: Int? = nil
    var threadExpanded: Bool = false
    var inThread: Bool = false
    var compact: Bool = false

    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo
    @Environment(\.palette) private var palette

    private var borderColor: Color? {
        if selected { return palette.primary }
        if let n = threadCount, n > 1, threadExpanded { return palette.primary.opacity(0.6) }
        if !mail.seen { return palette.primary.opacity(0.35) }
        if inThread { return palette.secondary.opacity(0.45) }
        if prefs.plainDesign { return palette.outlineVariant }
        return nil
    }

    private var borderWidth: CGFloat {
        if selected { return 1.5 }
        if let n = threadCount, n > 1, threadExpanded { return 1.5 }
        if !mail.seen || inThread { return 1 }
        return 0.75
    }

    var body: some View {
        let accountColor = inboxAccountColor(mail, prefs: prefs, repo: repo)
        let chipColor: Color? = accountColor ?? ((!mail.seen && !selected) ? palette.primary : nil)
        let frame: CGFloat = compact ? 34 : 42
        VStack(alignment: .leading, spacing: 0) {
            HStack(alignment: .center, spacing: 0) {
                ZStack {
                    if selectionMode && selected {
                        Circle().fill(palette.primary)
                            .frame(width: compact ? 32 : 40, height: compact ? 32 : 40)
                            .overlay(Image(systemName: "checkmark")
                                .font(.system(size: compact ? 14 : 17, weight: .semibold))
                                .foregroundStyle(palette.onPrimary))
                    } else if !mail.seen {
                        RoundedRectangle(cornerRadius: compact ? 11 : 13)
                            .stroke(palette.primary.opacity(0.55), lineWidth: 2)
                            .frame(width: frame, height: frame)
                        SenderAvatar(name: mail.from, address: mail.fromAddress, size: compact ? 26 : 34)
                    } else {
                        SenderAvatar(name: mail.from, address: mail.fromAddress, size: compact ? 32 : 40)
                    }
                }
                .frame(width: frame, height: frame)
                Spacer(minLength: 4)
                if compact {
                    VStack(alignment: .trailing, spacing: 0) {
                        HStack(spacing: 3) {
                            InboxStatusIcons(mail: mail, size: 10, spacing: 3)
                            if let c = chipColor { InboxMiniBlocksLogo(color: c).padding(.leading, 1) }
                        }
                        .frame(height: 12)
                        Text(InboxFormat.mailDate(mail.date))
                            .font(.caption2.weight(mail.seen ? .regular : .bold))
                            .foregroundStyle(mail.seen ? palette.onSurfaceVariant : palette.primary)
                            .lineLimit(1)
                            .fixedSize()
                        Text(InboxFormat.mailTime(mail.date))
                            .font(.caption2)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                } else {
                    InboxStatusIcons(mail: mail, size: 13, spacing: 6)
                        .padding(.trailing, 6)
                    VStack(alignment: .trailing, spacing: 0) {
                        HStack(spacing: 6) {
                            if let c = chipColor { InboxMiniBlocksLogo(color: c) }
                            Text(InboxFormat.mailDate(mail.date))
                                .font(.caption2.weight(mail.seen ? .regular : .bold))
                                .foregroundStyle(mail.seen ? palette.onSurfaceVariant : palette.primary)
                                .lineLimit(1)
                        }
                        Text(InboxFormat.mailTime(mail.date))
                            .font(.caption2)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                }
            }
            HStack(spacing: 4) {
                if inThread {
                    Image(systemName: "arrow.turn.down.right")
                        .font(.system(size: 12))
                        .foregroundStyle(palette.secondary)
                        .accessibilityLabel(L("inbox_thread_part"))
                }
                Text(mail.from)
                    .font(.subheadline.weight(mail.seen ? .medium : .semibold))
                    .foregroundStyle(palette.onSurface)
                    .lineLimit(1)
                if let n = threadCount, n > 1 {
                    InboxThreadBadge(count: n).padding(.leading, 2)
                    Image(systemName: threadExpanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 11, weight: .semibold))
                        .foregroundStyle(palette.onSurfaceVariant)
                        .accessibilityLabel(threadExpanded ? L("inbox_thread_collapse") : L("inbox_thread_expand"))
                }
            }
            .padding(.top, 10)
            // Betreff immer zweizeilig (gleich hohe Kacheln)
            Text(mail.subject + "\n ")
                .font(.subheadline.weight(mail.seen ? .regular : .medium))
                .foregroundStyle(mail.seen ? palette.onSurfaceVariant : palette.onSurface)
                .lineLimit(2)
                .padding(.top, 2)
            if !compact {
                InboxSnippetText(snippet: mail.snippet, lines: 3)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(compact ? 10 : 14)
        .background(RoundedRectangle(cornerRadius: 18)
            .fill(inboxCardFill(palette, seen: mail.seen, selected: selected, plain: prefs.plainDesign)))
        .overlay {
            if let c = borderColor {
                RoundedRectangle(cornerRadius: 18).strokeBorder(c, lineWidth: borderWidth)
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .contentShape(RoundedRectangle(cornerRadius: 18))
    }
}

// MARK: - Wischgesten (Kacheln)

/// Wischbarer Eintrag für die Kachel-Ansicht: ab 30 % Wischstrecke wird die
/// Aktion ausgelöst (mit Vibration an der Schwelle), danach schnappt der
/// Eintrag zurück. Im Auswahlmodus keine Wischgesten.
struct InboxSwipeContainer<Content: View>: View {
    let enabled: Bool
    let right: InboxSwipeSpec
    let left: InboxSwipeSpec
    var cornerRadius: CGFloat = 18
    var stacked: Bool = true
    let onTap: () -> Void
    let onLongPress: () -> Void
    @ViewBuilder let content: () -> Content

    @Environment(\.palette) private var palette
    @State private var offset: CGFloat = 0
    @State private var width: CGFloat = 1
    /// 0 = unentschieden, 1 = waagerecht (Wischen), 2 = senkrecht (Scrollen)
    @State private var axis = 0
    @State private var reached = false

    private static var threshold: CGFloat { 0.30 }

    var body: some View {
        let fraction = min(1, abs(offset) / max(width, 1))
        let spec: InboxSwipeSpec? = offset > 0 ? right : (offset < 0 ? left : nil)
        ZStack {
            if let spec {
                swipeBackground(spec, fraction: fraction, end: offset < 0)
            }
            content()
                .offset(x: offset)
                .onTapGesture { onTap() }
                .onLongPressGesture(minimumDuration: 0.45) {
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                    onLongPress()
                }
        }
        .clipShape(RoundedRectangle(cornerRadius: cornerRadius))
        .background(GeometryReader { g in
            Color.clear
                .onAppear { width = g.size.width }
                .onChange(of: g.size.width) { _, w in width = w }
        })
        .simultaneousGesture(drag, including: enabled ? .all : .subviews)
    }

    private var drag: some Gesture {
        DragGesture(minimumDistance: 18, coordinateSpace: .local)
            .onChanged { v in
                if axis == 0 {
                    axis = abs(v.translation.width) > abs(v.translation.height) * 1.4 ? 1 : 2
                }
                guard axis == 1 else { return }
                offset = v.translation.width
                let hit = abs(offset) / max(width, 1) >= Self.threshold
                if hit && !reached {
                    reached = true
                    UIImpactFeedbackGenerator(style: .medium).impactOccurred()
                } else if !hit && abs(offset) / max(width, 1) < Self.threshold - 0.04 {
                    reached = false
                }
            }
            .onEnded { _ in
                let wasHorizontal = axis == 1
                let fraction = abs(offset) / max(width, 1)
                let dir = offset
                axis = 0
                reached = false
                withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) { offset = 0 }
                guard wasHorizontal, fraction >= Self.threshold else { return }
                if dir > 0 { right.action() } else { left.action() }
            }
    }

    @ViewBuilder
    private func swipeBackground(_ spec: InboxSwipeSpec, fraction: CGFloat, end: Bool) -> some View {
        let ramp = min(1, fraction / Self.threshold)
        let hit = fraction >= Self.threshold
        let bg: Color = spec.destructive
            ? (hit ? palette.error : palette.errorContainer.opacity(ramp))
            : (hit ? palette.primary : palette.primaryContainer.opacity(ramp))
        let fg: Color = spec.destructive
            ? (hit ? palette.onError : palette.onErrorContainer.opacity(0.4 + 0.6 * ramp))
            : (hit ? palette.onPrimary : palette.onPrimaryContainer.opacity(0.4 + 0.6 * ramp))
        ZStack(alignment: end ? .trailing : .leading) {
            bg
            if stacked {
                VStack(alignment: end ? .trailing : .leading, spacing: 3) {
                    Image(systemName: spec.icon)
                    Text(spec.label.replacingOccurrences(of: " ", with: "\n"))
                        .font(.system(size: 10))
                        .multilineTextAlignment(end ? .trailing : .leading)
                        .lineLimit(4)
                }
                .foregroundStyle(fg)
                .padding(.horizontal, 12)
            } else {
                HStack(spacing: 12) {
                    if !end { Image(systemName: spec.icon) }
                    Text(spec.label).font(.subheadline.weight(.medium))
                    if end { Image(systemName: spec.icon) }
                }
                .foregroundStyle(fg)
                .padding(.horizontal, 24)
            }
        }
    }
}
