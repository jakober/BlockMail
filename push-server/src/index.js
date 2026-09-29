// BlockMail-Push-Server: IMAP IDLE je Konto → Apple Push (APNs).
import { config, validateConfig } from './config.js';
import { Store } from './store.js';
import { ApnsClient } from './apns.js';
import { Hub } from './hub.js';
import { createServer } from './server.js';
import { log, errText } from './log.js';

const problems = validateConfig();
if (problems.length) {
    for (const p of problems) log.error(p);
    process.exit(1);
}
if (!config.sharedSecret) {
    log.warn('PUSH_SHARED_SECRET ist nicht gesetzt – jeder, der die URL kennt, kann Geräte registrieren. Dringend empfohlen!');
}

process.on('unhandledRejection', err => log.error('Unbehandelter Fehler (Promise)', { err: errText(err) }));
process.on('uncaughtException', err => {
    // Zustand unklar: Daten sichern und beenden – Docker startet neu (restart: unless-stopped)
    log.error('Unbehandelter Fehler – Neustart', { err: errText(err) });
    try { store.flush(); } catch { /* egal */ }
    process.exit(1);
});

const store = new Store(config.dataDir, config.dataKey);
try {
    store.load();
} catch (err) {
    log.error('Datenablage nicht lesbar', { err: errText(err) });
    process.exit(1);
}

let apns;
try {
    apns = new ApnsClient(config.apns);
} catch (err) {
    log.error('APNs-Schlüssel ungültig (.p8 erwartet)', { err: errText(err) });
    process.exit(1);
}

const hub = new Hub({
    store,
    apns,
    apnsCfg: config.apns,
    idleRenewMs: config.idleRenewMs,
    maxBacklog: config.maxBacklogNotifications
});
hub.expire(config.registrationTtlDays);
hub.reconcile();

const expireTimer = setInterval(() => hub.expire(config.registrationTtlDays), 6 * 3600_000);
expireTimer.unref();

const server = createServer({ store, hub, config });
server.requestTimeout = 30_000;
server.headersTimeout = 20_000;
server.listen(config.port, config.host, () => {
    log.info('Push-Server läuft', { port: config.port, registrations: store.registrations().length });
});

let stopping = false;
/** @param {string} signal */
async function shutdown(signal) {
    if (stopping) return;
    stopping = true;
    log.info('Beende …', { signal });
    server.close();
    const force = setTimeout(() => process.exit(0), 8000);
    force.unref();
    try {
        await hub.stop();
    } catch { /* egal */ }
    store.flush();
    apns.close();
    process.exit(0);
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT', () => shutdown('SIGINT'));
