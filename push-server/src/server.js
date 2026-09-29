// HTTP-API: POST /v1/register, POST /v1/unregister, GET /health.
// Läuft hinter einem HTTPS-Reverse-Proxy (Caddy/Traefik) – selbst nur HTTP.
import crypto from 'node:crypto';
import dns from 'node:dns/promises';
import http from 'node:http';
import net from 'node:net';
import { log, errText, maskToken } from './log.js';

/** @typedef {import('./store.js').Registration} Registration */
/** @typedef {import('./store.js').AccountCfg} AccountCfg */

const MAX_BODY = 256 * 1024;
const RATE_WINDOW_MS = 60_000;
const RATE_MAX = 30;
const MAX_LIST = 5000;

class HttpError extends Error {
    /** @param {number} status @param {string} message */
    constructor(status, message) {
        super(message);
        this.status = status;
    }
}

const sha = (/** @type {string} */ s) => crypto.createHash('sha256').update(s).digest('hex');

/** Zeitkonstanter Vergleich. @param {string} a @param {string} b */
function safeEqual(a, b) {
    const x = crypto.createHash('sha256').update(a).digest();
    const y = crypto.createHash('sha256').update(b).digest();
    return crypto.timingSafeEqual(x, y);
}

/** @param {http.IncomingMessage} req @returns {Promise<any>} */
function readJson(req) {
    return new Promise((resolve, reject) => {
        const ct = String(req.headers['content-type'] || '');
        if (!ct.toLowerCase().startsWith('application/json')) {
            reject(new HttpError(415, 'Content-Type muss application/json sein'));
            return;
        }
        let size = 0;
        /** @type {Buffer[]} */
        const chunks = [];
        req.on('data', c => {
            size += c.length;
            if (size > MAX_BODY) {
                reject(new HttpError(413, 'Anfrage zu groß'));
                req.destroy();
                return;
            }
            chunks.push(c);
        });
        req.on('end', () => {
            try {
                resolve(JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}'));
            } catch {
                reject(new HttpError(400, 'Ungültiges JSON'));
            }
        });
        req.on('error', reject);
    });
}

/** @param {http.ServerResponse} res @param {number} status @param {object} body */
function send(res, status, body) {
    const data = JSON.stringify(body);
    res.writeHead(status, {
        'content-type': 'application/json; charset=utf-8',
        'content-length': Buffer.byteLength(data),
        'cache-control': 'no-store',
        'x-content-type-options': 'nosniff'
    });
    res.end(data);
}

/** @param {unknown} v @param {string} name @param {number} [max] */
function str(v, name, max = 500) {
    if (typeof v !== 'string') throw new HttpError(400, `Feld „${name}“ fehlt`);
    const s = v.trim();
    if (s.length > max) throw new HttpError(400, `Feld „${name}“ zu lang`);
    return s;
}

/** @param {unknown} v @param {string} name */
function addrList(v, name) {
    if (v === undefined || v === null) return [];
    if (!Array.isArray(v)) throw new HttpError(400, `Feld „${name}“ muss eine Liste sein`);
    if (v.length > MAX_LIST) throw new HttpError(400, `Feld „${name}“ zu lang`);
    return [...new Set(v.filter(x => typeof x === 'string').map(x => x.trim().toLowerCase()).filter(Boolean))];
}

/** Private/lokale Ziele ablehnen (kein Abtasten des internen Netzes). @param {string} ip */
function isPrivateIp(ip) {
    if (net.isIPv4(ip)) {
        const [a, b] = ip.split('.').map(Number);
        return a === 10 || a === 127 || a === 0 || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31) ||
            (a === 192 && b === 168) || (a === 100 && b >= 64 && b <= 127) || a >= 224;
    }
    const s = ip.toLowerCase();
    if (s.startsWith('::ffff:')) return isPrivateIp(s.slice(7));
    return s === '::1' || s === '::' || s.startsWith('fc') || s.startsWith('fd') || s.startsWith('fe80');
}

/** @param {string} host */
async function assertPublicHost(host) {
    if (process.env.ALLOW_PRIVATE_IMAP_HOSTS === '1') return;
    let addrs;
    try {
        addrs = await dns.lookup(host, { all: true });
    } catch {
        throw new HttpError(400, `IMAP-Server „${host}“ nicht auflösbar`);
    }
    if (addrs.some(a => isPrivateIp(a.address))) {
        throw new HttpError(400, `IMAP-Server „${host}“ ist eine interne Adresse`);
    }
}

