import WidgetKit
import SwiftUI

// Homescreen-/Sperrbildschirm-Widget: zeigt die neuesten Mails aus dem
// Posteingangs-Cache (Android: MailWidgetProvider + MailWidgetService).
// Das Widget-Target kennt nur MailMessage.swift und L.swift aus dem Kern –
// App-Gruppe, Dateiname und Farbschemata werden daher hier nachgebildet.

// MARK: - Daten

enum WidgetStore {
    static let groupId = "group.com.jakober.blockmail"

    static var defaults: UserDefaults? { UserDefaults(suiteName: groupId) }

    /// Wie `Prefs.inboxCacheFileName(for:)` im Kern.
    static func inboxCacheFileName(for accountEmail: String) -> String {
        let safe = accountEmail.trimmingCharacters(in: .whitespaces).lowercased()
            .replacingOccurrences(of: "[^a-z0-9@._-]", with: "_", options: .regularExpression)
        return safe.isEmpty ? "inbox_cache.json" : "inbox_cache_\(safe).json"
    }

    /// Liest den Posteingangs-Cache des aktiven Kontos (neueste zuerst).
    static func load(limit: Int = 20) -> MailEntry {
        let email = defaults?.string(forKey: "email")?.trimmingCharacters(in: .whitespaces) ?? ""
        let scheme = defaults?.string(forKey: "color_scheme") ?? "klarmail"
        let custom = defaults?.object(forKey: "custom_color") as? Int
        var mails: [MailMessage] = []
        if let dir = FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: groupId) {
            let url = dir.appendingPathComponent(inboxCacheFileName(for: email))
            if let data = try? Data(contentsOf: url) {
                mails = MailMessage.listFromJson(data)
            }
        }
        let unread = mails.filter { !$0.seen }.count
        let newest = Array(mails.sorted { $0.date > $1.date }.prefix(limit))
        return MailEntry(date: Date(), mails: newest, unread: unread, hasAccount: !email.isEmpty,
                         scheme: scheme, customColor: custom)
    }
}

struct MailEntry: TimelineEntry {
    let date: Date
    let mails: [MailMessage]
    let unread: Int
    let hasAccount: Bool
    let scheme: String
    let customColor: Int?

    static func sample() -> MailEntry {
        let now = nowMs()
        let mails = [
            MailMessage(uid: 3, subject: "Projekt-Update", from: "Anna Berger", fromAddress: "anna@example.com",
                        date: now - 5 * 60_000, seen: false),
            MailMessage(uid: 2, subject: "Rechnung März", from: "Stadtwerke", fromAddress: "info@example.com",
                        date: now - 90 * 60_000, seen: false),
            MailMessage(uid: 1, subject: "Wochenende?", from: "Max", fromAddress: "max@example.com",
                        date: now - 26 * 3_600_000, seen: true)
        ]
        return MailEntry(date: Date(), mails: mails, unread: 2, hasAccount: true, scheme: "klarmail",
                         customColor: nil)
    }
}

// MARK: - Links

enum WidgetLinks {
    static let inbox = URL(string: "blockmail://inbox")!
    static let compose = URL(string: "blockmail://compose")!

    static func mail(_ m: MailMessage) -> URL {
        var c = URLComponents()
        c.scheme = "blockmail"
        c.host = "mail"
        c.queryItems = [URLQueryItem(name: "uid", value: String(m.uid)),
                        URLQueryItem(name: "account", value: m.account)]
        return c.url ?? inbox
    }
}

// MARK: - Farben (Auszug aus SchemeDef der App)

enum WidgetTheme {
    private static func argb(_ v: UInt32) -> Color {
        Color(.sRGB,
              red: Double((v >> 16) & 0xFF) / 255,
              green: Double((v >> 8) & 0xFF) / 255,
              blue: Double(v & 0xFF) / 255,
              opacity: 1)
    }

    private static func mix(_ v: UInt32, with t: Double) -> Color {
        // Richtung Weiß aufhellen (für dunkle Darstellung eigener Farben)
        func ch(_ s: UInt32) -> Double {
            let c = Double(s & 0xFF) / 255
            return c + (1 - c) * t
        }
        return Color(.sRGB, red: ch(v >> 16), green: ch(v >> 8), blue: ch(v), opacity: 1)
    }

