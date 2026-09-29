// Schlankes Logging. Zugangsdaten werden nie geloggt; Adressen nur gekürzt.
import { config } from './config.js';

const LEVELS = { debug: 10, info: 20, warn: 30, error: 40 };
const min = LEVELS[/** @type {keyof LEVELS} */ (config.logLevel)] ?? LEVELS.info;

/** @param {keyof LEVELS} level @param {string} msg @param {Record<string, unknown>} [extra] */
function out(level, msg, extra) {
    if (LEVELS[level] < min) return;
    const line = { t: new Date().toISOString(), level, msg, ...(extra || {}) };
    const s = JSON.stringify(line);
    if (level === 'error' || level === 'warn') console.error(s);
    else console.log(s);
}

export const log = {
    /** @param {string} m @param {Record<string, unknown>} [e] */ debug: (m, e) => out('debug', m, e),
    /** @param {string} m @param {Record<string, unknown>} [e] */ info: (m, e) => out('info', m, e),
    /** @param {string} m @param {Record<string, unknown>} [e] */ warn: (m, e) => out('warn', m, e),
    /** @param {string} m @param {Record<string, unknown>} [e] */ error: (m, e) => out('error', m, e)
};

/**
 * Kürzt eine Adresse für Logs: "max.mustermann@example.com" → "ma***@example.com".
 * @param {string} email
 */
export function maskEmail(email) {
    const s = String(email || '');
    const at = s.indexOf('@');
    if (at < 0) return s.slice(0, 2) + '***';
    return s.slice(0, Math.min(2, at)) + '***' + s.slice(at);
}

/** @param {string} token */
export function maskToken(token) {
    const s = String(token || '');
    return s.length > 8 ? s.slice(0, 6) + '…' : '***';
}

/** @param {unknown} err */
export function errText(err) {
    if (err instanceof Error) {
        const code = /** @type {any} */ (err).code;
        return code ? `${code}: ${err.message}` : err.message;
    }
    return String(err);
}
