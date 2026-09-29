// Google-OAuth: Access-Token per Refresh-Token erneuern (iOS-Client-ID, ohne Secret).
import { log, maskEmail } from './log.js';

const TOKEN_URL = 'https://oauth2.googleapis.com/token';

/** @type {Map<string, {token: string, expires: number}>} */
const cache = new Map();

export class OAuthError extends Error {
    /** @param {string} message @param {boolean} permanent */
    constructor(message, permanent) {
        super(message);
        this.permanent = permanent;
    }
}

/**
 * Liefert ein gültiges Access-Token (zwischengespeichert bis kurz vor Ablauf).
 * @param {{email: string, refreshToken?: string, googleClientId?: string}} acc
 * @param {boolean} [force]
 * @returns {Promise<string>}
 */
export async function googleAccessToken(acc, force = false) {
    if (!acc.refreshToken || !acc.googleClientId) {
        throw new OAuthError('Refresh-Token oder Client-ID fehlt', true);
    }
    const key = `${acc.googleClientId}|${acc.refreshToken}`;
    const hit = cache.get(key);
    if (!force && hit && hit.expires - 120_000 > Date.now()) return hit.token;

    const body = new URLSearchParams({
        client_id: acc.googleClientId,
        refresh_token: acc.refreshToken,
        grant_type: 'refresh_token'
    });
    let res;
    try {
        res = await fetch(TOKEN_URL, {
            method: 'POST',
            headers: { 'content-type': 'application/x-www-form-urlencoded' },
            body,
            signal: AbortSignal.timeout(20_000)
        });
    } catch (err) {
        throw new OAuthError(`Google nicht erreichbar: ${err instanceof Error ? err.message : err}`, false);
    }
    /** @type {any} */
    let json = {};
    try {
        json = await res.json();
    } catch { /* kein JSON */ }
    if (!res.ok || !json.access_token) {
        const code = json.error || `HTTP ${res.status}`;
        // invalid_grant = Token widerrufen/abgelaufen → erst nach neuer Registrierung sinnvoll
        const permanent = code === 'invalid_grant' || code === 'invalid_client' || code === 'unauthorized_client';
        log.warn('Google-Token-Erneuerung fehlgeschlagen', { account: maskEmail(acc.email), code });
        throw new OAuthError(`Google-Token-Erneuerung fehlgeschlagen (${code})`, permanent);
    }
    const expires = Date.now() + (Number(json.expires_in) || 3600) * 1000;
    cache.set(key, { token: json.access_token, expires });
    return json.access_token;
}
