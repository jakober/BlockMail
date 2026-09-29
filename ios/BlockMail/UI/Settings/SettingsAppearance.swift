import SwiftUI

/// „Darstellung“ und „Posteingang“ (Farbschemata, Hell/Dunkel, schlichtes
/// Design, Schriftgröße, Layout, Konversationen, Fokus-Blöcke, Wischgesten).
struct SettingsAppearanceSections: View {
    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette

    private static let swipeActions = ["delete", "archive", "read", "snooze"]

    private static func swipeLabel(_ id: String) -> String {
        switch id {
        case "archive": return L("settings_swipe_archive")
        case "read": return L("settings_swipe_read")
        case "snooze": return L("settings_swipe_snooze")
        default: return L("settings_swipe_delete")
        }
    }

    private var customColorBinding: Binding<Color> {
        Binding(
            get: { SettingsFormat.color(prefs.customColor) },
            set: { c in
                prefs.customColor = SettingsFormat.argb(c)
                if prefs.colorScheme != "custom" { prefs.colorScheme = "custom" }
            }
        )
    }

    var body: some View {
        Section {
            Picker(selection: prefs.settingsBinding(\.darkMode)) {
                Text(L("settings_darkmode_system")).tag("system")
                Text(L("settings_darkmode_light")).tag("light")
                Text(L("settings_darkmode_dark")).tag("dark")
            } label: {
                EmptyView()
            }
            .pickerStyle(.segmented)
            .listRowSeparator(.hidden)

            Text(L("settings_color_scheme"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.primary)
            ForEach(SchemeDef.all) { scheme in
                Button {
                    prefs.colorScheme = scheme.id
                } label: {
                    HStack(spacing: 12) {
                        Circle().fill(scheme.preview).frame(width: 22, height: 22)
                        Text(scheme.label).foregroundStyle(palette.onSurface)
                        Spacer()
                        if prefs.colorScheme == scheme.id {
                            Image(systemName: "checkmark").foregroundStyle(palette.primary)
                        }
                    }
                }
            }
            // Frei wählbare Akzentfarbe mit dem System-Farbwähler
            HStack(spacing: 12) {
                Button {
                    prefs.colorScheme = "custom"
                } label: {
                    HStack(spacing: 12) {
                        Circle().fill(SettingsFormat.color(prefs.customColor)).frame(width: 22, height: 22)
                        Text(L("settings_custom_color")).foregroundStyle(palette.onSurface)
                        Spacer()
                        if prefs.colorScheme == "custom" {
                            Image(systemName: "checkmark").foregroundStyle(palette.primary)
                        }
                    }
                }
                .buttonStyle(.borderless)
                ColorPicker(L("ios_color_pick"), selection: customColorBinding, supportsOpacity: false)
                    .labelsHidden()
            }

            SettingsToggleRow(title: L("settings_plain_design"), desc: L("settings_plain_design_desc"),
                              isOn: prefs.settingsBinding(\.plainDesign))

            Stepper(value: prefs.settingsBinding(\.fontScalePercent), in: 80...120, step: 10) {
                HStack {
                    Text(L("ios_settings_font_size"))
                    Spacer()
                    Text("\(prefs.fontScalePercent) %")
                        .foregroundStyle(palette.onSurfaceVariant)
                        .monospacedDigit()
                }
            }
        } header: {
            SettingsSectionHeader(title: L("settings_appearance_title"), icon: "paintpalette",
                                  subtitle: L("settings_appearance_subtitle"))
        }

        Section {
            Text(L("settings_display"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(palette.primary)
            layoutRow("list", title: L("settings_layout_list_title"), desc: L("settings_layout_list_desc"))
            layoutRow("blocks", title: L("settings_layout_blocks_title"), desc: L("settings_layout_blocks_desc"))
            layoutRow("blocks3", title: L("settings_layout_blocks3_title"), desc: L("settings_layout_blocks3_desc"))

            SettingsToggleRow(title: L("settings_conversation_view"), desc: L("settings_conversation_view_desc"),
                              isOn: prefs.settingsBinding(\.conversationView))
            SettingsToggleRow(title: L("inbox_focus_blocks"), desc: L("ios_focus_desc"),
                              isOn: prefs.settingsBinding(\.focusMode))

            VStack(alignment: .leading, spacing: 2) {
                Text(L("settings_swipe_gestures"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(palette.primary)
                SettingsHint(L("settings_swipe_desc"))
            }
            Picker(L("settings_swipe_left"), selection: prefs.settingsBinding(\.swipeLeftAction)) {
                ForEach(Self.swipeActions, id: \.self) { a in
                    Text(Self.swipeLabel(a)).tag(a)
                }
            }
            Picker(L("settings_swipe_right"), selection: prefs.settingsBinding(\.swipeRightAction)) {
                ForEach(Self.swipeActions, id: \.self) { a in
                    Text(Self.swipeLabel(a)).tag(a)
                }
            }
        } header: {
            SettingsSectionHeader(title: L("settings_inbox_title"), icon: "tray",
                                  subtitle: L("settings_inbox_subtitle"))
        }
    }

    private func layoutRow(_ id: String, title: String, desc: String) -> some View {
        Button {
            prefs.inboxLayout = id
        } label: {
            HStack(spacing: 12) {
                Image(systemName: prefs.inboxLayout == id ? "largecircle.fill.circle" : "circle")
                    .foregroundStyle(prefs.inboxLayout == id ? palette.primary : palette.outline)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).foregroundStyle(palette.onSurface)
                    SettingsHint(desc)
                }
            }
        }
    }
}
