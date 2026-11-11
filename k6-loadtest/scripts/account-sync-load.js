import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// 계좌 동기화 집중 부하. 실행 예:
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e NEW_TRANSACTIONS=20 /scripts/account-sync-load.js
// 준비: lt_user_0001@sai.com부터 최대 VU 수만큼 계정과 AVAILABLE 연결 계좌.
// SQL seed의 가상 account_id만으로는 부족하며 mock-bank에 실제 대응 계좌가 필요하다.
// 은행 mock은 매 요청의 afterTransactionId 이후 고유 ID의 신규 거래를
// NEW_TRANSACTIONS개씩 반환하도록 별도 준비한다. 이 스크립트는 은행 데이터를 생성하지 않는다.
// 같은 거래를 반복 반환하면 신규 저장 0건이 되어 즉시 중단한다. 외부 동기화/삭제는 중지한다.
// VU별 사용자를 고정하고 소유 계좌를 순환한다. 로그인/계좌 조회는 setup에서 끝낸다.
// 저장 건수는 거래 조회 API의 totalCount 전후 차이로 측정한다 (매칭 응답 수 사용 금지).
// 검증 SELECT도 DB 부하에 포함되지만 sync_latency에는 포함되지 않는다.
// sync_latency p95/p99, sync_accounts count/rate(성공 계좌/초),
// sync_transactions_stored count/rate(관측 저장 거래 총합/초)를 확인한다.
// sync_transactions_expected는 실패 요청도 포함한 기대 신규 거래 수이다.
// InfluxDB/Grafana에서 rate와 vus를 시간대로 비교한다. 최종 rate는 전체 구간 평균이다.
// DB 지표는 별도 DB 모니터링과 같은 시간대로 대조한다. 현재 compose는 앱만 수집한다.
// mysqld_exporter 연결 후 mysql_global_status_threads_connected/threads_running,
// DB CPU는 DB 프로세스/컨테이너 CPU 또는 RDS CPUUtilization을 확인한다.
// hikaricp_connections_active/pending은 DB 전체 연결 수가 아닌 앱 커넥션 풀 지표이다.
// DB 저장 건수 교차 확인 (테스트 계좌로 제한한 SQL의 실행 전후 차이):
// SELECT linked_account_id, COUNT(*) FROM bank_transaction
// WHERE linked_account_id IN (...) GROUP BY linked_account_id;

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const LEVELS = (__ENV.VU_LEVELS || '10,25,50,100').split(',').map(Number);
const NEW_TRANSACTIONS = positiveInt(__ENV.NEW_TRANSACTIONS || '20', 'NEW_TRANSACTIONS');
const FIRST_USER = positiveInt(__ENV.FIRST_USER || '1', 'FIRST_USER');
const P95 = positiveInt(__ENV.P95_MS || '2000', 'P95_MS');
const P99 = positiveInt(__ENV.P99_MS || '5000', 'P99_MS');
if (LEVELS.some((v, i) => !Number.isInteger(v) || v < 1 || (i > 0 && v <= LEVELS[i - 1]))) {
    throw new Error('VU_LEVELS는 증가하는 양의 정수 목록이어야 합니다.');
}
const stages = [];
LEVELS.forEach((target) => {
    stages.push({ duration: __ENV.RAMP_DURATION || '30s', target });
    stages.push({ duration: __ENV.HOLD_DURATION || '2m', target });
});
stages.push({ duration: '30s', target: 0 });
const latency = new Trend('sync_latency', true);
const accounts = new Counter('sync_accounts');
const stored = new Counter('sync_transactions_stored');
const expected = new Counter('sync_transactions_expected');
const failures = new Rate('sync_failed');
const verified = new Rate('sync_fixed_transaction_count');

