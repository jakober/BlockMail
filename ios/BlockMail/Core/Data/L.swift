import Foundation

/// Übersetzter Text zum Schlüssel (dieselben Schlüssel wie die Android-
/// Ressourcen `R.string.*`). Formatargumente wie bei `String(format:)`.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = NSLocalizedString(key, comment: "")
    return args.isEmpty ? format : String(format: format, locale: Locale.current, arguments: args)
}

/// Ist die Gerätesprache Deutsch? (Wie `deviceIsGerman` in der Android-App.)
var deviceIsGerman: Bool {
    (Locale.preferredLanguages.first ?? "de").hasPrefix("de")
}