    static func primary(scheme: String, custom: Int?, dark: Bool) -> Color {
        switch scheme {
        case "ozean": return argb(dark ? 0xFFAEC6FF : 0xFF2F5FD0)
        case "wald": return argb(dark ? 0xFF95D5A2 : 0xFF2E6B3F)
        case "violett": return argb(dark ? 0xFFD0BCFF : 0xFF6B4FA8)
        case "sonne": return argb(dark ? 0xFFFFB59A : 0xFFB4491F)
        case "mono": return argb(dark ? 0xFFC9C9D0 : 0xFF3C3C43)
        case "custom":
            let v = UInt32(truncatingIfNeeded: custom ?? Int(Int32(bitPattern: 0xFFEE5F0F)))
            return dark ? mix(v, with: 0.45) : argb(v)
        default: return argb(dark ? 0xFFFFB68B : 0xFFD9530A)
        }
    }
}

// MARK: - Timeline

struct MailProvider: TimelineProvider {
    func placeholder(in context: Context) -> MailEntry { MailEntry.sample() }

    func getSnapshot(in context: Context, completion: @escaping (MailEntry) -> Void) {
        let entry = WidgetStore.load()
        completion(context.isPreview && entry.mails.isEmpty ? MailEntry.sample() : entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<MailEntry>) -> Void) {
        let entry = WidgetStore.load()
        let next = Calendar.current.date(byAdding: .minute, value: 15, to: Date()) ?? Date().addingTimeInterval(900)
        completion(Timeline(entries: [entry], policy: .after(next)))
    }
}

// MARK: - Ansichten

private func timeLabel(_ ms: Int64) -> String {
    let d = Date(ms: ms)
    let f = DateFormatter()
    f.locale = Locale.current
    if Calendar.current.isDateInToday(d) {
        f.setLocalizedDateFormatFromTemplate("HH:mm")
    } else {
        f.setLocalizedDateFormatFromTemplate("dM")
    }
    return f.string(from: d)
}

private func senderName(_ m: MailMessage) -> String {
    let n = m.from.trimmingCharacters(in: .whitespaces)
    if !n.isEmpty { return n }
    return m.fromAddress.isEmpty ? L("mail_unknown_sender") : m.fromAddress
}

private func subjectText(_ m: MailMessage) -> String {
    let s = m.subject.trimmingCharacters(in: .whitespaces)
    return s.isEmpty ? L("mail_no_subject") : s
}

struct MailRow: View {
    let mail: MailMessage
    let accent: Color
    var compact = false

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                if !mail.seen {
                    Circle().fill(accent).frame(width: 6, height: 6).widgetAccentable()
                }
                Text(senderName(mail))
                    .font(.system(size: compact ? 13 : 14, weight: mail.seen ? .regular : .bold))
                    .foregroundStyle(mail.seen ? Color.primary : accent)
                    .lineLimit(1)
                Spacer(minLength: 4)
                Text(timeLabel(mail.date))
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Text(subjectText(mail))
                .font(.system(size: compact ? 12 : 13, weight: mail.seen ? .regular : .semibold))
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct WidgetHeader: View {
    let entry: MailEntry
    let accent: Color
    var showCompose = true

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "tray.fill")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(accent)
                .widgetAccentable()
            Text(L("svc_widget_title"))
                .font(.system(size: 15, weight: .bold))
                .foregroundStyle(accent)
                .lineLimit(1)
                .widgetAccentable()
            if entry.unread > 0 {
                Text(L("svc_widget_unread", entry.unread))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            if showCompose {
                Link(destination: WidgetLinks.compose) {
                    HStack(spacing: 3) {
                        Image(systemName: "square.and.pencil")
                        Text(L("widget_compose"))
                    }
                    .font(.system(size: 12, weight: .semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(accent))
                    .widgetAccentable()
                }
                .accessibilityLabel(L("compose_title_new"))
            }
        }
    }
}

