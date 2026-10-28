import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

// 은행(mock-bank) 응답 지연이 은행과 무관한 조회 API까지 번지는지 보는 시나리오.
// 앱은 --sai.mock-bank.base-url=http://localhost:18081 로 실행해 toxiproxy를 거치게 한다.
// 준비: 01_cleanup.sql -> 02_seed.sql -> 07_account_sync.sql(@accounts_per_user=1), toxiproxy 컨테이너 실행.
// 일반: -e BANK_LATENCY_MS=0 -e TEST_ID=bank-normal / 지연: -e BANK_LATENCY_MS=2000 -e TEST_ID=bank-delay
// 동기화는 새 거래가 없어도 매번 은행을 호출하므로 시드를 다시 넣지 않고 반복 실행할 수 있다.
// 두 실행의 조회 API p95/p99, dropped_iterations, hikaricp/tomcat 지표를 비교한다.

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://toxiproxy:8474';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const TEST_ID = __ENV.TEST_ID || 'bank-latency';
const BANK_LATENCY_MS = nonNegativeInt(__ENV.BANK_LATENCY_MS || '0', 'BANK_LATENCY_MS');
const SYNC_USERS = positiveInt(__ENV.SYNC_USERS || '100', 'SYNC_USERS');
const READ_USERS = positiveInt(__ENV.READ_USERS || '100', 'READ_USERS');
const SYNC_RATE = positiveInt(__ENV.SYNC_RATE || '20', 'SYNC_RATE');
const READ_RATE = positiveInt(__ENV.READ_RATE || '30', 'READ_RATE');
const DURATION = __ENV.DURATION || '3m';
const TOXIC = `${TOXIPROXY_URL}/proxies/mock-bank/toxics`;
const READ_APIS = [
    ['/api/integration/dashboard', 'integration-dashboard'],
    ['/api/notifications', 'notification-list'],
    ['/api/settlements/summary', 'settlement-summary'],
];

export const options = {
    setupTimeout: '10m',
    tags: { testid: TEST_ID },
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    scenarios: {
        sync: {
            executor: 'constant-arrival-rate', exec: 'sync', rate: SYNC_RATE, timeUnit: '1s',
            duration: DURATION, preAllocatedVUs: SYNC_RATE * 3, maxVUs: SYNC_RATE * 20,
        },
        read: {
            executor: 'constant-arrival-rate', exec: 'read', rate: READ_RATE, timeUnit: '1s',
            duration: DURATION, preAllocatedVUs: READ_RATE * 2, maxVUs: READ_RATE * 10,
        },
    },
    thresholds: {
        'http_req_duration{name:integration-dashboard}': ['p(95)<1000'],
        'http_req_duration{name:notification-list}': ['p(95)<300'],
        'http_req_duration{name:settlement-summary}': ['p(95)<1000'],
        'http_req_failed{scenario:read}': ['rate<0.01'],
    },
};

export function setup() {
    const proxy = http.get(`${TOXIPROXY_URL}/proxies/mock-bank`, { tags: { name: 'toxiproxy' } });
    if (proxy.status !== 200) throw new Error('toxiproxy mock-bank 프록시가 없습니다. toxiproxy 컨테이너를 확인하세요.');
    removeLatency();

    const syncUsers = [];
    for (let n = 1; n <= SYNC_USERS; n++) {
        const token = signIn(n);
        const res = http.get(`${BASE_URL}/api/linked-accounts`, auth(token, 'setup-accounts'));
        if (res.status !== 200) throw new Error(`${userEmail(n)}: 계좌 조회 실패 HTTP ${res.status}`);
        const ids = res.json().filter((a) => a.connectionStatus === 'AVAILABLE').map((a) => a.linkedAccountId);
        if (!ids.length) throw new Error(`${userEmail(n)}: AVAILABLE 계좌 없음`);
        syncUsers.push({ token, id: ids[0] });
    }
    const readTokens = [];
    for (let n = SYNC_USERS + 1; n <= SYNC_USERS + READ_USERS; n++) readTokens.push(signIn(n));

    if (BANK_LATENCY_MS > 0) {
        const res = http.post(TOXIC, JSON.stringify({
            name: 'bank-latency', type: 'latency', stream: 'downstream',
            attributes: { latency: BANK_LATENCY_MS, jitter: 0 },
        }), { headers: { 'Content-Type': 'application/json' }, tags: { name: 'toxiproxy' } });
        if (res.status !== 200) throw new Error(`지연 설정 실패 HTTP ${res.status}`);
    }
    // 앱이 toxiproxy를 거치는지 확인한다. 거치지 않으면 지연이 적용되지 않는다.
    const probe = http.post(`${BASE_URL}/api/linked-accounts/${syncUsers[0].id}/sync`, null,
        auth(syncUsers[0].token, 'setup-probe'));
    if (probe.status !== 200) throw new Error(`동기화 확인 실패 HTTP ${probe.status}`);
    if (probe.timings.duration < BANK_LATENCY_MS) {
        removeLatency();
        throw new Error('동기화가 지연보다 빨리 끝났습니다. 앱의 sai.mock-bank.base-url이 18081인지 확인하세요.');
    }
    console.log(`준비 완료: 은행 지연 ${BANK_LATENCY_MS}ms, 동기화 ${SYNC_RATE}/s, 조회 ${READ_RATE}/s, ${DURATION}`);
    return { syncUsers, readTokens };
}

export function sync(data) {
    const user = data.syncUsers[exec.scenario.iterationInTest % data.syncUsers.length];
    const res = http.post(`${BASE_URL}/api/linked-accounts/${user.id}/sync`, null, auth(user.token, 'account-sync'));
    check(res, { 'sync HTTP 200': (r) => r.status === 200 });
}

export function read(data) {
    const i = exec.scenario.iterationInTest;
    const [path, name] = READ_APIS[i % READ_APIS.length];
    const res = http.get(`${BASE_URL}${path}`, auth(data.readTokens[i % data.readTokens.length], name));
    check(res, { [`${name} status is 200`]: (r) => r.status === 200 });
}

export function teardown() {
    removeLatency();
}

function removeLatency() {
    http.del(`${TOXIC}/bank-latency`, null, {
        tags: { name: 'toxiproxy' }, responseCallback: http.expectedStatuses(204, 404),
    });
}

function signIn(n) {
    const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email: userEmail(n), password: PASSWORD }), {
        headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup-login' },
    });
    if (res.status !== 200) throw new Error(`${userEmail(n)}: 로그인 실패 HTTP ${res.status}`);
    const token = res.json('accessToken');
    if (!token) throw new Error(`${userEmail(n)}: accessToken 없음`);
    return token;
}

function auth(token, name) {
    return { headers: { Authorization: `Bearer ${token}` }, tags: { name }, timeout: '60s' };
}

function userEmail(n) {
    return `lt_user_${String(n).padStart(4, '0')}@sai.com`;
}

function positiveInt(value, name) {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1) throw new Error(`${name}은 양의 정수여야 합니다.`);
    return n;
}

function nonNegativeInt(value, name) {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 0) throw new Error(`${name}은 0 이상의 정수여야 합니다.`);
    return n;
}
