import SwiftUI
import UIKit

/// Gold für den Wichtig-Stern — bewusst außerhalb des Farbschemas.
let starGold = Color(argb: 0xFFF5A623)

extension Color {
    init(argb: UInt32) {
        self.init(.sRGB,
                  red: Double((argb >> 16) & 0xFF) / 255,
                  green: Double((argb >> 8) & 0xFF) / 255,
                  blue: Double(argb & 0xFF) / 255,
                  opacity: Double((argb >> 24) & 0xFF) / 255)
    }

    /// Mischung zweier Farben (wie Compose `lerp`).
    func mix(with other: Color, _ t: Double) -> Color {
        let a = UIColor(self).rgba, b = UIColor(other).rgba
        return Color(.sRGB, red: a.0 + (b.0 - a.0) * t, green: a.1 + (b.1 - a.1) * t,
                     blue: a.2 + (b.2 - a.2) * t, opacity: a.3 + (b.3 - a.3) * t)
    }
}

extension UIColor {
    var rgba: (Double, Double, Double, Double) {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        return (Double(r), Double(g), Double(b), Double(a))
    }
}

/// Farbrollen wie Material 3 (`MaterialTheme.colorScheme`), damit die
/// portierten Bildschirme dieselben Farben benutzen können.
struct Palette {
    var primary: Color
    var onPrimary: Color
    var primaryContainer: Color
    var onPrimaryContainer: Color
    var secondary: Color
    var secondaryContainer: Color
    var onSecondaryContainer: Color
    var tertiary: Color
    /// Kräftige Grundfarbe des Schemas (auch im Dunkelmodus nicht aufgehellt).
    var accent: Color
    var dark: Bool

    var background: Color { dark ? Color(argb: 0xFF121316) : Color(argb: 0xFFFBF8FD) }
    var surface: Color { background }
    var surfaceContainer: Color { dark ? Color(argb: 0xFF1E1F23) : Color(argb: 0xFFEFEDF1) }
    var surfaceContainerHigh: Color { dark ? Color(argb: 0xFF292A2E) : Color(argb: 0xFFE9E7EC) }
    var surfaceVariant: Color { dark ? Color(argb: 0xFF44464F) : Color(argb: 0xFFE1E2EC) }
    var onSurface: Color { dark ? Color(argb: 0xFFE3E2E6) : Color(argb: 0xFF1B1B1F) }
    var onSurfaceVariant: Color { dark ? Color(argb: 0xFFC5C6D0) : Color(argb: 0xFF44464F) }
    var outline: Color { dark ? Color(argb: 0xFF8F9099) : Color(argb: 0xFF757780) }
    var outlineVariant: Color { dark ? Color(argb: 0xFF44464F) : Color(argb: 0xFFC5C6D0) }
    var error: Color { dark ? Color(argb: 0xFFFFB4AB) : Color(argb: 0xFFBA1A1A) }
    var onError: Color { dark ? Color(argb: 0xFF690005) : .white }
    var errorContainer: Color { dark ? Color(argb: 0xFF93000A) : Color(argb: 0xFFFFDAD6) }
    var onErrorContainer: Color { dark ? Color(argb: 0xFFFFDAD6) : Color(argb: 0xFF410002) }
}

/// Ein Farbschema (Hell + Dunkel) — Port von `SchemeDef`.
struct SchemeDef: Identifiable {
    let id: String
    let labelKey: String
    let preview: Color
    let light: Palette
    let dark: Palette

    var label: String { L(labelKey) }

    static func make(_ id: String, _ labelKey: String, primary: UInt32, primaryDark: UInt32,
                     container: UInt32, onContainer: UInt32, containerDark: UInt32,
                     onContainerDark: UInt32) -> SchemeDef {
        make(id, labelKey, primary: Color(argb: primary), primaryDark: Color(argb: primaryDark),
             container: Color(argb: container), onContainer: Color(argb: onContainer),
             containerDark: Color(argb: containerDark), onContainerDark: Color(argb: onContainerDark))
    }

    static func make(_ id: String, _ labelKey: String, primary: Color, primaryDark: Color,
                     container: Color, onContainer: Color, containerDark: Color,
                     onContainerDark: Color) -> SchemeDef {
        SchemeDef(
            id: id, labelKey: labelKey, preview: primary,
            light: Palette(primary: primary, onPrimary: .white, primaryContainer: container,
                           onPrimaryContainer: onContainer, secondary: primary, secondaryContainer: container,
                           onSecondaryContainer: onContainer, tertiary: primary, accent: primary, dark: false),
            dark: Palette(primary: primaryDark, onPrimary: Color(argb: 0xFF10131A), primaryContainer: containerDark,
                          onPrimaryContainer: onContainerDark, secondary: primaryDark,
                          secondaryContainer: containerDark, onSecondaryContainer: onContainerDark,
                          tertiary: primaryDark, accent: primary, dark: true))
    }