/** @param {any} a @returns {Promise<AccountCfg>} */
async function parseAccount(a) {
    if (!a || typeof a !== 'object') throw new HttpError(400, 'Ungültiges Konto');
    const email = str(a.email, 'email', 320).toLowerCase();
    if (!email.includes('@')) throw new HttpError(400, 'Ungültige E-Mail-Adresse');
    const authMethod = a.authMethod === 'oauth' ? 'oauth' : 'password';
    const imapHost = str(a.imapHost, 'imapHost', 253).toLowerCase();
    if (!/^[a-z0-9.-]+$/.test(imapHost)) throw new HttpError(400, 'Ungültiger IMAP-Server');
    const imapPort = Number(a.imapPort);
    if (!Number.isInteger(imapPort) || imapPort < 1 || imapPort > 65535) throw new HttpError(400, 'Ungültiger IMAP-Port');
    await assertPublicHost(imapHost);
    const loginUser = typeof a.loginUser === 'string' && a.loginUser.trim() ? a.loginUser.trim() : email;
    /** @type {AccountCfg} */
    const acc = { email, authMethod, imapHost, imapPort, loginUser };
    if (authMethod === 'oauth') {
        acc.refreshToken = str(a.refreshToken, 'refreshToken', 2048);
        acc.googleClientId = str(a.googleClientId, 'googleClientId', 256);
        if (!acc.refreshToken || !acc.googleClientId) throw new HttpError(400, `Google-Zugang für ${email} unvollständig`);
    } else {
        acc.password = typeof a.password === 'string' ? a.password : '';
        if (!acc.password) throw new HttpError(400, `Passwort für ${email} fehlt`);
        if (acc.password.length > 1024) throw new HttpError(400, 'Passwort zu lang');
    }
    return acc;
}

/**
 * @param {Object} deps
 * @param {import('./store.js').Store} deps.store
 * @param {import('./hub.js').Hub} deps.hub
 * @param {typeof import('./config.js').config} deps.config
 */
