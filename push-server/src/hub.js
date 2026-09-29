// Verbindet Registrierungen, IMAP-Watcher und APNs.
import crypto from 'node:crypto';
import { AccountWatcher } from './watcher.js';
import { isDeadToken } from './apns.js';
import { log, errText, maskEmail, maskToken } from './log.js';

/** @typedef {import('./store.js').Registration} Registration */
/** @typedef {import('./store.js').AccountCfg} AccountCfg */
/** @typedef {import('./watcher.js').NewMail} NewMail */

const sha = (/** @type {string} */ s) => crypto.createHash('sha256').update(s).digest('hex');

/** Kennung eines IMAP-Postfachs (unabhängig von den Zugangsdaten). @param {AccountCfg} a */
export function accountKey(a) {
    return sha([a.email.toLowerCase(), a.imapHost.toLowerCase(), a.imapPort, (a.loginUser || a.email).toLowerCase()].join('|')).slice(0, 32);
}

/** Fingerabdruck der Zugangsdaten – ändern sie sich, wird neu verbunden. @param {AccountCfg} a */
function credFingerprint(a) {
    return sha(JSON.stringify([a.authMethod, a.password || '', a.refreshToken || '', a.googleClientId || '']));
}

const TEXT = {
    de: { sender: 'Absender', noSubject: '(Kein Betreff)' },
    en: { sender: 'Sender', noSubject: '(No subject)' }
};

/** @param {string} s @param {number} n */
const clip = (s, n) => (s.length > n ? s.slice(0, n - 1) + '…' : s);

export class Hub {
    /**
     * @param {Object} deps
     * @param {import('./store.js').Store} deps.store
     * @param {import('./apns.js').ApnsClient} deps.apns
     * @param {{topic: string, allowedTopics: string[]}} deps.apnsCfg
     * @param {number} deps.idleRenewMs
     * @param {number} deps.maxBacklog
     */
    constructor(deps) {
        this.store = deps.store;
        this.apns = deps.apns;
        this.apnsCfg = deps.apnsCfg;
        this.idleRenewMs = deps.idleRenewMs;
        this.maxBacklog = deps.maxBacklog;
        /** @type {Map<string, {watcher: AccountWatcher, fp: string}>} */
        this.watchers = new Map();
        /** @type {NodeJS.Timeout | null} */
        this.reconcileTimer = null;
    }

    /** Watcher an die aktuellen Registrierungen anpassen (entprellt). */
    reconcileSoon() {
        if (this.reconcileTimer) return;
        this.reconcileTimer = setTimeout(() => {
            this.reconcileTimer = null;
            this.reconcile();
        }, 200);
    }

    reconcile() {
        /** @type {Map<string, {acc: AccountCfg, updatedAt: number}>} */
        const wanted = new Map();
        for (const reg of this.store.registrations()) {
            for (const acc of reg.accounts) {
                const k = accountKey(acc);
                const prev = wanted.get(k);
                // Neueste Registrierung liefert die aktuellsten Zugangsdaten
                if (!prev || reg.updatedAt > prev.updatedAt) wanted.set(k, { acc, updatedAt: reg.updatedAt });
            }
        }
        for (const [k, entry] of this.watchers) {
            const w = wanted.get(k);
            if (!w || credFingerprint(w.acc) !== entry.fp) {
                entry.watcher.stop().catch(() => {});
                this.watchers.delete(k);
                log.info(w ? 'Zugangsdaten geändert – neu verbinden' : 'Konto wird nicht mehr beobachtet',
                    { account: maskEmail(entry.watcher.account.email) });
            }
        }
        for (const [k, w] of wanted) {
            if (this.watchers.has(k)) continue;
            const watcher = new AccountWatcher(k, w.acc, {
                store: this.store,
                onNewMail: (key, mail) => {
                    this.onNewMail(key, mail).catch(err => log.error('Zustellung fehlgeschlagen', { err: errText(err) }));
                },
                idleRenewMs: this.idleRenewMs,
                maxBacklog: this.maxBacklog
            });
            this.watchers.set(k, { watcher, fp: credFingerprint(w.acc) });
            watcher.start();
        }
        this.store.pruneMailboxes(new Set(wanted.keys()));
    }

