import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// 준비: seed/01_cleanup.sql -> 02_seed.sql -> 03_matching_bulk.sql.
// mock-bank는 선택 계좌의 동기화 요청에 200 + 빈 거래 목록을 반환하도록 준비한다.
// DB의 PENDING 거래를 sync API가 자동매칭한다. 외부 스케줄러/동기화는 중지한다.
// 실행:
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run /scripts/matching-bulk.js
// -e USERS_PER_GROUP=100 -e TRANSACTIONS_PER_ACCOUNT=100 (SQL 설정과 일치)
// -e VUS_PER_GROUP=10 -e MAX_DURATION=15m -e COHORT=all|matchable|ambiguous|unmatched
// 각 계좌는 한 번만 처리한다. 반복 측정/그룹별 단독 비교 전에 반드시 seed를 초기화한다.
// all은 세 그룹 동시 부하. 격리 성능 비교는 COHORT별로 같은 VU/데이터 수를 사용한다.
// matching_latency는 sync+자동매칭 API 시간만 측정한다. 저장 상태 검증 GET은 제외한다.
// matching_transactions count/rate와 상태별 Counter를 cohort 태그로 비교한다.
// 전체 rate는 setup/검증 시간을 포함하는 실행 평균이며 시간대별 rate는 InfluxDB에서 확인한다.
// 검증은 샘플링 없이 모든 거래를 대상으로 하며 검증 SELECT 부하도 DB에 포함된다.

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const USERS = positiveInt(__ENV.USERS_PER_GROUP || '100', 'USERS_PER_GROUP');
const TRADES = positiveInt(__ENV.TRANSACTIONS_PER_ACCOUNT || '100', 'TRANSACTIONS_PER_ACCOUNT');
const VUS = positiveInt(__ENV.VUS_PER_GROUP || '10', 'VUS_PER_GROUP');
const ALL = ['matchable', 'ambiguous', 'unmatched'];
const SELECTED = __ENV.COHORT || 'all';
if (SELECTED !== 'all' && !ALL.includes(SELECTED)) throw new Error('COHORT 설정 오류');
if (USERS * 3 > 1000 || TRADES > 1000 || VUS > USERS) throw new Error('SQL seed 범위 또는 VU 수 확인');
const GROUPS = SELECTED === 'all' ? ALL : [SELECTED];
const EXPECTED = { matchable: 'APPLIED', ambiguous: 'NEEDS_CHECK', unmatched: 'UNMATCHED' };
const latency = new Trend('matching_latency', true);
const processed = new Counter('matching_transactions');
const accountCount = new Counter('matching_accounts');
const apiSuccess = new Rate('matching_api_success');
const accuracy = new Rate('matching_final_state_accuracy');
const resultAccuracy = new Rate('matching_response_accuracy');
const states = {};
['APPLIED', 'UNMATCHED', 'NEEDS_CHECK', 'DUPLICATE', 'FAILED'].forEach((s) => {
    states[s] = new Counter(`matching_${s.toLowerCase()}`);
});
const scenarios = {};
const thresholds = { checks: ['rate==1'] };
GROUPS.forEach((cohort) => {
    scenarios[cohort] = {
        executor: 'shared-iterations', vus: VUS, iterations: USERS,
        maxDuration: __ENV.MAX_DURATION || '15m', exec: 'matchAccount', tags: { cohort },
    };
    thresholds[`matching_api_success{cohort:${cohort}}`] = ['rate==1'];
    thresholds[`matching_response_accuracy{cohort:${cohort}}`] = ['rate==1'];
    thresholds[`matching_final_state_accuracy{cohort:${cohort}}`] = ['rate==1'];
    // 시간 초과로 일부 계좌가 실행되지 않은 경우도 실패 처리한다.
    thresholds[`matching_accounts{cohort:${cohort}}`] = [`count==${USERS}`];
    thresholds[`matching_transactions{cohort:${cohort}}`] = [`count==${USERS * TRADES}`];
    thresholds[`matching_latency{cohort:${cohort}}`] = [
        `p(95)<${positiveInt(__ENV.P95_MS || '10000', 'P95_MS')}`,
        `p(99)<${positiveInt(__ENV.P99_MS || '30000', 'P99_MS')}`,
    ];
});
export const options = {
    setupTimeout: '15m',
    summaryTrendStats: ['avg', 'med', 'max', 'p(95)', 'p(99)'],
    systemTags: ['status', 'method', 'name', 'scenario', 'expected_response'],
    scenarios, thresholds,
};

