// Local-only Slack webhook sink / evidence verification. No external messages are sent.
// node k6-loadtest/tools/batch-evidence.mjs capture evidence/webhooks.jsonl 18081
// node k6-loadtest/tools/batch-evidence.mjs verify evidence/webhooks.jsonl evidence/exhaustion-ids.txt evidence/app.log [evidence/error-account-ids.txt]
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
const [mode, file, ...args] = process.argv.slice(2);
if (!file || !['capture', 'verify'].includes(mode)) throw Error('See usage at top of batch-evidence.mjs');
if (mode === 'capture') {
    const port = Number(args[0] || 18081);
    if (!Number.isInteger(port) || port < 1 || port > 65535) throw Error('Invalid port');
    fs.mkdirSync(path.dirname(path.resolve(file)), { recursive: true });
    const server = http.createServer((req, res) => {
        if (req.method !== 'POST' || req.url !== '/webhook') { res.writeHead(404).end(); return; }
        let body = '';
        req.on('data', (chunk) => { body += chunk; if (body.length > 1000000) req.destroy(); });
        req.on('end', () => {
            try {
                const data = JSON.parse(body);
                if (typeof data.text !== 'string') throw Error('text required');
                fs.appendFileSync(file, JSON.stringify({ receivedAt: new Date().toISOString(), text: data.text }) + '\n');
                res.writeHead(200).end('ok');
            } catch (_) { res.writeHead(400).end('invalid payload'); }
        });
    });
    server.listen(port, '127.0.0.1', () => console.log(`Webhook capture: http://127.0.0.1:${port}/webhook -> ${path.resolve(file)}`));
} else {
    const [expectedFile, logFile, errorAccountsFile, persistentErrorFile] = args;
    if (!expectedFile || !logFile) throw Error('Expected exhaustion IDs and application log files required');
    const ids = (f) => {
        const values = fs.readFileSync(f, 'utf8').replace(/^\uFEFF/, '').trim().split(/\s+/).filter(Boolean);
        if (values.some((v) => !/^\d+$/.test(v))) throw Error(`${f}: headerless numeric IDs required`);
        return new Set(values);
    };
    const expected = ids(expectedFile);
    const actual = new Map();
    let summaries = 0;
    for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/).filter(Boolean)) {
        const { text } = JSON.parse(line);
        if (typeof text !== 'string') throw Error('Invalid webhook evidence');
        if (text.includes('은행거래 매칭 재시도 소진')) {
            const match = /bankTransactionId=(\d+), status=(\w+), retryCount=(\d+)\/(\d+)/.exec(text);
            if (!match) throw Error('Malformed exhaustion notification');
            const [, id, status, count, max] = match;
            const limit = status === 'FAILED' ? 5 : ['UNMATCHED', 'NEEDS_CHECK'].includes(status) ? 14 : -1;
            if (Number(max) !== limit || Number(count) !== limit) throw Error(`Incorrect retry limit: ${text}`);
            actual.set(id, (actual.get(id) || 0) + 1);
        } else { summaries++; }
    }
    for (const id of expected) if (actual.get(id) !== 1) throw Error(`Exhaustion ${id}: expected once, got ${actual.get(id) || 0}`);
    for (const id of actual.keys()) if (!expected.has(id)) throw Error(`Unexpected exhaustion ${id}`);
    const log = fs.readFileSync(logFile, 'utf8');
    if (!log.includes('[BATCH SUCCESS] job=bankTransactionRetryJob')) throw Error('No retry Job success log');
    if (log.includes('[BATCH FAILED]')) throw Error('Failed Job found in captured test logs');
    if (errorAccountsFile && errorAccountsFile !== '-') for (const id of ids(errorAccountsFile)) {
        const found = log.split(/\r?\n/).some((line) => line.includes('[BATCH SKIP - PROCESS]') &&
            new RegExp(`linkedAccountId=${id}(?:\\D|$)`).test(line));
        if (!found) throw Error(`No process-skip log for fault account ${id}`);
    }
    if (persistentErrorFile) for (const id of ids(persistentErrorFile)) {
        const pattern = new RegExp(`Auto matching transaction failed\\. transactionId=${id},`, 'g');
        const count = [...log.matchAll(pattern)].length;
        const rounds = Number(process.env.ROUNDS || 15);
        if (!Number.isSafeInteger(rounds) || rounds < 1) throw Error('ROUNDS must be positive');
        if (count !== Math.min(5, rounds)) throw Error(`Transaction ${id}: expected ${Math.min(5, rounds)} error logs, got ${count}`);
    }
    console.log(`PASS: ${expected.size} exhaustion notifications, no missing/duplicate/unexpected IDs; ${summaries} other webhook messages; Job logs checked.`);
}