    /** @param {string} key @param {NewMail} mail */
    async onNewMail(key, mail) {
        const regs = this.store.registrations().filter(r => r.accounts.some(a => accountKey(a) === key));
        for (const reg of regs) {
            const acc = reg.accounts.find(a => accountKey(a) === key);
            if (!acc || !this.allowed(reg, mail.address)) continue;
            const topic = this.apnsCfg.topic || reg.bundleId;
            if (!topic) continue;
            const res = await this.apns.send({
                deviceToken: reg.deviceToken,
                topic,
                sandbox: reg.sandbox,
                payload: this.payload(reg, acc, mail),
                collapseId: sha(`${acc.email.toLowerCase()}:${mail.uid}`).slice(0, 32)
            });
            if (isDeadToken(res)) {
                log.info('Gerät abgemeldet (APNs)', { device: maskToken(reg.deviceToken), reason: res.reason || res.status });
                this.store.deleteRegistration(reg.id);
                this.reconcileSoon();
            } else if (res.status === 200) {
                log.debug('Meldung zugestellt', { account: maskEmail(acc.email), uid: mail.uid });
            }
        }
    }

    /** Stumm / Blockiert / Nur-VIP beachten. @param {Registration} reg @param {string} address */
    allowed(reg, address) {
        const a = address.toLowerCase();
        if (reg.blockedSenders.includes(a)) return false;
        if (reg.mutedSenders.includes(a)) return false;
        if (reg.vipOnly && !reg.vipSenders.includes(a)) return false;
        return true;
    }

    /**
     * APNs-Nutzlast wie die lokale Meldung aus Notifier.showNewMail.
     * @param {Registration} reg @param {AccountCfg} acc @param {NewMail} mail
     */
    payload(reg, acc, mail) {
        const t = TEXT[reg.language] || TEXT.de;
        const accLower = acc.email.trim().toLowerCase();
        const first = (reg.accounts[0]?.email || '').trim().toLowerCase();
        // "" = erstes/aktives Konto der App (siehe MailChecker.accountTag)
        const tag = accLower === first ? '' : accLower;
        const subject = clip(mail.subject, 300);
        const from = clip(mail.from, 120);
        /** @type {Record<string, string>} */
        const alert = {
            title: from || mail.address || t.sender,
            body: subject || t.noSubject
        };
        if (reg.showAccount) alert.subtitle = acc.email;
        return {
            aps: {
                alert,
                sound: 'default',
                category: 'NEW_MAIL',
                'thread-id': accLower,
                'mutable-content': 1
            },
            uid: mail.uid,
            account: tag,
            address: mail.address,
            subject,
            from
        };
    }

    /** Registrierungen, die lange nicht erneuert wurden, entfernen. @param {number} ttlDays */
    expire(ttlDays) {
        const cutoff = Date.now() - ttlDays * 24 * 3600_000;
        let removed = 0;
        for (const reg of this.store.registrations()) {
            if (reg.updatedAt < cutoff) {
                this.store.deleteRegistration(reg.id);
                removed++;
            }
        }
        if (removed) {
            log.info('Veraltete Registrierungen entfernt', { removed });
            this.reconcileSoon();
        }
    }

    stats() {
        let connected = 0;
        let failing = 0;
        for (const { watcher } of this.watchers.values()) {
            if (watcher.state === 'idle') connected++;
            else if (watcher.lastError) failing++;
        }
        return { registrations: this.store.registrations().length, accounts: this.watchers.size, connected, failing };
    }

    async stop() {
        await Promise.all([...this.watchers.values()].map(e => e.watcher.stop().catch(() => {})));
        this.watchers.clear();
    }
}
