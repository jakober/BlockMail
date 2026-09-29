// IMAP-IDLE je Konto (imapflow) mit Reconnect/Backoff.
import { ImapFlow } from 'imapflow';
import { googleAccessToken, OAuthError } from './google.js';
import { log, errText, maskEmail } from './log.js';

/** @typedef {import('./store.js').AccountCfg} AccountCfg */
/** @typedef {import('./store.js').Store} Store */

/**
 * @typedef {Object} NewMail
 * @property {number} uid
 * @property {string} from        Anzeigename (kann leer sein)
 * @property {string} address     Absenderadresse (klein)
 * @property {string} subject
 * @property {number} date        ms
 */

const BACKOFF_MIN_MS = 5_000;
const BACKOFF_MAX_MS = 5 * 60_000;
const AUTH_RETRY_MS = 30 * 60_000;
const OAUTH_PERMANENT_RETRY_MS = 60 * 60_000;
/** Nach einer Unterbrechung nur Mails melden, die höchstens so alt sind. */
const BACKLOG_MAX_AGE_MS = 24 * 3600_000;

/** @param {number} ms @param {AbortSignal} signal */
function sleep(ms, signal) {
    return new Promise(resolve => {
        if (signal.aborted) return resolve(undefined);
        const t = setTimeout(resolve, ms);
        signal.addEventListener('abort', () => {
            clearTimeout(t);
            resolve(undefined);
        }, { once: true });
    });
}

export class AccountWatcher {
    /**
     * @param {string} key                 eindeutiger Konto-Schlüssel
     * @param {AccountCfg} account
     * @param {Object} opts
     * @param {Store} opts.store
     * @param {(key: string, mail: NewMail) => void} opts.onNewMail
     * @param {number} opts.idleRenewMs
     * @param {number} opts.maxBacklog
     */
    constructor(key, account, opts) {
        this.key = key;
        this.account = account;
        this.opts = opts;
        /** @type {'starting'|'connecting'|'idle'|'waiting'|'stopped'} */
        this.state = 'starting';
        this.lastError = '';
        this.failures = 0;
        /** @type {ImapFlow | null} */
        this.client = null;
        this.abort = new AbortController();
        /** @type {Promise<void>} */
        this.checkChain = Promise.resolve();
        this.forceTokenRefresh = false;
    }

    get label() {
        return maskEmail(this.account.email);
    }

    start() {
        this.loop().catch(err => log.error('Watcher-Schleife abgebrochen', { account: this.label, err: errText(err) }));
    }

    async stop() {
        this.state = 'stopped';
        this.abort.abort();
        const c = this.client;
        this.client = null;
        if (c) {
            try {
                await c.logout();
            } catch {
                c.close();
            }
        }
    }

    async loop() {
        while (!this.abort.signal.aborted) {
            let delay = BACKOFF_MIN_MS;
            const started = Date.now();
            try {
                await this.session();
                // Normal beendete Verbindung (Server-Timeout, Netzwechsel …)
                if (Date.now() - started > 60_000) this.failures = 0;
            } catch (err) {
                this.lastError = errText(err);
                this.failures++;
                if (err instanceof OAuthError && err.permanent) {
                    delay = OAUTH_PERMANENT_RETRY_MS;
                } else if (/** @type {any} */ (err)?.authenticationFailed) {
                    // Passwort falsch / Token abgelaufen: nicht ständig anklopfen (Sperrgefahr)
                    delay = AUTH_RETRY_MS;
                    this.forceTokenRefresh = true;
                }
                log.warn('IMAP-Verbindung fehlgeschlagen', { account: this.label, err: this.lastError, failures: this.failures });
            } finally {
                this.client = null;
            }
            if (this.abort.signal.aborted) break;
            if (delay === BACKOFF_MIN_MS) {
                const exp = Math.min(BACKOFF_MAX_MS, BACKOFF_MIN_MS * 2 ** Math.min(this.failures, 8));
                delay = Math.round(exp * (0.75 + Math.random() * 0.5));
            }
            this.state = 'waiting';
            log.debug('Neuer Verbindungsversuch geplant', { account: this.label, inMs: delay });
            await sleep(delay, this.abort.signal);
        }
        this.state = 'stopped';
    }

