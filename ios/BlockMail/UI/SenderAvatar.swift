import SwiftUI
import UIKit

/// Findet und lädt Absender-Logos (Port von `SenderIcon.kt`): Marken-Logo-
/// Dienst, DuckDuckGo-Favicon, Standard-Favicon — sonst Initialen-Kreis.
actor SenderIconLoader {
    static let shared = SenderIconLoader()

    static let freemailDomains: Set<String> = [
        "gmail.com", "googlemail.com", "outlook.com", "outlook.de", "hotmail.com", "hotmail.de",
        "live.com", "live.de", "msn.com", "yahoo.com", "yahoo.de", "gmx.de", "gmx.net", "gmx.at",
        "gmx.ch", "web.de", "icloud.com", "me.com", "mac.com", "t-online.de", "freenet.de", "aol.com",
        "mail.de", "mail.com", "posteo.de", "proton.me", "protonmail.com", "tutanota.com", "tuta.io"
    ]

    static func candidates(for domain: String) -> [String] {
        ["https://logo.clearbit.com/\(domain)?size=256",
         "https://icons.duckduckgo.com/ip3/\(domain).ico",
         "https://\(domain)/favicon.ico"]
    }

    private var cache: [String: UIImage] = [:]
    private var failed: Set<String> = []
    private var inflight: [String: Task<UIImage?, Never>] = [:]

    private let session: URLSession = {
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 4
        c.requestCachePolicy = .returnCacheDataElseLoad
        return URLSession(configuration: c)
    }()

    func image(for domain: String) async -> UIImage? {
        guard !domain.isEmpty, !Self.freemailDomains.contains(domain) else { return nil }
        if let img = cache[domain] { return img }
        if failed.contains(domain) { return nil }
        if let t = inflight[domain] { return await t.value }
        let session = self.session
        let t = Task<UIImage?, Never> {
            for s in Self.candidates(for: domain) {
                guard let url = URL(string: s),
                      let (data, resp) = try? await session.data(from: url),
                      let http = resp as? HTTPURLResponse, (200..<300).contains(http.statusCode),
                      (http.value(forHTTPHeaderField: "Content-Type") ?? "").hasPrefix("image"),
                      let img = UIImage(data: data), img.size.width >= 16 else { continue }
                return img
            }
            return nil
        }
        inflight[domain] = t
        let img = await t.value
        inflight[domain] = nil
        if let img { cache[domain] = img } else { failed.insert(domain) }
        return img
    }

    func cached(_ domain: String) -> UIImage? { cache[domain] }
}

/// Absender-Avatar mit Logo oder Initialen-Kreis.
struct SenderAvatar: View {
    let name: String
    let address: String
    var size: CGFloat = 44
    var tint: Color? = nil

    @Environment(\.palette) private var palette
    @State private var image: UIImage?

    private var domain: String {
        address.split(separator: "@").last.map { $0.lowercased().trimmingCharacters(in: .whitespaces) } ?? ""
    }

    var body: some View {
        ZStack {
            if let image {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .frame(width: size, height: size)
                    .clipShape(RoundedRectangle(cornerRadius: size * 0.23))
            } else {
                Circle()
                    .fill(tint ?? palette.primary)
                    .frame(width: size, height: size)
                    .overlay(
                        Text(name.first.map { String($0).uppercased() } ?? "?")
                            .font(.system(size: size * 0.42, weight: .medium))
                            .foregroundStyle(palette.onPrimary)
                    )
            }
        }
        .frame(width: size, height: size)
        .task(id: domain) {
            image = await SenderIconLoader.shared.image(for: domain)
        }
    }
}
