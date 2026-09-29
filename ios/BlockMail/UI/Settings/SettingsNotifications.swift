import SwiftUI
import UIKit
import UserNotifications

/// „Benachrichtigungen“: Echtzeit-Status, Sparmodus, Push-Server (iOS),
/// iOS-Mitteilungsrechte und die Aktions-Knöpfe der Mitteilung.
struct SettingsNotificationSections: View {
    let ui: SettingsUIState

    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette
    @Environment(\.scenePhase) private var scenePhase

    @State private var serverURL = ""
    @State private var serverSecret = ""
    @State private var loadedServerFields = false
    @State private var authStatus: UNAuthorizationStatus?

    private static let allActions = ["reply", "read", "archive", "delete"]

    private func actionLabel(_ key: String) -> String {
        switch key {
        case "reply": return L("settings_notif_reply")
        case "read": return L("settings_notif_read")
        case "archive": return L("settings_swipe_archive")
        case "delete": return L("settings_swipe_delete")
        default: return key
        }
    }

    var body: some View {
        let push = PushService.shared
        let reg = PushRegistration.shared

        // Echtzeit-Verbindung solange die App offen ist + Sparmodus
        Section {
            VStack(alignment: .leading, spacing: 4) {
                Text(L("ios_push_status_title"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(palette.onSurfaceVariant)
                Text(prefs.pushMode == "eco" ? L("svc_push_stopped") : push.status)
                    .font(.subheadline)
            }
            Button {
                if prefs.pushMode != "eco" { PushService.shared.restart() }
                PushRegistration.shared.syncSoon()
                ui.snackbar.show(L("settings_push_restarted"))
            } label: {
                Label(L("settings_push_restart"), systemImage: "arrow.clockwise")
            }
            SettingsToggleRow(
                title: L("settings_eco_title"), desc: L("ios_eco_desc"),
                isOn: Binding(
                    get: { prefs.pushMode == "eco" },
                    set: { eco in
                        prefs.pushMode = eco ? "eco" : "push"
                        if eco {
                            PushService.shared.stop()
                            ui.snackbar.show(L("settings_eco_on_snack"))
                        } else {
                            PushService.shared.start()
                            ui.snackbar.show(L("settings_push_on_snack"))
                        }
                        // Der Push-Server meldet das Gerät im Sparmodus ab
                        PushRegistration.shared.syncSoon()
                    }
                )
            )
        } header: {
            SettingsSectionHeader(title: L("settings_push_title"), icon: "arrow.triangle.2.circlepath",
                                  subtitle: L("settings_push_subtitle"))
        }

        // iOS: eigener Push-Server (APNs) + Mitteilungsrechte
        Section {
            SettingsHint(L("ios_push_server_hint"))
                .onAppear {
                    if !loadedServerFields {
                        serverURL = prefs.pushServerURL
                        serverSecret = prefs.pushServerSecret
                        loadedServerFields = true
                    }
                }
                .task(id: scenePhase) { await refreshAuthStatus() }
            TextField(L("ios_push_server"), text: $serverURL)
                .keyboardType(.URL)
                .textContentType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { saveServer() }
            SecureField(L("ios_push_server_secret"), text: $serverSecret)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { saveServer() }

            HStack(spacing: 8) {
                if reg.busy {
                    ProgressView()
                } else {
                    Image(systemName: reg.isRegistered ? "checkmark.circle.fill" : "exclamationmark.circle")
                        .foregroundStyle(reg.isRegistered ? palette.primary : palette.onSurfaceVariant)
                }
                Text(reg.isRegistered
                     ? L("ios_push_registered_since", SettingsFormat.dateTime(prefs.pushRegisteredAt))
                     : L("ios_push_not_registered"))
                    .font(.subheadline)
            }
            if let err = reg.lastError {
                Text(L("ios_push_error", err))
                    .font(.caption)
                    .foregroundStyle(palette.error)
            }
            Button {
                saveServer()
            } label: {
                Label(L("ios_push_register"), systemImage: "antenna.radiowaves.left.and.right")
            }
            .disabled(reg.busy || serverURL.trimmingCharacters(in: .whitespaces).isEmpty || prefs.pushMode == "eco")

            // Mitteilungsrechte
            switch authStatus {
            case .some(.denied):
                Text(L("ios_push_denied"))
                    .font(.subheadline)
                    .foregroundStyle(palette.error)
            case .some(.notDetermined):
                Button {
                    Task { @MainActor in
                        _ = await Notifier.requestAuthorization()
                        await refreshAuthStatus()
                    }
                } label: {
                    Label(L("ios_notif_allow"), systemImage: "bell.badge")
                }
            case .some:
                Text(L("ios_notif_allowed"))
                    .font(.subheadline)
                    .foregroundStyle(palette.onSurfaceVariant)
            case .none:
                EmptyView()
            }
            Button {
                if let url = URL(string: UIApplication.openSettingsURLString) {
                    UIApplication.shared.open(url)
                }
            } label: {
                Label(L("ios_open_settings"), systemImage: "gear")
            }
        } header: {
            SettingsSectionHeader(title: L("ios_push_title"), icon: "bell.badge")
        }

        // Aktions-Knöpfe der Mitteilung
        Section {
            SettingsHint(L("ios_notif_buttons_desc"))
            let selected = prefs.notifActions
            let ordered = selected + Self.allActions.filter { !selected.contains($0) }
            ForEach(ordered, id: \.self) { key in
                let checked = selected.contains(key)
                let idx = selected.firstIndex(of: key)
                HStack(spacing: 12) {
                    Button {
                        toggleAction(key, on: !checked)
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: checked ? "checkmark.square.fill" : "square")
                                .foregroundStyle(checked ? palette.primary : palette.outline)
                            Text(actionLabel(key)).foregroundStyle(palette.onSurface)
                        }
                    }
                    Spacer()
                    if let idx {
                        Button {
                            moveAction(from: idx, by: -1)
                        } label: {
                            Image(systemName: "chevron.up")
                        }
                        .disabled(idx == 0)
                        .accessibilityLabel(L("ios_notif_move_up"))
                        Button {
                            moveAction(from: idx, by: 1)
                        } label: {
                            Image(systemName: "chevron.down")
                        }
                        .disabled(idx >= selected.count - 1)
                        .accessibilityLabel(L("ios_notif_move_down"))
                    }
                }
                .buttonStyle(.borderless)
            }
        } header: {
            Text(L("settings_notif_buttons"))
        }
    }

