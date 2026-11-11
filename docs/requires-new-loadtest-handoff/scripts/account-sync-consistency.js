import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// Each VU owns one user and visits each selected account exactly once.
// No automatic retries: a retry could repair the inconsistency being measured.
// Run final SQL verification only after server-side work has finished.
const BASE = (__ENV.BASE_URL || 'http://host.docker.internal:18080').replace(/\/+$/, '');
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const USERS = positiveInt(__ENV.USERS || '100', 'USERS');
const FIRST = positiveInt(__ENV.FIRST_USER || '1', 'FIRST_USER');
const PER_USER = positiveInt(__ENV.ACCOUNTS_PER_USER || '3', 'ACCOUNTS_PER_USER');
// The list API exposes a masked number, so validate using the fixture alias.
const PREFIX = __ENV.ACCOUNT_ALIAS_PREFIX || '100명 3계좌 테스트 통장 ';
const TIMEOUT = __ENV.REQUEST_TIMEOUT || '30s';
const RUN = __ENV.RUN_LABEL || 'RCB01';

const attempted = new Counter('consistency_sync_attempted');
const successful = new Counter('consistency_sync_http_200');
const failed = new Rate('consistency_sync_failed');
const latency = new Trend('consistency_sync_latency', true);

export const options = {
    setupTimeout: '10m',
    scenarios: {
        sync_once: {
            executor: 'per-vu-iterations',
            vus: USERS,
            iterations: 1,
            maxDuration: __ENV.MAX_DURATION || '10m',
            gracefulStop: '1m',
        },
    },
    tags: { test_run: RUN },
    summaryTrendStats: ['avg', 'med', 'max', 'p(95)', 'p(99)'],
    thresholds: {
        consistency_sync_attempted: [`count==${USERS * PER_USER}`],
        consistency_sync_failed: ['rate==0'],
        checks: ['rate==1'],
    },
};

export function setup() {
    const users = [];
    const seen = new Set();
    for (let i = 0; i < USERS; i++) {
        const email = `lt_user_${String(FIRST + i).padStart(4, '0')}@sai.com`;
        const login = http.post(`${BASE}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
            headers: { 'Content-Type': 'application/json' },
            timeout: TIMEOUT,
            tags: { name: 'setup-login' },
        });
        if (login.status !== 200) throw new Error(`${email}: login HTTP ${login.status}`);
        const token = login.json('accessToken');
        if (!token) throw new Error(`${email}: missing accessToken`);
        const response = http.get(`${BASE}/api/linked-accounts`, params(token, 'setup-accounts'));
        if (response.status !== 200) throw new Error(`${email}: accounts HTTP ${response.status}`);
        const list = response.json();
        if (!Array.isArray(list)) throw new Error(`${email}: invalid account list`);
        const available = list.filter((a) => a.connectionStatus === 'AVAILABLE');
        const selected = available.filter((a) =>
            typeof a.accountAlias === 'string' && a.accountAlias.startsWith(PREFIX));
        if (available.length !== PER_USER || selected.length !== PER_USER) {
            throw new Error(`${email}: expected ${PER_USER} AVAILABLE ${PREFIX} accounts; available=${available.length}, selected=${selected.length}`);
        }
        const ids = selected.map((a) => a.linkedAccountId).sort((a, b) => a - b);
        for (const id of ids) {
            if (!Number.isSafeInteger(id) || id < 1 || seen.has(id)) {
                throw new Error(`${email}: invalid/duplicate account ID ${id}`);
            }
            seen.add(id);
        }
        users.push({ token, ids });
    }
    console.log(`READY run=${RUN}, users=${users.length}, accounts=${seen.size}, no retries`);
    return { users };
}

export default function (data) {
    const user = data.users[exec.vu.idInTest - 1];
    if (!user) throw new Error('Missing VU user');
    for (const id of user.ids) {
        attempted.add(1);
        const response = http.post(`${BASE}/api/linked-accounts/${id}/sync`, null,
            params(user.token, 'account-sync-once'));
        latency.add(response.timings.duration);
        const ok = check(response, { 'sync HTTP 200': (r) => r.status === 200 });
        successful.add(ok ? 1 : 0);
        failed.add(!ok);
        if (!ok) {
            console.warn(`run=${RUN}, linkedAccountId=${id}, HTTP=${response.status}, error=${response.error || ''}, body=${String(response.body || '').slice(0, 500)}`);
        }
    }
}

function params(token, name) {
    return { headers: { Authorization: `Bearer ${token}` }, timeout: TIMEOUT, tags: { name } };
}

function positiveInt(value, name) {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1) throw new Error(`${name} must be a positive integer`);
    return n;
}
