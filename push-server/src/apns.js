// Apple Push Notification service über HTTP/2 mit Token-Auth (ES256-JWT) –
// nur Node-Bordmittel (http2 + crypto).
import crypto from 'node:crypto';
import http2 from 'node:http2';
import { log, errText, maskToken } from './log.js';

const HOST_PROD = 'https://api.push.apple.com';
const HOST_SANDBOX = 'https://api.sandbox.push.apple.com';
const REQUEST_TIMEOUT_MS = 15_000;
const SESSION_IDLE_MS = 10 * 60_000;
/** Apple: Token höchstens alle 20 min neu, gültig bis 60 min. */
const JWT_MAX_AGE_MS = 45 * 60_000;

/** @param {Buffer | string} b */
const b64url = b => Buffer.from(b).toString('base64url');

/**
 * @typedef {Object} ApnsResult
 * @property {number} status   HTTP-Status (0 = Netzwerkfehler)
 * @property {string} [reason] APNs-Grund, z. B. "BadDeviceToken"
 * @property {string} [apnsId]
 */

export class ApnsClient {
    /** @param {{keyId: string, teamId: string, key: string}} opts */
    constructor(opts) {
        this.keyId = opts.keyId;
        this.teamId = opts.teamId;
        this.privateKey = crypto.createPrivateKey(opts.key);
        /** @type {{token: string, at: number} | null} */
        this.jwt = null;
        /** @type {Map<string, http2.ClientHttp2Session>} */
        this.sessions = new Map();
    }

    /** @param {boolean} [force] */
    token(force = false) {
        const now = Date.now();
        if (!force && this.jwt && now - this.jwt.at < JWT_MAX_AGE_MS) return this.jwt.token;
        const header = b64url(JSON.stringify({ alg: 'ES256', kid: this.keyId }));
        const claims = b64url(JSON.stringify({ iss: this.teamId, iat: Math.floor(now / 1000) }));
        const input = `${header}.${claims}`;
        const sig = crypto.sign('sha256', Buffer.from(input), { key: this.privateKey, dsaEncoding: 'ieee-p1363' });
        this.jwt = { token: `${input}.${b64url(sig)}`, at: now };
        return this.jwt.token;
    }

    /** @param {string} origin */
    session(origin) {
        const existing = this.sessions.get(origin);
        if (existing && !existing.closed && !existing.destroyed) return existing;
        const s = http2.connect(origin);
        s.setTimeout(SESSION_IDLE_MS, () => s.close());
        const drop = () => {
            if (this.sessions.get(origin) === s) this.sessions.delete(origin);
        };
        s.on('close', drop);
        s.on('goaway', drop);
        s.on('error', err => {
            log.warn('APNs-Verbindung gestört', { err: errText(err) });
            drop();
        });
        this.sessions.set(origin, s);
        return s;
    }

    /**
     * Schickt eine Meldung.
     * @param {Object} p
     * @param {string} p.deviceToken
     * @param {string} p.topic
     * @param {boolean} p.sandbox
     * @param {object} p.payload
     * @param {'alert'|'background'} [p.pushType]
     * @param {string} [p.collapseId]
     * @returns {Promise<ApnsResult>}
     */
    async send(p) {
        let res = await this.sendOnce(p, false);
        if (res.status === 403 && (res.reason === 'ExpiredProviderToken' || res.reason === 'InvalidProviderToken')) {
            res = await this.sendOnce(p, true);
        }
        if (res.status === 0) {
            // Netzwerkfehler: einmal mit frischer Verbindung wiederholen
            res = await this.sendOnce(p, false);
        }
        return res;
    }

    /** @param {Parameters<ApnsClient['send']>[0]} p @param {boolean} freshToken @returns {Promise<ApnsResult>} */
    sendOnce(p, freshToken) {
        const origin = p.sandbox ? HOST_SANDBOX : HOST_PROD;
        const body = Buffer.from(JSON.stringify(p.payload));
        const pushType = p.pushType || 'alert';
        /** @type {http2.OutgoingHttpHeaders} */
        const headers = {
            ':method': 'POST',
            ':path': `/3/device/${p.deviceToken}`,
            authorization: `bearer ${this.token(freshToken)}`,
            'apns-topic': p.topic,
            'apns-push-type': pushType,
            'apns-priority': pushType === 'alert' ? '10' : '5',
            'apns-expiration': String(Math.floor(Date.now() / 1000) + 24 * 3600),
            'content-type': 'application/json',
            'content-length': body.length
        };
        if (p.collapseId) headers['apns-collapse-id'] = p.collapseId.slice(0, 64);

        return new Promise(resolve => {
            let settled = false;
            /** @param {ApnsResult} r */
            const done = r => {
                if (!settled) {
                    settled = true;
                    resolve(r);
                }
            };
            /** @type {http2.ClientHttp2Stream} */
            let req;
            try {
                req = this.session(origin).request(headers);
            } catch (err) {
                log.warn('APNs nicht erreichbar', { err: errText(err) });
                this.sessions.delete(origin);
                return done({ status: 0, reason: 'ConnectFailed' });
            }
            let status = 0;
            let apnsId = '';
            const chunks = [];
            req.setTimeout(REQUEST_TIMEOUT_MS, () => {
                req.close(http2.constants.NGHTTP2_CANCEL);
                done({ status: 0, reason: 'Timeout' });
            });
            req.on('response', h => {
                status = Number(h[':status']) || 0;
                apnsId = String(h['apns-id'] || '');
            });
            req.on('data', c => chunks.push(c));
            req.on('end', () => {
                let reason;
                if (chunks.length) {
                    try {
                        reason = JSON.parse(Buffer.concat(chunks).toString('utf8')).reason;
                    } catch { /* leerer/ungültiger Body */ }
                }
                if (status !== 200) {
                    log.warn('APNs lehnt ab', { status, reason, device: maskToken(p.deviceToken) });
                }
                done({ status, reason, apnsId });
            });
            req.on('error', err => {
                log.warn('APNs-Anfrage fehlgeschlagen', { err: errText(err) });
                done({ status: 0, reason: 'RequestError' });
            });
            req.end(body);
        });
    }

    close() {
        for (const s of this.sessions.values()) s.close();
        this.sessions.clear();
    }
}

/**
 * Soll die Registrierung wegen dieser Antwort gelöscht werden?
 * @param {ApnsResult} r
 */
export function isDeadToken(r) {
    return r.status === 410 || r.reason === 'BadDeviceToken' || r.reason === 'Unregistered';
}