    // MARK: Aktionen

    private func saveServer() {
        let url = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        prefs.pushServerURL = url
        prefs.pushServerSecret = serverSecret
        ui.snackbar.show(L("ios_push_saved"))
        guard !url.isEmpty else { return }
        Task { @MainActor in
            await PushRegistration.shared.requestRemotePush()
            await PushRegistration.shared.sync()
            await refreshAuthStatus()
        }
    }

    private func refreshAuthStatus() async {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        authStatus = settings.authorizationStatus
    }

    private func toggleAction(_ key: String, on: Bool) {
        var list = prefs.notifActions
        if on {
            if list.count >= 3 {
                ui.snackbar.show(L("settings_notif_max3"))
                return
            }
            list.append(key)
        } else {
            list.removeAll { $0 == key }
        }
        prefs.setNotifActions(list)
        Notifier.registerCategories()
    }

    private func moveAction(from i: Int, by dir: Int) {
        var list = prefs.notifActions
        let j = i + dir
        guard list.indices.contains(i), list.indices.contains(j) else { return }
        list.swapAt(i, j)
        prefs.setNotifActions(list)
        Notifier.registerCategories()
    }
}

// MARK: - Absender-Regeln

/// Stumm / Blockiert / VIP samt „Nur VIP benachrichtigen“.
struct SettingsRulesSections: View {
    @Environment(Prefs.self) private var prefs
    @Environment(MailRepository.self) private var repo

    private var suggestions: [String] {
        var seen = Set<String>()
        var out: [String] = []
        for m in repo.messages where m.fromAddress.contains("@") {
            let a = m.fromAddress.lowercased()
            if seen.insert(a).inserted { out.append(a) }
        }
        for a in prefs.knownRecipients().keys.sorted() where seen.insert(a).inserted {
            out.append(a)
        }
        return out
    }

    var body: some View {
        let sugg = suggestions
        Section {
            SenderListEditor(title: L("settings_muted_title"), description: L("settings_muted_desc"),
                             entries: prefs.muted, suggestions: sugg,
                             onAdd: { prefs.addMuted($0); PushRegistration.shared.syncSoon() },
                             onRemove: { prefs.removeMuted($0); PushRegistration.shared.syncSoon() })
        } header: {
            SettingsSectionHeader(title: L("settings_sender_rules_title"), icon: "hand.raised",
                                  subtitle: L("settings_sender_rules_subtitle"))
        }
        Section {
            SenderListEditor(title: L("settings_blocked_title"), description: L("settings_blocked_desc"),
                             entries: prefs.blocked, suggestions: sugg,
                             onAdd: { prefs.addBlocked($0); PushRegistration.shared.syncSoon() },
                             onRemove: { prefs.removeBlocked($0); PushRegistration.shared.syncSoon() })
        }
        Section {
            SenderListEditor(title: L("settings_vip_title"), description: L("settings_vip_desc"),
                             entries: prefs.vip, suggestions: sugg,
                             onAdd: { prefs.addVip($0); PushRegistration.shared.syncSoon() },
                             onRemove: { prefs.removeVip($0); PushRegistration.shared.syncSoon() })
            SettingsToggleRow(
                title: L("settings_vip_only"), desc: L("settings_vip_only_desc"),
                isOn: Binding(
                    get: { prefs.vipOnlyNotifications },
                    set: { prefs.vipOnlyNotifications = $0; PushRegistration.shared.syncSoon() }
                )
            )
        }
    }
}

