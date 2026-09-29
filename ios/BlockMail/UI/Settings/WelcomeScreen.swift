import SwiftUI

/// Willkommens-Bildschirm beim allerersten Start (Port von `WelcomeScreen.kt`):
/// begrüßt neue Nutzer und führt direkt in den Einrichtungsassistenten.
struct WelcomeScreen: View {
    @Environment(AppNav.self) private var nav
    @Environment(Prefs.self) private var prefs
    @Environment(\.palette) private var palette

    init() {}

    private struct Feature: Identifiable {
        let icon: String
        let titleKey: String
        let textKey: String
        var id: String { titleKey }
    }

    private static let features: [Feature] = [
        Feature(icon: "square.grid.2x2.fill", titleKey: "welcome_feature_ask_title", textKey: "welcome_feature_ask_text"),
        Feature(icon: "shield.lefthalf.filled", titleKey: "welcome_feature_phishing_title", textKey: "welcome_feature_phishing_text"),
        Feature(icon: "dot.radiowaves.left.and.right", titleKey: "welcome_feature_radar_title", textKey: "welcome_feature_radar_text"),
        Feature(icon: "paperclip", titleKey: "welcome_feature_attachments_title", textKey: "welcome_feature_attachments_text"),
        Feature(icon: "signature", titleKey: "welcome_feature_sign_title", textKey: "welcome_feature_sign_text"),
        Feature(icon: "bell.badge.fill", titleKey: "welcome_feature_push_title", textKey: "welcome_feature_push_text"),
        Feature(icon: "tray.2.fill", titleKey: "welcome_feature_accounts_title", textKey: "welcome_feature_accounts_text"),
        Feature(icon: "sparkles", titleKey: "welcome_feature_ai_title", textKey: "welcome_feature_ai_text")
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Image("Logo")
                    .resizable()
                    .scaledToFit()
                    .frame(width: 96, height: 96)
                    .clipShape(RoundedRectangle(cornerRadius: 22))
                    .padding(.top, 12)
                Text(L("welcome_title"))
                    .font(.title.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .padding(.top, 20)
                Text(L("welcome_subtitle"))
                    .font(.body)
                    .foregroundStyle(palette.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.top, 8)

                Text(L("testflight_badge"))
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(palette.onPrimaryContainer)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(palette.primaryContainer))
                    .padding(.top, 14)

                VStack(alignment: .leading, spacing: 14) {
                    ForEach(Self.features) { f in
                        HStack(alignment: .center, spacing: 16) {
                            Image(systemName: f.icon)
                                .font(.title2)
                                .foregroundStyle(palette.primary)
                                .frame(width: 32)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(L(f.titleKey))
                                    .font(.subheadline.weight(.semibold))
                                Text(L(f.textKey))
                                    .font(.caption)
                                    .foregroundStyle(palette.onSurfaceVariant)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            Spacer(minLength: 0)
                        }
                    }
                }
                .padding(.top, 28)

                Button {
                    prefs.welcomeShown = true
                    if nav.path.last == .welcome { nav.pop() }
                    nav.push(.setup)
                } label: {
                    Text(L("welcome_setup"))
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 6)
                }
                .buttonStyle(.borderedProminent)
                .padding(.top, 32)

                Button(L("welcome_skip")) {
                    prefs.welcomeShown = true
                    if nav.path.last == .welcome { nav.pop() }
                }
                .padding(.top, 8)
            }
            .padding(.horizontal, 32)
            .padding(.vertical, 24)
        }
        .background(palette.background)
        .navigationBarBackButtonHidden(true)
        .toolbar(.hidden, for: .navigationBar)
    }
}
