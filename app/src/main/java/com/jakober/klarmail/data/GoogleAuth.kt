package com.jakober.klarmail.data

import android.net.Uri
import android.util.Base64
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Google-OAuth: Konto-Auswahlfenster beim Verbinden, danach IMAP/SMTP-Anmeldung
 * per XOAUTH2-Zugriffstoken (automatisch erneuert über das Refresh-Token).
 */
object GoogleAuth {

    const val CLIENT_ID =
        "313846853654-qv9mb3t22r8v9u8uhj5ee3jl0mu0sftu.apps.googleusercontent.com"

    private const val REDIRECT_URI =
        "com.googleusercontent.apps.313846853654-qv9mb3t22r8v9u8uhj5ee3jl0mu0sftu:/oauth2redirect"

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun buildAuthRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder(
            serviceConfig,
            CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        )
            .setScopes("openid", "email", "https://mail.google.com/")
            .setPrompt("select_account")
            .build()

    /** E-Mail-Adresse aus dem ID-Token (JWT) lesen. */
    fun emailFromIdToken(idToken: String?): String? {
        if (idToken.isNullOrBlank()) return null
        return try {
            val payload = idToken.split(".")[1]
            val decoded = String(
                Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            )
            JSONObject(decoded).optString("email").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Liefert ein gültiges Zugriffstoken; erneuert es bei Bedarf über das Refresh-Token.
     * Blockierend — nur von Hintergrund-Threads aufrufen.
     */
    fun freshAccessToken(): String {
        val now = System.currentTimeMillis()
        val cached = Prefs.accessToken
        if (cached.isNotBlank() && Prefs.accessTokenExpiry > now + 120_000) return cached

        val refreshToken = Prefs.refreshToken
        if (refreshToken.isBlank()) {
            throw IOException("Nicht bei Google angemeldet. Bitte in den Einstellungen neu verbinden.")
        }
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(form)
            .build()
        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                if (resp.code == 400 || resp.code == 401) {
                    // Refresh-Token ungültig/widerrufen → Neuanmeldung nötig
                    Prefs.accessToken = ""
                    Prefs.accessTokenExpiry = 0
                    throw IOException("Google-Anmeldung abgelaufen. Bitte in den Einstellungen neu verbinden.")
                }
                throw IOException("Google-Token konnte nicht erneuert werden (HTTP ${resp.code})")
            }
            val json = JSONObject(bodyText)
            val token = json.getString("access_token")
            Prefs.accessToken = token
            Prefs.accessTokenExpiry = now + json.optLong("expires_in", 3600) * 1000
            return token
        }
    }

    /** Token-Zwischenspeicher für weitere Konten (Refresh-Token → Token+Ablauf). */
    private val tokenCache = HashMap<String, Pair<String, Long>>()

    /**
     * Zugriffstoken für ein beliebiges gespeichertes Google-Konto (Sammel-
     * Posteingang): nutzt dessen Refresh-Token, gecacht im Arbeitsspeicher.
     * Blockierend — nur von Hintergrund-Threads aufrufen.
     */
    fun freshAccessTokenFor(refreshToken: String): String {
        if (refreshToken.isBlank()) {
            throw IOException("Google-Konto nicht angemeldet. Bitte in den Einstellungen verbinden.")
        }
        // Das aktive Konto nutzt den normalen (persistierten) Weg
        if (refreshToken == Prefs.refreshToken) return freshAccessToken()
        val now = System.currentTimeMillis()
        synchronized(tokenCache) {
            tokenCache[refreshToken]?.let { (token, expiry) ->
                if (expiry > now + 120_000) return token
            }
        }
        val form = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .build()
        val request = Request.Builder()
            .url("https://oauth2.googleapis.com/token")
            .post(form)
            .build()
        http.newCall(request).execute().use { resp ->
            val bodyText = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw IOException("Google-Token konnte nicht erneuert werden (HTTP ${resp.code})")
            }
            val json = JSONObject(bodyText)
            val token = json.getString("access_token")
            val expiry = now + json.optLong("expires_in", 3600) * 1000
            synchronized(tokenCache) { tokenCache[refreshToken] = token to expiry }
            return token
        }
    }

    fun signOut() {
        Prefs.refreshToken = ""
        Prefs.accessToken = ""
        Prefs.accessTokenExpiry = 0
        Prefs.authMethod = "password"
    }
}