    static let all: [SchemeDef] = [
        SchemeDef(
            id: "klarmail", labelKey: "scheme_default", preview: Color(argb: 0xFFEE5F0F),
            light: Palette(primary: Color(argb: 0xFFD9530A), onPrimary: .white,
                           primaryContainer: Color(argb: 0xFFFFDCC7), onPrimaryContainer: Color(argb: 0xFF351000),
                           secondary: Color(argb: 0xFF9C6F00), secondaryContainer: Color(argb: 0xFFFFE9AE),
                           onSecondaryContainer: Color(argb: 0xFF302400), tertiary: Color(argb: 0xFFF2C230),
                           accent: Color(argb: 0xFFD9530A), dark: false),
            dark: Palette(primary: Color(argb: 0xFFFFB68B), onPrimary: Color(argb: 0xFF541F00),
                          primaryContainer: Color(argb: 0xFF9A3B00), onPrimaryContainer: Color(argb: 0xFFFFDCC7),
                          secondary: Color(argb: 0xFFF2C230), secondaryContainer: Color(argb: 0xFF5C4300),
                          onSecondaryContainer: Color(argb: 0xFFFFE9AE), tertiary: Color(argb: 0xFFF2C230),
                          accent: Color(argb: 0xFFD9530A), dark: true)),
        make("ozean", "scheme_ocean", primary: 0xFF2F5FD0, primaryDark: 0xFFAEC6FF, container: 0xFFDBE1FF,
             onContainer: 0xFF00174B, containerDark: 0xFF10448F, onContainerDark: 0xFFDBE1FF),
        make("wald", "scheme_forest", primary: 0xFF2E6B3F, primaryDark: 0xFF95D5A2, container: 0xFFB1F1BC,
             onContainer: 0xFF00210C, containerDark: 0xFF15522A, onContainerDark: 0xFFB1F1BC),
        make("violett", "scheme_violet", primary: 0xFF6B4FA8, primaryDark: 0xFFD0BCFF, container: 0xFFE9DDFF,
             onContainer: 0xFF22005D, containerDark: 0xFF503786, onContainerDark: 0xFFE9DDFF),
        make("sonne", "scheme_sunset", primary: 0xFFB4491F, primaryDark: 0xFFFFB59A, container: 0xFFFFDBCE,
             onContainer: 0xFF390C00, containerDark: 0xFF8A3313, onContainerDark: 0xFFFFDBCE),
        make("mono", "scheme_mono", primary: 0xFF3C3C43, primaryDark: 0xFFC9C9D0, container: 0xFFE2E2E9,
             onContainer: 0xFF17171C, containerDark: 0xFF47474E, onContainerDark: 0xFFE2E2E9)
    ]

    /// Schema aus frei gewählter Akzentfarbe.
    static func custom(_ base: Color) -> SchemeDef {
        make("custom", "settings_custom_color", primary: base, primaryDark: base.mix(with: .white, 0.45),
             container: base.mix(with: .white, 0.82), onContainer: base.mix(with: .black, 0.72),
             containerDark: base.mix(with: .black, 0.45), onContainerDark: base.mix(with: .white, 0.82))
    }

    static func current(_ prefs: Prefs) -> SchemeDef {
        if prefs.colorScheme == "custom" { return custom(Color(argb: UInt32(bitPattern: Int32(truncatingIfNeeded: prefs.customColor)))) }
        return all.first { $0.id == prefs.colorScheme } ?? all[0]
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = SchemeDef.all[0].light
}

extension EnvironmentValues {
    /// Aktuelle Farbrollen (`MaterialTheme.colorScheme`-Ersatz).
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

/// Wendet Farbschema, Hell/Dunkel und Schriftgröße an (Port von `KlarMailTheme`).
struct BlockMailTheme: ViewModifier {
    @Environment(Prefs.self) private var prefs
    @Environment(\.colorScheme) private var system

    func body(content: Content) -> some View {
        let def = SchemeDef.current(prefs)
        let forced: ColorScheme? = prefs.darkMode == "light" ? .light : (prefs.darkMode == "dark" ? .dark : nil)
        let dark = (forced ?? system) == .dark
        let palette = dark ? def.dark : def.light
        return content
            .environment(\.palette, palette)
            .tint(palette.primary)
            .preferredColorScheme(forced)
            .dynamicTypeSize(Self.typeSize(prefs.fontScalePercent))
    }

    static func typeSize(_ percent: Int) -> DynamicTypeSize {
        switch percent {
        case ..<85: return .xSmall
        case ..<95: return .small
        case ..<105: return .large
        case ..<115: return .xLarge
        default: return .xxLarge
        }
    }
}

extension View {
    func blockMailTheme() -> some View { modifier(BlockMailTheme()) }
}