struct EmptyState: View {
    let entry: MailEntry
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "tray")
                .font(.system(size: 22))
                .foregroundStyle(.secondary)
            Text(entry.hasAccount ? L("svc_widget_empty") : L("widget_no_account"))
                .font(.system(size: 13))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Klein: Ungelesen-Zähler + neueste Mail (kleine Widgets kennen nur eine Tipp-URL).
struct SmallView: View {
    let entry: MailEntry
    let accent: Color

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 4) {
                Image(systemName: "tray.fill").foregroundStyle(accent).widgetAccentable()
                Text(L("svc_widget_title"))
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(accent)
                    .lineLimit(1)
                    .widgetAccentable()
            }
            Text("\(entry.unread)")
                .font(.system(size: 30, weight: .bold, design: .rounded))
                .foregroundStyle(entry.unread > 0 ? accent : Color.secondary)
                .widgetAccentable()
            Spacer(minLength: 0)
            if let m = entry.mails.first {
                MailRow(mail: m, accent: accent, compact: true)
            } else {
                Text(entry.hasAccount ? L("svc_widget_empty") : L("widget_no_account"))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(entry.mails.first.map(WidgetLinks.mail) ?? WidgetLinks.inbox)
    }
}

/// Mittel/Groß: Kopf mit „Neu“ + Liste; jede Zeile öffnet die Mail.
struct ListView: View {
    let entry: MailEntry
    let accent: Color
    let rows: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            WidgetHeader(entry: entry, accent: accent)
            if entry.mails.isEmpty {
                EmptyState(entry: entry)
            } else {
                VStack(alignment: .leading, spacing: 5) {
                    ForEach(Array(entry.mails.prefix(rows).enumerated()), id: \.element.id) { idx, m in
                        if idx > 0 { Divider().opacity(0.5) }
                        Link(destination: WidgetLinks.mail(m)) {
                            MailRow(mail: m, accent: accent)
                        }
                    }
                }
                Spacer(minLength: 0)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .widgetURL(WidgetLinks.inbox)
    }
}

/// Sperrbildschirm (rechteckig): Zähler + neueste Mail.
struct AccessoryView: View {
    let entry: MailEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack(spacing: 4) {
                Image(systemName: "envelope.fill")
                Text(entry.unread > 0 ? L("svc_widget_unread", entry.unread) : L("svc_widget_title"))
                    .font(.headline)
                    .lineLimit(1)
            }
            .widgetAccentable()
            if let m = entry.mails.first {
                Text(senderName(m))
                    .font(.system(size: 13, weight: m.seen ? .regular : .semibold))
                    .lineLimit(1)
                Text(subjectText(m))
                    .font(.system(size: 12))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            } else {
                Text(entry.hasAccount ? L("svc_widget_empty") : L("widget_no_account"))
                    .font(.system(size: 12))
                    .lineLimit(2)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .widgetURL(entry.mails.first.map(WidgetLinks.mail) ?? WidgetLinks.inbox)
    }
}

struct BlockMailWidgetView: View {
    let entry: MailEntry
    @Environment(\.widgetFamily) private var family
    @Environment(\.colorScheme) private var colorScheme

    private var accent: Color {
        WidgetTheme.primary(scheme: entry.scheme, custom: entry.customColor, dark: colorScheme == .dark)
    }

    var body: some View {
        content.containerBackground(for: .widget) {
            if family == .accessoryRectangular {
                Color.clear
            } else {
                Color(UIColor.systemBackground)
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch family {
        case .accessoryRectangular:
            AccessoryView(entry: entry)
        case .systemSmall:
            SmallView(entry: entry, accent: accent)
        case .systemLarge, .systemExtraLarge:
            ListView(entry: entry, accent: accent, rows: 7)
        default:
            ListView(entry: entry, accent: accent, rows: 3)
        }
    }
}

@main
struct BlockMailWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "BlockMailWidget", provider: MailProvider()) { entry in
            BlockMailWidgetView(entry: entry)
        }
        .configurationDisplayName(L("svc_widget_title"))
        .description(L("widget_description"))
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge, .accessoryRectangular])
    }
}
