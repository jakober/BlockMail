// Verschlüsselte Ablage (AES-256-GCM) für Registrierungen und UID-Stände.
//
// Dateiformat data/store.enc: JSON { v: 1, iv, tag, data } (Base64). Der Schlüssel
// kommt aus DATA_KEY: 64 Hex-Zeichen oder 32 Byte Base64 werden direkt benutzt,
// alles andere wird per scrypt zu 32 Byte abgeleitet.
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { log, errText } from './log.js';

/**
 * @typedef {Object} AccountCfg
 * @property {string} email
 * @property {'oauth'|'password'} authMethod
 * @property {string} imapHost
 * @property {number} imapPort
 * @property {string} loginUser
 * @property {string} [password]
 * @property {string} [refreshToken]
 * @property {string} [googleClientId]
 */

/**
 * @typedef {Object} Registration
 * @property {string} id              sha256(installToken + ":" + deviceToken)
 * @property {string} installHash     sha256(installToken) – der Token selbst wird nicht gespeichert
 * @property {string} deviceToken
 * @property {string} bundleId
 * @property {boolean} sandbox
 * @property {'de'|'en'} language
 * @property {AccountCfg[]} accounts
 * @property {string[]} mutedSenders
 * @property {string[]} blockedSenders
 * @property {string[]} vipSenders
 * @property {boolean} vipOnly
 * @property {boolean} showAccount
 * @property {number} createdAt
 * @property {number} updatedAt
 */

/**
 * @typedef {Object} MailboxState
 * @property {string} uidValidity
 * @property {number} lastUid
 */

/**
 * @typedef {Object} StoreData
 * @property {Record<string, Registration>} registrations
 * @property {Record<string, MailboxState>} mailboxes
 */

/** @param {string} raw */
function deriveKey(raw) {
    if (/^[0-9a-fA-F]{64}$/.test(raw)) return Buffer.from(raw, 'hex');
    try {
        const b = Buffer.from(raw, 'base64');
        if (b.length === 32 && /^[A-Za-z0-9+/=_-]+$/.test(raw)) return b;
    } catch { /* weiter mit scrypt */ }
    log.warn('DATA_KEY ist kein 32-Byte-Schlüssel – wird per scrypt abgeleitet. Besser: `openssl rand -hex 32`.');
    return crypto.scryptSync(raw, 'blockmail-push-server', 32);
}

export class Store {
    /** @param {string} dir @param {string} dataKey */
    constructor(dir, dataKey) {
        this.dir = dir;
        this.file = path.join(dir, 'store.enc');
        this.key = deriveKey(dataKey);
        /** @type {StoreData} */
        this.data = { registrations: {}, mailboxes: {} };
        /** @type {NodeJS.Timeout | null} */
        this.saveTimer = null;
    }

    load() {
        fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
        if (!fs.existsSync(this.file)) return;
        const env = JSON.parse(fs.readFileSync(this.file, 'utf8'));
        if (env.v !== 1) throw new Error('Unbekanntes Speicherformat');
        const decipher = crypto.createDecipheriv('aes-256-gcm', this.key, Buffer.from(env.iv, 'base64'));
        decipher.setAuthTag(Buffer.from(env.tag, 'base64'));
        let plain;
        try {
            plain = Buffer.concat([decipher.update(Buffer.from(env.data, 'base64')), decipher.final()]);
        } catch {
            throw new Error('Datenablage lässt sich nicht entschlüsseln – falscher DATA_KEY?');
        }
        const parsed = JSON.parse(plain.toString('utf8'));
        this.data = {
            registrations: parsed.registrations || {},
            mailboxes: parsed.mailboxes || {}
        };
    }

    /** Speichert gebündelt (höchstens alle 500 ms). */
    save() {
        if (this.saveTimer) return;
        this.saveTimer = setTimeout(() => {
            this.saveTimer = null;
            this.flush();
        }, 500);
    }

    /** Sofort und atomar schreiben. */
    flush() {
        if (this.saveTimer) {
            clearTimeout(this.saveTimer);
            this.saveTimer = null;
        }
        try {
            const iv = crypto.randomBytes(12);
            const cipher = crypto.createCipheriv('aes-256-gcm', this.key, iv);
            const enc = Buffer.concat([cipher.update(JSON.stringify(this.data), 'utf8'), cipher.final()]);
            const env = {
                v: 1,
                iv: iv.toString('base64'),
                tag: cipher.getAuthTag().toString('base64'),
                data: enc.toString('base64')
            };
            fs.mkdirSync(this.dir, { recursive: true, mode: 0o700 });
            const tmp = this.file + '.tmp';
            fs.writeFileSync(tmp, JSON.stringify(env), { mode: 0o600 });
            fs.renameSync(tmp, this.file);
        } catch (err) {
            log.error('Speichern fehlgeschlagen', { err: errText(err) });
        }
    }

    /** @returns {Registration[]} */
    registrations() {
        return Object.values(this.data.registrations);
    }

    /** @param {Registration} reg */
    putRegistration(reg) {
        this.data.registrations[reg.id] = reg;
        this.save();
    }

    /** @param {string} id */
    deleteRegistration(id) {
        if (this.data.registrations[id]) {
            delete this.data.registrations[id];
            this.save();
            return true;
        }
        return false;
    }

    /** @param {string} key @returns {MailboxState | undefined} */
    mailbox(key) {
        return this.data.mailboxes[key];
    }

    /** @param {string} key @param {MailboxState} state */
    setMailbox(key, state) {
        this.data.mailboxes[key] = state;
        this.save();
    }

    /** Entfernt UID-Stände von Konten, die niemand mehr beobachtet. @param {Set<string>} keep */
    pruneMailboxes(keep) {
        let changed = false;
        for (const k of Object.keys(this.data.mailboxes)) {
            if (!keep.has(k)) {
                delete this.data.mailboxes[k];
                changed = true;
            }
        }
        if (changed) this.save();
    }
}