export function setup() {
    const groups = {};
    const used = new Set();
    GROUPS.forEach((cohort) => {
        groups[cohort] = [];
        const offset = ALL.indexOf(cohort) * USERS;
        for (let i = 1; i <= USERS; i++) {
            const email = `lt_user_${String(offset + i).padStart(4, '0')}@sai.com`;
            const login = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({ email, password: PASSWORD }), {
                headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup-login' },
            });
            const token = login.status === 200 ? json(login).accessToken : null;
            if (!token) throw new Error(`${email}: 로그인 실패`);
            const res = http.get(`${BASE_URL}/api/linked-accounts`, params(token, 'setup-accounts', cohort));
            const list = json(res);
            if (res.status !== 200 || !Array.isArray(list)) throw new Error(`${email}: 계좌 조회 실패`);
            const available = list.filter((a) => a.connectionStatus === 'AVAILABLE')
                .sort((a, b) => a.linkedAccountId - b.linkedAccountId);
            if (!available.length) throw new Error(`${email}: AVAILABLE 계좌 없음`);
            const id = available[0].linkedAccountId;
            if (used.has(id)) throw new Error('계좌 중복');
            used.add(id);
            const transactions = readTransactions(id, token, cohort, true);
            const prefix = `MLT:${cohort}:`;
            if (transactions.length !== TRADES || transactions.some((t) =>
                t.processingStatus !== 'PENDING' || t.transactionType !== 'DEPOSIT' ||
                typeof t.memo !== 'string' || !t.memo.startsWith(prefix))) {
                throw new Error(`${email}: PENDING fixture 불일치. SQL 초기화/그룹 설정을 확인하세요.`);
            }
            groups[cohort].push({ id, token, transactionIds: transactions.map((t) => t.bankTransactionId) });
        }
    });
    return groups;
}

export function matchAccount(data) {
    const cohort = exec.scenario.name;
    // scenario별 전역 iteration 번호: VU 수와 무관하게 계좌를 정확히 한 번 사용한다.
    const account = data[cohort][exec.scenario.iterationInTest];
    const tags = { cohort };
    const res = http.post(`${BASE_URL}/api/linked-accounts/${account.id}/sync`, null,
        params(account.token, 'matching-sync', cohort));
    latency.add(res.timings.duration, tags);
    const ok = res.status === 200;
    apiSuccess.add(ok, tags);
    check(res, { '동기화 자동매칭 HTTP 200': () => ok }, tags);
    const body = json(res);
    const results = Array.isArray(body.transactionResults) ? body.transactionResults : [];
    const counts = { APPLIED: 0, NEEDS_CHECK: 0, UNMATCHED: 0, DUPLICATE: 0, FAILED: 0 };
    const expectedIds = new Set(account.transactionIds);
    const seen = new Set();
    let responseOk = ok && body.totalTransactionCount === TRADES && results.length === TRADES;
    results.forEach((r) => {
        if (!expectedIds.has(r.transactionId) || seen.has(r.transactionId) || !(r.processStatus in counts)) {
            responseOk = false;
            return;
        }
        seen.add(r.transactionId);
        counts[r.processStatus]++;
        if (r.processStatus !== EXPECTED[cohort]) responseOk = false;
    });
    const fields = { APPLIED: 'appliedCount', NEEDS_CHECK: 'needsCheckCount', UNMATCHED: 'unmatchedCount',
        DUPLICATE: 'duplicateCount', FAILED: 'failedCount' };
    Object.keys(counts).forEach((s) => {
        states[s].add(counts[s], tags);
        if (body[fields[s]] !== counts[s]) responseOk = false;
    });
    processed.add(seen.size, tags);
    accountCount.add(ok ? 1 : 0, tags);
    resultAccuracy.add(responseOk, tags);
    check(body, { '거래별 응답 및 상태별 집계 정확': () => responseOk }, tags);

    let saved = [];
    let readable = true;
    try { saved = readTransactions(account.id, account.token, cohort, false); }
    catch (_) { readable = false; }
    const finalStates = new Map(saved.map((t) => [t.bankTransactionId, t.processingStatus]));
    account.transactionIds.forEach((id) => {
        const correct = readable && finalStates.get(id) === EXPECTED[cohort];
        accuracy.add(correct, tags);
        check(id, { '대상 거래 최종 저장 상태 정확': () => correct }, tags);
    });
}

function readTransactions(id, token, cohort, pendingOnly) {
    const rows = [];
    for (let page = 0; ; page++) {
        const filter = pendingOnly ? '&processingStatus=PENDING&transactionType=DEPOSIT' : '';
        const res = http.get(`${BASE_URL}/api/linked-accounts/${id}/transactions?page=${page}&size=100${filter}`,
            params(token, pendingOnly ? 'setup-pending' : 'verify-final-state', cohort));
        const body = json(res);
        if (res.status !== 200 || !Array.isArray(body.content) || !Number.isSafeInteger(body.totalCount) ||
            !Number.isSafeInteger(body.totalPages) || body.totalPages < 0 || page > 10000) {
            throw new Error(`계좌 ${id}: 거래 조회 실패`);
        }
        rows.push(...body.content);
        if (page + 1 >= body.totalPages) {
            if (rows.length !== body.totalCount || new Set(rows.map((t) => t.bankTransactionId)).size !== rows.length) {
                throw new Error(`계좌 ${id}: 페이지 정합성 오류`);
            }
            return rows;
        }
    }
}
function params(token, name, cohort) {
    return { headers: { Authorization: `Bearer ${token}` }, tags: { name, cohort }, timeout: '120s' };
}
function json(res) {
    try { return res.json() || {}; } catch (_) { return {}; }
}
function positiveInt(value, name) {
    const n = Number(value);
    if (!Number.isSafeInteger(n) || n < 1) throw new Error(`${name}: 양의 정수 필요`);
    return n;
}
