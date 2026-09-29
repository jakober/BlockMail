// Konfiguration aus Umgebungsvariablen (siehe .env.example).
import fs from 'node:fs';
import path from 'node:path';

/** @param {string} name @param {string} [fallback] */
function env(name, fallback = '') {
    const v = process.env[name];
    return v === undefined || v === '' ? fallback : v.trim();
}

/** @param {string} name @param {number} fallback */
function envInt(name, fallback) {
    const n = Number.parseInt(env(name, ''), 10);
    return Number.isFinite(n) && n > 0 ? n : fallback;
}

/**
 * Liest den APNs-Schlüssel (.p8) aus APNS_KEY (Inhalt, auch mit "\n") oder APNS_KEY_FILE.
 * @returns {string}
 */
function loadApnsKey() {
    const inline = env('APNS_KEY');
    if (inline) return inline.replace(/\\n/g, '\n');
    const file = env('APNS_KEY_FILE');
    if (file) return fs.readFileSync(path.resolve(file), 'utf8');
    return '';
}

export const config = {
    port: envInt('PORT', 8787),
    host: env('HOST', '0.0.0.0'),
    dataDir: path.resolve(env('DATA_DIR', './data')),
    dataKey: env('DATA_KEY'),
    sharedSecret: env('PUSH_SHARED_SECRET'),
    apns: {
        keyId: env('APNS_KEY_ID'),
        teamId: env('APNS_TEAM_ID'),
        key: loadApnsKey(),
        /** Fester Topic (Bundle-ID der App); leer = bundleId aus der Registrierung. */
        topic: env('APNS_TOPIC'),
        /** Kommagetrennte Liste erlaubter Bundle-IDs (leer = alle). */
        allowedTopics: env('APNS_ALLOWED_TOPICS').split(',').map(s => s.trim()).filter(Boolean)
    },
    maxRegistrations: envInt('MAX_REGISTRATIONS', 500),
    maxAccountsPerDevice: envInt('MAX_ACCOUNTS_PER_DEVICE', 10),
    /** Registrierungen, die so lange nicht erneuert wurden, werden gelöscht. */
    registrationTtlDays: envInt('REGISTRATION_TTL_DAYS', 90),
    /** Nach einer Unterbrechung höchstens so viele Mails je Konto melden. */
    maxBacklogNotifications: envInt('MAX_BACKLOG_NOTIFICATIONS', 5),
    /** IDLE nach dieser Zeit (ms) erneuern. */
    idleRenewMs: envInt('IDLE_RENEW_MINUTES', 25) * 60_000,
    logLevel: env('LOG_LEVEL', 'info'),
    /** Hinter einem Reverse-Proxy: X-Forwarded-For für die Anfragebegrenzung auswerten. */
    trustProxy: env('TRUST_PROXY', '1') !== '0'
};

/** Prüft Pflichtwerte und bricht mit verständlicher Meldung ab. */
export function validateConfig() {
    const problems = [];
    if (!config.dataKey) problems.push('DATA_KEY fehlt (z. B. `openssl rand -hex 32`).');
    if (!config.apns.keyId) problems.push('APNS_KEY_ID fehlt.');
    if (!config.apns.teamId) problems.push('APNS_TEAM_ID fehlt.');
    if (!config.apns.key) problems.push('APNS_KEY bzw. APNS_KEY_FILE fehlt.');
    return problems;
}
