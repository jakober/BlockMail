import Foundation
import AuthenticationServices
import CryptoKit
import UIKit

/// Google-OAuth (Port von `GoogleAuth.kt`): Konto-Auswahl im System-Browser-
/// fenster (ASWebAuthenticationSession, PKCE), danach IMAP/SMTP per XOAUTH2.
///
/// iOS braucht eine eigene OAuth-Client-ID vom Typ „iOS“ in der Google Cloud
/// Console (gebunden an die Bundle-ID). Sie wird über `ios/Config.xcconfig`
/// (GOOGLE_IOS_CLIENT_ID) in die Info.plist geschrieben.
enum GoogleAuth {

    struct SignInResult {
        let email: String?
        let refreshToken: String?
        let accessToken: String
        let expiresIn: Int64
    }

    static var clientID: String {
        (Bundle.main.object(forInfoDictionaryKey: "GoogleIOSClientID") as? String)?
            .trimmingCharacters(in: .whitespaces) ?? ""
    }

    static var isConfigured: Bool {
        clientID.hasSuffix(".apps.googleusercontent.com")
    }

    /// Umgekehrte Client-ID als Rücksprung-Schema.
    private static var redirectScheme: String {
        let id = clientID.replacingOccurrences(of: ".apps.googleusercontent.com", with: "")
        return "com.googleusercontent.apps." + id
    }

    private static var redirectURI: String { redirectScheme + ":/oauth2redirect" }

    // MARK: Anmeldung

