import WidgetKit
import SwiftUI

/// Homescreen-Widget mit den neuesten Mails (Platzhalter, wird ausgebaut).
struct MailEntry: TimelineEntry {
    let date: Date
    let mails: [MailMessage]
}

struct MailProvider: TimelineProvider {
    func placeholder(in context: Context) -> MailEntry { MailEntry(date: Date(), mails: []) }
    func getSnapshot(in context: Context, completion: @escaping (MailEntry) -> Void) {
        completion(MailEntry(date: Date(), mails: []))
    }
    func getTimeline(in context: Context, completion: @escaping (Timeline<MailEntry>) -> Void) {
        completion(Timeline(entries: [MailEntry(date: Date(), mails: [])], policy: .never))
    }
}

struct BlockMailWidgetView: View {
    let entry: MailEntry
    var body: some View {
        Text("BlockMail").containerBackground(.fill.tertiary, for: .widget)
    }
}

@main
struct BlockMailWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "BlockMailWidget", provider: MailProvider()) { entry in
            BlockMailWidgetView(entry: entry)
        }
        .configurationDisplayName("BlockMail")
    }
}