    /** Eine Verbindung aufbauen und halten, bis sie endet. */
    async session() {
        this.state = 'connecting';
        const a = this.account;
        const user = a.loginUser || a.email;
        /** @type {{user: string, pass?: string, accessToken?: string}} */
        let auth;
        if (a.authMethod === 'oauth') {
            auth = { user, accessToken: await googleAccessToken(a, this.forceTokenRefresh) };
            this.forceTokenRefresh = false;
        } else {
            auth = { user, pass: a.password || '' };
        }
        const client = new ImapFlow({
            host: a.imapHost,
            port: a.imapPort,
            secure: a.imapPort === 993,
            auth,
            logger: false,
            clientInfo: { name: 'BlockMail Push', version: '1.0' },
            maxIdleTime: this.opts.idleRenewMs,
            connectionTimeout: 30_000,
            greetingTimeout: 20_000
        });
        this.client = client;
        const closed = new Promise(resolve => client.once('close', resolve));
        client.on('error', err => log.debug('IMAP-Fehler', { account: this.label, err: errText(err) }));
        client.on('exists', ev => {
            if (!ev || !ev.path || ev.path === 'INBOX') this.scheduleCheck(false);
        });

        await client.connect();
        if (this.abort.signal.aborted) {
            client.close();
            return;
        }
        // EXAMINE: nur lesend, verändert keine Flags
        await client.mailboxOpen('INBOX', { readOnly: true });
        this.state = 'idle';
        this.lastError = '';
        log.info('IMAP verbunden (IDLE)', { account: this.label });
        this.scheduleCheck(true);
        await closed;
        if (!this.abort.signal.aborted) log.info('IMAP-Verbindung beendet', { account: this.label });
    }

    /** @param {boolean} afterConnect */
    scheduleCheck(afterConnect) {
        this.checkChain = this.checkChain
            .then(() => this.check(afterConnect))
            .catch(err => log.warn('Abruf neuer Mails fehlgeschlagen', { account: this.label, err: errText(err) }));
    }

    /** Neue UIDs seit dem letzten Stand holen und melden. @param {boolean} afterConnect */
    async check(afterConnect) {
        const client = this.client;
        if (!client || !client.mailbox) return;
        const mb = client.mailbox;
        const uidValidity = String(mb.uidValidity ?? '');
        const uidNext = Number(mb.uidNext ?? 0);
        const { store } = this.opts;
        const st = store.mailbox(this.key);

        if (!st || st.uidValidity !== uidValidity) {
            // Erststart (oder Postfach neu nummeriert): Stand merken, nichts melden
            const lastUid = Math.max(0, uidNext - 1);
            store.setMailbox(this.key, { uidValidity, lastUid });
            log.info('Ausgangsstand gespeichert', { account: this.label, lastUid });
            return;
        }
        if (!mb.exists) return;

        const msgs = await client.fetchAll(`${st.lastUid + 1}:*`,
            { uid: true, envelope: true, internalDate: true }, { uid: true });
        const fresh = msgs.filter(m => Number(m.uid) > st.lastUid).sort((x, y) => Number(x.uid) - Number(y.uid));
        if (!fresh.length) return;

        const maxUid = Number(fresh[fresh.length - 1].uid);
        store.setMailbox(this.key, { uidValidity, lastUid: maxUid });

        let toNotify = fresh;
        if (afterConnect) {
            // Nachholen nach Unterbrechung: nur Aktuelles, begrenzte Anzahl
            const cutoff = Date.now() - BACKLOG_MAX_AGE_MS;
            toNotify = fresh.filter(m => {
                const d = m.internalDate ? new Date(m.internalDate).getTime() : Date.now();
                return d >= cutoff;
            });
        }
        toNotify = toNotify.slice(-this.opts.maxBacklog);

        for (const m of toNotify) {
            const env = m.envelope || {};
            const f = (env.from && env.from[0]) || {};
            const date = m.internalDate ? new Date(m.internalDate).getTime() : Date.now();
            this.opts.onNewMail(this.key, {
                uid: Number(m.uid),
                from: String(f.name || '').trim(),
                address: String(f.address || '').trim().toLowerCase(),
                subject: String(env.subject || '').trim(),
                date
            });
        }
    }
}
