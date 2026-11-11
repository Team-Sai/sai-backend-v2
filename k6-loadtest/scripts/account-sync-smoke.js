import http from 'k6/http';
import { check, fail } from 'k6';

// Smoke check before account-sync-consistency.js on the deployed server.
// Read-only by default: login + linked account list for a few users.
// SYNC=1 also syncs one account of SYNC_USER, which consumes that account's new
// transactions, so pick a user outside the consistency run (or reseed afterwards).
const BASE = (__ENV.DEPLOYED_URL || 'https://saiwonjang.shinhanacademy.co.kr').replace(/\/+$/, '');
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const USERS = positiveInt(__ENV.USERS || '3', 'USERS');
const FIRST = positiveInt(__ENV.FIRST_USER || '1', 'FIRST_USER');
const PER_USER = positiveInt(__ENV.ACCOUNTS_PER_USER || '3', 'ACCOUNTS_PER_USER');
const PREFIX = __ENV.ACCOUNT_ALIAS_PREFIX || '100명 3계좌 테스트 통장 ';
const SYNC = __ENV.SYNC === '1';
const SYNC_USER = positiveInt(__ENV.SYNC_USER || String(FIRST), 'SYNC_USER');
const TIMEOUT = __ENV.REQUEST_TIMEOUT || '30s';

export const options = {
    vus: 1,
    iterations: 1,
    tags: { test_run: 'account-sync-smoke' },
    thresholds: {
        checks: ['rate==1'],
    },
};

export default function () {
    console.log(`BASE=${BASE}, users=${FIRST}..${FIRST + USERS - 1}, sync=${SYNC ? `user ${SYNC_USER}` : 'off'}`);

    for (let i = 0; i < USERS; i++) {
        inspectUser(FIRST + i, false);
    }
    if (SYNC) {
        inspectUser(SYNC_USER, true);
    }
}

function inspectUser(n, sync) {
    const email = `lt_user_${String(n).padStart(4, '0')}@sai.com`;
    const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' },
        timeout: TIMEOUT,
        tags: { name: 'smoke-login' },
    });
    const token = login.status === 200 ? login.json('accessToken') : null;
    if (!check(login, { 'login 200 with token': () => !!token })) {
        console.error(`${email}: login HTTP ${login.status}, error=${login.error || ''}, body=${String(login.body || '').slice(0, 300)}`);
        return;
    }

    const response = http.get(`${BASE}/api/linked-accounts`, params(token, 'smoke-accounts'));
    if (!check(response, { 'accounts 200': (r) => r.status === 200 })) {
        console.error(`${email}: accounts HTTP ${response.status}, body=${String(response.body || '').slice(0, 300)}`);
        return;
    }
    const list = response.json();
    const available = list.filter((a) => a.connectionStatus === 'AVAILABLE');
    const selected = available.filter((a) =>
        typeof a.accountAlias === 'string' && a.accountAlias.startsWith(PREFIX));
    const ok = check(selected, {
        [`${PER_USER} AVAILABLE accounts with prefix`]: () =>
            available.length === PER_USER && selected.length === PER_USER,
    });
    console.log(`${email}: total=${list.length}, available=${available.length}, selected=${selected.length}, `
        + `aliases=${JSON.stringify(list.map((a) => `${a.accountAlias}(${a.connectionStatus})`))}`);
    if (!ok || !sync) return;

    const id = selected[0].linkedAccountId;
    const synced = http.post(`${BASE}/api/linked-accounts/${id}/sync`, null, params(token, 'smoke-sync'));
    check(synced, { 'sync 200': (r) => r.status === 200 });
    console.log(`${email}: sync linkedAccountId=${id}, HTTP=${synced.status}, ${synced.timings.duration.toFixed(0)}ms, `
        + `body=${String(synced.body || '').slice(0, 300)}`);
}

function params(token, name) {
    return { headers: { Authorization: `Bearer ${token}` }, timeout: TIMEOUT, tags: { name } };
}

function positiveInt(value, name) {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1) fail(`${name} must be a positive integer`);
    return n;
}