    @MainActor
    static func signIn(loginHint: String? = nil) async throws -> SignInResult {
        guard isConfigured else {
            throw MailNetError.authFailed(L("err_google_no_client_id"))
        }
        let verifier = randomString(64)
        let challenge = Data(SHA256.hash(data: Data(verifier.utf8)))
            .base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        let state = randomString(24)
        var comps = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        comps.queryItems = [
            URLQueryItem(name: "client_id", value: clientID),
            URLQueryItem(name: "redirect_uri", value: redirectURI),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "scope", value: "openid email https://mail.google.com/"),
            URLQueryItem(name: "prompt", value: "select_account consent"),
            URLQueryItem(name: "access_type", value: "offline"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "state", value: state)
        ]
        if let loginHint, !loginHint.isEmpty {
            comps.queryItems?.append(URLQueryItem(name: "login_hint", value: loginHint))
        }
        let callback = try await WebAuth.run(url: comps.url!, scheme: redirectScheme)
        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        if let err = items.first(where: { $0.name == "error" })?.value {
            throw MailNetError.authFailed(err)
        }
        guard items.first(where: { $0.name == "state" })?.value == state,
              let code = items.first(where: { $0.name == "code" })?.value else {
            throw MailNetError.authFailed(L("err_auth_oauth"))
        }
        let json = try await tokenRequest([
            "client_id": clientID,
            "grant_type": "authorization_code",
            "code": code,
            "redirect_uri": redirectURI,
            "code_verifier": verifier
        ])
        guard let access = json["access_token"] as? String else {
            throw MailNetError.authFailed(L("err_auth_oauth"))
        }
        return SignInResult(
            email: emailFromIdToken(json["id_token"] as? String),
            refreshToken: json["refresh_token"] as? String,
            accessToken: access,
            expiresIn: (json["expires_in"] as? NSNumber)?.int64Value ?? 3600
        )
    }

    /// E-Mail-Adresse aus dem ID-Token (JWT) lesen.
    static func emailFromIdToken(_ idToken: String?) -> String? {
        guard let idToken, idToken.split(separator: ".").count > 1 else { return nil }
        var payload = String(idToken.split(separator: ".")[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while payload.count % 4 != 0 { payload += "=" }
        guard let data = Data(base64Encoded: payload),
              let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let mail = o["email"] as? String, !mail.isEmpty else { return nil }
        return mail
    }

    // MARK: Token-Erneuerung

    private static func tokenRequest(_ form: [String: String]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        req.httpMethod = "POST"
        req.timeoutInterval = 30
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        req.httpBody = form.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
            .joined(separator: "&").data(using: .utf8)
        let (data, resp) = try await URLSession.shared.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        guard (200..<300).contains(code) else {
            if code == 400 || code == 401 { throw TokenError.invalidGrant }
            throw TokenError.http(code)
        }
        return json
    }

    enum TokenError: LocalizedError {
        case invalidGrant
        case http(Int)
        case notSignedIn
        var errorDescription: String? {
            switch self {
            case .invalidGrant: return L("err_google_expired")
            case .http(let c): return L("err_google_refresh", c)
            case .notSignedIn: return L("err_google_not_signed_in")
            }
        }
    }

    private static let cache = TokenCache()

    /// Gültiges Zugriffstoken des aktiven Kontos; erneuert bei Bedarf.
    static func freshAccessToken() async throws -> String {
        let prefs = Prefs.shared
        let now = nowMs()
        let cached = prefs.accessToken
        if !cached.isEmpty && prefs.accessTokenExpiry > now + 120_000 { return cached }
        let rt = prefs.refreshToken
        guard !rt.isEmpty else { throw TokenError.notSignedIn }
        do {
            let json = try await tokenRequest([
                "client_id": clientID, "grant_type": "refresh_token", "refresh_token": rt
            ])
            guard let token = json["access_token"] as? String else { throw TokenError.http(0) }
            prefs.accessToken = token
            prefs.accessTokenExpiry = now + ((json["expires_in"] as? NSNumber)?.int64Value ?? 3600) * 1000
            return token
        } catch TokenError.invalidGrant {
            prefs.accessToken = ""
            prefs.accessTokenExpiry = 0
            throw TokenError.invalidGrant
        }
    }

    /// Zugriffstoken für ein beliebiges gespeichertes Google-Konto.
    static func freshAccessToken(for refreshToken: String) async throws -> String {
        guard !refreshToken.isEmpty else { throw TokenError.notSignedIn }
        if refreshToken == Prefs.shared.refreshToken { return try await freshAccessToken() }
        let now = nowMs()
        if let (token, expiry) = await cache.get(refreshToken), expiry > now + 120_000 { return token }
        let json = try await tokenRequest([
            "client_id": clientID, "grant_type": "refresh_token", "refresh_token": refreshToken
        ])
        guard let token = json["access_token"] as? String else { throw TokenError.http(0) }
        let expiry = now + ((json["expires_in"] as? NSNumber)?.int64Value ?? 3600) * 1000
        await cache.set(refreshToken, token, expiry)
        return token
    }

    static func signOut() {
        let p = Prefs.shared
        p.refreshToken = ""
        p.accessToken = ""
        p.accessTokenExpiry = 0
        p.authMethod = "password"
    }

    private static func randomString(_ n: Int) -> String {
        let chars = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return String((0..<n).map { _ in chars.randomElement()! })
    }
}

private actor TokenCache {
    private var map: [String: (String, Int64)] = [:]
    func get(_ k: String) -> (String, Int64)? { map[k] }
    func set(_ k: String, _ t: String, _ e: Int64) { map[k] = (t, e) }
}

/// Führt eine ASWebAuthenticationSession aus (auch für Microsoft-OAuth nutzbar).
@MainActor
final class WebAuth: NSObject, ASWebAuthenticationPresentationContextProviding {
    private static var current: WebAuth?
    private var session: ASWebAuthenticationSession?

    static func run(url: URL, scheme: String) async throws -> URL {
        let helper = WebAuth()
        current = helper
        defer { current = nil }
        return try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: url, callbackURLScheme: scheme) { cb, err in
                if let cb { cont.resume(returning: cb) }
                else { cont.resume(throwing: err ?? MailNetError.authFailed(L("err_auth_oauth"))) }
            }
            s.presentationContextProvider = helper
            s.prefersEphemeralWebBrowserSession = false
            helper.session = s
            s.start()
        }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes
                .compactMap { ($0 as? UIWindowScene)?.keyWindow }
                .first ?? ASPresentationAnchor()
        }
    }
}