export const options = {
    setupTimeout: '10m',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    scenarios: {
        account_sync: {
            executor: 'ramping-vus', startVUs: 0, stages,
            gracefulRampDown: '1m', gracefulStop: '1m',
        },
    },
    thresholds: {
        sync_latency: [`p(95)<${P95}`, `p(99)<${P99}`],
        sync_failed: ['rate<0.01'],
        sync_fixed_transaction_count: ['rate==1'],
        checks: ['rate==1'],
    },
};

export function setup() {
    const users = [];
    const seen = new Set();
    for (let i = 0; i < LEVELS[LEVELS.length - 1]; i++) {
        const email = `lt_user_${String(FIRST_USER + i).padStart(4, '0')}@sai.com`;
        const res = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
            headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup-login' },
        });
        if (res.status !== 200) throw new Error(`${email}: 로그인 실패 HTTP ${res.status}`);
        const token = res.json('accessToken');
        if (!token) throw new Error(`${email}: accessToken 없음`);
        const listRes = http.get(`${BASE_URL}/api/linked-accounts`, params(token, 'setup-accounts'));
        if (listRes.status !== 200) throw new Error(`${email}: 계좌 조회 실패`);
        const list = listRes.json();
        if (!Array.isArray(list)) throw new Error(`${email}: 계좌 목록 형식 오류`);
        const ids = list.filter((a) => a.connectionStatus === 'AVAILABLE').map((a) => a.linkedAccountId);
        if (!ids.length) throw new Error(`${email}: AVAILABLE 계좌 없음`);
        ids.forEach((id) => {
            if (!Number.isSafeInteger(id) || id < 1 || seen.has(id)) throw new Error('계좌 ID 오류/중복');
            seen.add(id);
        });
        users.push({ token, ids });
    }
    console.log(`준비 완료: 사용자 ${users.length}, 계좌 ${seen.size}, 요청당 신규 거래 ${NEW_TRANSACTIONS}`);
    return { users };
}

export default function (data) {
    const user = data.users[exec.vu.idInTest - 1];
    if (!user) exec.test.abort('VU에 대응하는 사용자 없음');
    const id = user.ids[exec.vu.iterationInScenario % user.ids.length];
    const path = `${BASE_URL}/api/linked-accounts/${id}`;
    const before = savedCount(path, user.token);
    if (before === null) {
        verified.add(false);
        sleep(1);
        return;
    }
    expected.add(NEW_TRANSACTIONS);
    const res = http.post(`${path}/sync`, null, params(user.token, 'account-sync'));
    latency.add(res.timings.duration);
    const ok = check(res, { 'sync HTTP 200': (r) => r.status === 200 });
    failures.add(!ok);
    accounts.add(ok ? 1 : 0);
    // HTTP 실패여도 저장 후 매칭 중 실패했을 수 있어 저장 차이를 조회한다.
    const after = savedCount(path, user.token);
    const delta = after === null ? null : after - before;
    if (delta !== null && delta >= 0) stored.add(delta);
    const fixed = delta === NEW_TRANSACTIONS;
    verified.add(fixed);
    check(delta, { '신규 저장 거래 수 고정': () => fixed });
    if (!fixed) exec.test.abort(`계좌 ${id}: 신규 거래 ${delta}, 기대 ${NEW_TRANSACTIONS}. 은행 fixture/커서/외부 쓰기를 확인하세요.`);
}

function params(token, name) {
    return { headers: { Authorization: `Bearer ${token}` }, tags: { name }, timeout: '30s' };
}

function savedCount(path, token) {
    const res = http.get(`${path}/transactions?page=0&size=1`, params(token, 'verify-stored-count'));
    let count = null;
    try { count = res.json('totalCount'); } catch (_) { /* check에서 실패 처리 */ }
    const ok = check(res, {
        '저장 건수 조회 성공': (r) => r.status === 200 && Number.isSafeInteger(count) && count >= 0,
    });
    return ok ? count : null;
}

function positiveInt(value, name) {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1) throw new Error(`${name}은 양의 정수여야 합니다.`);
    return n;
}