export function createServer({ store, hub, config }) {
    /** @type {Map<string, {count: number, start: number}>} */
    const rate = new Map();
    setInterval(() => {
        const now = Date.now();
        for (const [k, v] of rate) if (now - v.start > RATE_WINDOW_MS) rate.delete(k);
    }, RATE_WINDOW_MS).unref();

    /** @param {http.IncomingMessage} req */
    function clientIp(req) {
        if (config.trustProxy) {
            const xf = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
            if (xf) return xf;
        }
        return req.socket.remoteAddress || '?';
    }

    /** @param {http.IncomingMessage} req */
    function limit(req) {
        const ip = clientIp(req);
        const now = Date.now();
        const e = rate.get(ip);
        if (!e || now - e.start > RATE_WINDOW_MS) {
            rate.set(ip, { count: 1, start: now });
            return;
        }
        if (++e.count > RATE_MAX) throw new HttpError(429, 'Zu viele Anfragen – bitte später erneut versuchen');
    }

    /** @param {http.IncomingMessage} req @returns {string} installToken */
    function authenticate(req) {
        if (config.sharedSecret) {
            const given = String(req.headers['x-push-secret'] || '');
            if (!given || !safeEqual(given, config.sharedSecret)) throw new HttpError(401, 'Server-Passwort fehlt oder ist falsch');
        }
        const m = /^Bearer\s+(\S+)$/i.exec(String(req.headers.authorization || ''));
        const token = m ? m[1] : '';
        if (token.length < 16 || token.length > 200) throw new HttpError(401, 'Installations-Token fehlt');
        return token;
    }

    /** @param {any} body */
    function parseDeviceToken(body) {
        const raw = body ? body.deviceToken : undefined;
        const t = typeof raw === 'string' ? raw.trim().toLowerCase() : '';
        if (!/^[0-9a-f]{64,200}$/.test(t)) throw new HttpError(400, 'Ungültiges Geräte-Token');
        return t;
    }

    /** @param {http.IncomingMessage} req */
    async function register(req) {
        const installToken = authenticate(req);
        const body = await readJson(req);
        const deviceToken = parseDeviceToken(body);
        const bundleId = str(body.bundleId ?? '', 'bundleId', 155);
        if (!config.apns.topic) {
            if (!/^[A-Za-z0-9.-]+$/.test(bundleId)) throw new HttpError(400, 'Ungültige Bundle-ID');
            if (config.apns.allowedTopics.length && !config.apns.allowedTopics.includes(bundleId)) {
                throw new HttpError(403, 'Diese App ist auf dem Server nicht freigeschaltet');
            }
        }
        if (!Array.isArray(body.accounts) || body.accounts.length === 0) throw new HttpError(400, 'Keine Konten angegeben');
        if (body.accounts.length > config.maxAccountsPerDevice) throw new HttpError(400, 'Zu viele Konten');
        const accounts = [];
        const seen = new Set();
        for (const a of body.accounts) {
            const acc = await parseAccount(a);
            if (seen.has(acc.email)) continue;
            seen.add(acc.email);
            accounts.push(acc);
        }

        const id = sha(`${installToken}:${deviceToken}`);
        const existing = store.data.registrations[id];
        if (!existing && store.registrations().length >= config.maxRegistrations) {
            throw new HttpError(503, 'Server ist voll (MAX_REGISTRATIONS)');
        }
        const now = Date.now();
        /** @type {Registration} */
        const reg = {
            id,
            installHash: sha(installToken),
            deviceToken,
            bundleId,
            sandbox: body.sandbox === true,
            language: body.language === 'en' ? 'en' : 'de',
            accounts,
            mutedSenders: addrList(body.mutedSenders, 'mutedSenders'),
            blockedSenders: addrList(body.blockedSenders, 'blockedSenders'),
            vipSenders: addrList(body.vipSenders, 'vipSenders'),
            vipOnly: body.vipOnly === true,
            showAccount: body.showAccount === true,
            createdAt: existing?.createdAt || now,
            updatedAt: now
        };
        // Ein Geräte-Token gehört genau einer Installation (z. B. nach Neuinstallation)
        for (const other of store.registrations()) {
            if (other.id !== id && other.deviceToken === deviceToken) store.deleteRegistration(other.id);
        }
        store.putRegistration(reg);
        hub.reconcileSoon();
        log.info(existing ? 'Registrierung erneuert' : 'Neue Registrierung',
            { device: maskToken(deviceToken), accounts: accounts.length, sandbox: reg.sandbox });
        return { ok: true, accounts: accounts.length };
    }

    /** @param {http.IncomingMessage} req */
    async function unregister(req) {
        const installToken = authenticate(req);
        const body = await readJson(req);
        let removed = 0;
        if (body && body.deviceToken) {
            const deviceToken = parseDeviceToken(body);
            if (store.deleteRegistration(sha(`${installToken}:${deviceToken}`))) removed++;
        } else {
            // Ohne Geräte-Token: alle Geräte dieser Installation abmelden
            const h = sha(installToken);
            for (const r of store.registrations()) if (r.installHash === h && store.deleteRegistration(r.id)) removed++;
        }
        if (removed) {
            hub.reconcileSoon();
            log.info('Registrierung gelöscht', { removed });
        }
        return { ok: true, removed };
    }

    return http.createServer(async (req, res) => {
        const url = new URL(req.url || '/', 'http://localhost');
        try {
            if ((req.method === 'GET' || req.method === 'HEAD') && url.pathname === '/health') {
                send(res, 200, { ok: true, uptime: Math.round(process.uptime()), ...hub.stats() });
                return;
            }
            if (url.pathname === '/v1/register' || url.pathname === '/v1/unregister') {
                if (req.method !== 'POST') throw new HttpError(405, 'Nur POST erlaubt');
                limit(req);
                const out = url.pathname === '/v1/register' ? await register(req) : await unregister(req);
                send(res, 200, out);
                return;
            }
            throw new HttpError(404, 'Nicht gefunden');
        } catch (err) {
            if (err instanceof HttpError) {
                if (err.status >= 400 && err.status !== 404) log.info('Anfrage abgelehnt', { path: url.pathname, status: err.status, err: err.message });
                send(res, err.status, { error: err.message });
            } else {
                log.error('Interner Fehler', { path: url.pathname, err: errText(err) });
                send(res, 500, { error: 'Interner Serverfehler' });
            }
        }
    });
}
