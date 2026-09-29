import SwiftUI

/// Wurzelansicht (wird von den UI-Portierungen ersetzt).
struct RootView: View {
    @Environment(MailRepository.self) private var repo

    var body: some View {
        NavigationStack {
            List(repo.messages) { m in
                VStack(alignment: .leading) {
                    Text(m.from).bold(!m.seen)
                    Text(m.subject).foregroundStyle(.secondary)
                }
            }
            .navigationTitle(repo.currentFolder.label)
            .refreshable { await repo.refresh() }
        }
    }
}