/// Liste von Absender-Adressen mit Entfernen, Eingabe und Vorschlägen
/// (Port von `SenderListSection`).
struct SenderListEditor: View {
    let title: String
    let description: String
    let entries: Set<String>
    let suggestions: [String]
    let onAdd: (String) -> Void
    let onRemove: (String) -> Void

    @Environment(\.palette) private var palette
    @State private var input = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.primary)
            SettingsHint(description)
        }
        if entries.isEmpty {
            SettingsHint(L("settings_no_entries"))
        }
        ForEach(entries.sorted(), id: \.self) { addr in
            HStack {
                Text(addr).font(.subheadline).lineLimit(1).truncationMode(.middle)
                Spacer()
                Button {
                    onRemove(addr)
                } label: {
                    Image(systemName: "xmark.circle")
                        .foregroundStyle(palette.onSurfaceVariant)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel(L("settings_remove"))
            }
        }
        HStack {
            TextField(L("settings_email_address"), text: $input)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { add() }
            Button(L("settings_add")) { add() }
                .buttonStyle(.borderless)
                .disabled(!input.contains("@"))
        }
        let available = Array(suggestions.filter { !entries.contains($0) }.prefix(30))
        Menu {
            if available.isEmpty {
                Text(L("settings_no_suggestions"))
            } else {
                ForEach(available, id: \.self) { addr in
                    Button(addr) { onAdd(addr) }
                }
            }
        } label: {
            Label(L("settings_pick_known"), systemImage: "person.crop.circle.badge.plus")
        }
    }

    private func add() {
        let v = input.trimmingCharacters(in: .whitespaces)
        guard v.contains("@") else { return }
        onAdd(v)
        input = ""
    }
}

// MARK: - Kontakte

/// Bekannte Empfänger (Adressvorschläge beim Verfassen).
struct SettingsContactsSection: View {
    let ui: SettingsUIState

    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette

    @State private var newAddr = ""
    @State private var newName = ""

    var body: some View {
        let _ = ui.listsVersion
        let known = prefs.knownRecipients()
        let addresses = known.keys.sorted()
        Section {
            SettingsHint(L("settings_contacts_desc"))
            TextField(L("settings_email_address"), text: $newAddr)
                .keyboardType(.emailAddress)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            HStack {
                TextField(L("settings_contact_name"), text: $newName)
                Button(L("settings_add")) {
                    prefs.addKnownRecipients([(newAddr.trimmingCharacters(in: .whitespaces),
                                               newName.trimmingCharacters(in: .whitespaces))])
                    newAddr = ""
                    newName = ""
                    ui.listsVersion += 1
                }
                .buttonStyle(.borderless)
                .disabled(!newAddr.contains("@"))
            }
            if addresses.isEmpty {
                SettingsHint(L("settings_contacts_empty"))
            }
            ForEach(addresses, id: \.self) { address in
                let name = known[address] ?? ""
                HStack(spacing: 10) {
                    SenderAvatar(name: name.isEmpty ? address : name, address: address, size: 34)
                    VStack(alignment: .leading, spacing: 1) {
                        if !name.isEmpty {
                            Text(name).font(.subheadline).lineLimit(1)
                        }
                        Text(address)
                            .font(.caption)
                            .foregroundStyle(palette.onSurfaceVariant)
                            .lineLimit(1)
                    }
                    Spacer()
                    Button {
                        prefs.removeKnownRecipient(address)
                        ui.listsVersion += 1
                    } label: {
                        Image(systemName: "xmark.circle")
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    .buttonStyle(.borderless)
                    .accessibilityLabel(L("settings_contact_remove"))
                }
            }
        } header: {
            SettingsSectionHeader(title: L("settings_contacts_title"), icon: "person.crop.rectangle.stack",
                                  subtitle: L("settings_contacts_subtitle"))
        }
    }
}
