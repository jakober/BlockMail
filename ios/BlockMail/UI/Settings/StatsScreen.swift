import SwiftUI

/// Statistik im BlockMail-Kachel-Stil (Port von `StatsScreen.kt`): Kennzahlen,
/// Wochentags-Verteilung, letzte vier Wochen und Top-Absender — berechnet aus
/// den aktuell geladenen Mails.
struct StatsScreen: View {
    @Environment(MailRepository.self) private var repo
    @Environment(\.palette) private var palette

    init() {}

    private struct TopSender: Identifiable {
        let name: String
        let address: String
        let count: Int
        var id: String { address.isEmpty ? name : address }
    }

    var body: some View {
        let messages = repo.messages
        let total = messages.count
        let unread = messages.filter { !$0.seen }.count
        let withAttachment = messages.filter { $0.hasAttachments }.count
        let weekdays = Self.weekdayCounts(messages)
        let weeks = Self.weekCounts(messages)
        let weeksSum = weeks.reduce(0, +)
        let top = Self.topSenders(messages)

        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text(L("stats_based_on", total))
                    .font(.caption)
                    .foregroundStyle(palette.onSurfaceVariant)

                HStack(spacing: 10) {
                    StatTile(label: L("stats_tile_loaded"), value: "\(total)")
                    StatTile(label: L("stats_tile_unread"), value: "\(unread)")
                }
                HStack(spacing: 10) {
                    StatTile(label: L("stats_tile_with_attachment"),
                             value: total > 0 ? "\(withAttachment * 100 / total) %" : "—")
                    StatTile(label: L("stats_tile_per_day"),
                             value: weeksSum > 0
                                ? String(format: "%.1f", locale: Locale.current, Double(weeksSum) / 28.0)
                                : "—")
                }

                StatsCard(title: L("stats_card_weekdays")) {
                    BarRow(values: weekdays, labels: Self.weekdayLabels())
                }

                StatsCard(title: L("stats_card_weeks")) {
                    // Älteste Woche links, aktuelle rechts
                    BarRow(values: Array(weeks.reversed()),
                           labels: [L("stats_week_3_ago"), L("stats_week_2_ago"),
                                    L("stats_week_last"), L("stats_week_this")])
                }

                StatsCard(title: L("stats_card_top_senders")) {
                    if top.isEmpty {
                        Text(L("stats_no_data"))
                            .font(.caption)
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    let maxCount = max(1, top.map { $0.count }.max() ?? 1)
                    ForEach(0..<top.count, id: \.self) { i in
                        let s = top[i]
                        if i > 0 {
                            Divider().overlay(palette.outlineVariant.opacity(0.4))
                        }
                        HStack(spacing: 10) {
                            SenderAvatar(name: s.name.isEmpty ? s.address : s.name, address: s.address, size: 34)
                            VStack(alignment: .leading, spacing: 3) {
                                Text(s.name.isEmpty ? s.address : s.name)
                                    .font(.subheadline.weight(.semibold))
                                    .lineLimit(1)
                                // Kleiner Anteils-Balken im Kachel-Stil
                                GeometryReader { geo in
                                    RoundedRectangle(cornerRadius: 3)
                                        .fill(palette.primary.opacity(0.75))
                                        .frame(width: max(4, geo.size.width * CGFloat(s.count) / CGFloat(maxCount)),
                                               height: 5)
                                }
                                .frame(height: 5)
                            }
                            Text("\(s.count)")
                                .font(.headline)
                                .foregroundStyle(palette.primary)
                        }
                        .padding(.vertical, 6)
                    }
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .background(palette.background)
        .navigationTitle(L("stats_title"))
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: Berechnung

    /// Mails je Wochentag (Mo–So).
    private static func weekdayCounts(_ messages: [MailMessage]) -> [Int] {
        var counts = [Int](repeating: 0, count: 7)
        let cal = Calendar.current
        for m in messages {
            // Calendar: Sonntag=1 … Samstag=7 → Index Montag=0 … Sonntag=6
            let wd = cal.component(.weekday, from: m.dateValue)
            counts[(wd + 5) % 7] += 1
        }
        return counts
    }

    /// Aufkommen der letzten 4 Wochen (Index 0 = aktuelle Woche).
    private static func weekCounts(_ messages: [MailMessage]) -> [Int] {
        let now = nowMs()
        let week: Int64 = 7 * 24 * 60 * 60 * 1000
        return (0..<4).map { w -> Int in
            let lo = Int64(w) * week, hi = Int64(w + 1) * week
            return messages.filter { m in
                let age = now - m.date
                return age >= lo && age < hi
            }.count
        }
    }

    private static func topSenders(_ messages: [MailMessage]) -> [TopSender] {
        var groups: [String: (String, String, Int)] = [:]
        var order: [String] = []
        for m in messages {
            let key = m.fromAddress.isEmpty ? m.from : m.fromAddress.lowercased()
            if let g = groups[key] {
                groups[key] = (g.0, g.1, g.2 + 1)
            } else {
                groups[key] = (m.from, m.fromAddress, 1)
                order.append(key)
            }
        }
        return order.compactMap { k -> TopSender? in
            guard let g = groups[k] else { return nil }
            return TopSender(name: g.0, address: g.1, count: g.2)
        }
        .sorted { $0.count > $1.count }
        .prefix(8)
        .map { $0 }
    }

    /// Kurze Wochentagsnamen der Systemsprache in Reihenfolge Mo–So.
    private static func weekdayLabels() -> [String] {
        let symbols = Calendar.current.shortWeekdaySymbols // So, Mo, … Sa
        guard symbols.count == 7 else { return ["Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"] }
        return (1...6).map { symbols[$0] } .map { $0.replacingOccurrences(of: ".", with: "") }
            + [symbols[0].replacingOccurrences(of: ".", with: "")]
    }
}

/// Kleine Kennzahl-Kachel im Block-Stil.
private struct StatTile: View {
    let label: String
    let value: String
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(.title.weight(.bold))
                .foregroundStyle(palette.primary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(.caption)
                .foregroundStyle(palette.onSurfaceVariant)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(palette.surfaceContainer))
    }
}

/// Abgerundete Karte für einen Statistik-Abschnitt.
private struct StatsCard<Content: View>: View {
    let title: String
    let content: Content
    @Environment(\.palette) private var palette

    init(title: String, @ViewBuilder content: () -> Content) {
        self.title = title
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.headline)
                .foregroundStyle(palette.primary)
                .padding(.bottom, 10)
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(palette.surfaceContainer))
    }
}

/// Einfaches Balkendiagramm aus SwiftUI-Formen.
private struct BarRow: View {
    let values: [Int]
    let labels: [String]
    @Environment(\.palette) private var palette

    var body: some View {
        let maxV = max(1, values.max() ?? 0)
        HStack(alignment: .bottom, spacing: 8) {
            ForEach(0..<values.count, id: \.self) { i in
                let v = values[i]
                let frac = CGFloat(v) / CGFloat(maxV)
                VStack(spacing: 2) {
                    Text("\(v)")
                        .font(.caption2)
                        .foregroundStyle(palette.onSurfaceVariant)
                    UnevenRoundedRectangle(topLeadingRadius: 6, bottomLeadingRadius: 0,
                                           bottomTrailingRadius: 0, topTrailingRadius: 6)
                        .fill(palette.primary.opacity(0.45 + 0.55 * Double(frac)))
                        .frame(height: max(3, 84 * frac))
                    Text(i < labels.count ? labels[i] : "")
                        .font(.caption2)
                        .foregroundStyle(palette.onSurfaceVariant)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                        .padding(.top, 2)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 120, alignment: .bottom)
        .frame(maxWidth: .infinity)
    }
}
