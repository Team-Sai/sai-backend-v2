import http from 'k6/http';
import { check, group, sleep } from 'k6';
import exec from 'k6/execution';

// 상환일(오늘) 09시 D-day 알림 발송 직후 사용자가 한꺼번에 접속하는 상황
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const TEST_ID = __ENV.TEST_ID || 'surge';
const USER_COUNT = 1000;
const HEAVY_USER_COUNT = 10;
const BASE_VUS = Number(__ENV.BASE_VUS || 30);
const PEAK_VUS = Number(__ENV.PEAK_VUS || 300);
const RELOGIN_RATE = Number(__ENV.RELOGIN_RATE || 0.05);

const JSON_HEADERS = { 'Content-Type': 'application/json' };

const STAGES = [
    { duration: '30s', target: BASE_VUS, phase: 'baseline' },
    { duration: '90s', target: BASE_VUS, phase: 'baseline' },
    { duration: '30s', target: PEAK_VUS, phase: 'surge' },
    { duration: '3m', target: PEAK_VUS, phase: 'peak' },
    { duration: '30s', target: BASE_VUS, phase: 'recovery' },
    { duration: '2m', target: BASE_VUS, phase: 'recovery' },
    { duration: '30s', target: 0, phase: 'end' },
];

const PHASE_ENDS = [];
STAGES.reduce((sum, stage) => {
    const end = sum + toSeconds(stage.duration);
    PHASE_ENDS.push({ end, phase: stage.phase });
    return end;
}, 0);
const TOTAL_SECONDS = PHASE_ENDS[PHASE_ENDS.length - 1].end;

export const options = {
    tags: { testid: TEST_ID },
    scenarios: {
        normal_users: {
            executor: 'ramping-vus',
            exec: 'normalUser',
            startVUs: 0,
            stages: STAGES.map(({ duration, target }) => ({ duration, target })),
            gracefulRampDown: '30s',
            tags: { user_type: 'normal' },
        },
        heavy_users: {
            executor: 'constant-vus',
            exec: 'heavyUser',
            vus: HEAVY_USER_COUNT,
            duration: `${TOTAL_SECONDS}s`,
            tags: { user_type: 'heavy' },
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
        'http_req_duration{user_type:normal}': ['p(95)<500', 'p(99)<1500'],
        'http_req_duration{user_type:heavy}': ['p(95)<2000'],
        'http_req_duration{name:login}': ['p(95)<1000'],
        'http_req_duration{name:integration-dashboard}': ['p(95)<1000'],
        'http_req_duration{name:notification-list}': ['p(95)<300'],
        'http_req_duration{name:contract-detail}': ['p(95)<500'],
        'http_req_duration{name:contract-schedules}': ['p(95)<500'],
        'http_req_duration{name:settlement-summary}': ['p(95)<1000'],
    },
};

export function setup() {
    const res = login(userEmail(1), 'setup');
    if (res.status !== 200) {
        throw new Error(`시드 계정 로그인 실패 (HTTP ${res.status}). seed/02_seed.sql 을 먼저 실행하세요.`);
    }
}

let session = null;

export function normalUser() {
    const n = HEAVY_USER_COUNT + 1 + ((exec.vu.idInTest - 1) % (USER_COUNT - HEAVY_USER_COUNT));
    runJourney(n);
}

export function heavyUser() {
    const n = 1 + ((exec.vu.idInTest - 1) % HEAVY_USER_COUNT);
    runJourney(n);
}

function runJourney(n) {
    if (!session || session.n !== n || Math.random() < RELOGIN_RATE) {
        if (!signIn(n)) {
            sleep(1);
            return;
        }
    }

    // 알림을 보고 들어온 사용자가 많으므로 상환일 확인 흐름 비중을 높게 둔다
    const roll = Math.random();
    if (roll < 0.35) dueReminderJourney();
    else if (roll < 0.65) homeJourney();
    else if (roll < 0.8) settlementJourney();
    else if (roll < 0.95) contractJourney();
    else riskCheckJourney();
}

// 1) 알림 목록 -> 오늘 상환일 알림 클릭 -> 계약 상세 -> 상환 일정
function dueReminderJourney() {
    group('due-reminder', () => {
        const list = get('/api/notifications', 'notification-list');
        think();

        const notifications = jsonValue(list, '') || [];
        // 목록은 최신순이므로 가장 최근 날짜의 상환일 알림만 고른다
        const reminders = notifications.filter((item) => item.notificationType === 'REPAYMENT_DUE_REMINDER_DDAY');
        const latestDate = reminders.length > 0 ? String(reminders[0].createdAt).slice(0, 10) : null;
        const due = reminders.filter((item) => String(item.createdAt).startsWith(latestDate));
        if (due.length === 0) {
            get('/api/integration/dashboard', 'integration-dashboard');
            think();
            return;
        }

        const contractId = pick(due).secondaryReferenceId;
        get(`/api/contracts/${contractId}`, 'contract-detail');
        think();
        get(`/api/contracts/${contractId}/schedules`, 'contract-schedules');
        think();
    });
}

// 2) 홈: 통합 대시보드 + 알림
function homeJourney() {
    group('home', () => {
        get('/api/integration/dashboard', 'integration-dashboard');
        think();
        get('/api/notifications', 'notification-list');
        think();
    });
}

// 3) 정산 요약 -> 목록 -> 상세 -> 납부 현황
function settlementJourney() {
    group('settlement', () => {
        get('/api/settlements/summary', 'settlement-summary');
        const list = get('/api/settlements', 'settlement-list');
        think();

        const settlements = jsonValue(list, '') || [];
        if (settlements.length === 0) return;
        const settlementId = pick(settlements).settlementId;

        get(`/api/settlements/${settlementId}`, 'settlement-detail');
        get(`/api/settlements/${settlementId}/payment-status`, 'settlement-payment-status');
        think();
    });
}

// 4) 차용증 목록 -> 상세
function contractJourney() {
    group('contract', () => {
        const first = get('/api/contracts/dashboard?page=1', 'contract-dashboard');
        think();

        const contracts = jsonValue(first, 'contracts') || [];
        if (contracts.length === 0) return;
        get(`/api/contracts/${pick(contracts).contractId}`, 'contract-detail');
        think();
    });
}

// 5) 차용증 작성 전 위험 진단
function riskCheckJourney() {
    group('risk-check', () => {
        get('/api/contracts/previous-sum', 'contract-previous-sum');
        think();
    });
}

function signIn(n) {
    const res = login(userEmail(n));
    const ok = check(res, { 'login status is 200': (r) => r.status === 200 });
    session = ok ? { n, token: res.json('accessToken') } : null;
    return ok;
}

function login(email, phase = currentPhase()) {
    return http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ email, password: PASSWORD }),
        { headers: JSON_HEADERS, tags: { name: 'login', phase } }
    );
}

function get(path, name) {
    const res = http.get(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${session.token}` },
        tags: { name, phase: currentPhase() },
    });
    check(res, { [`${name} status is 200`]: (r) => r.status === 200 });
    if (res.status === 401) session = null;
    return res;
}

function currentPhase() {
    const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
    const found = PHASE_ENDS.find((p) => elapsed < p.end);
    return found ? found.phase : 'end';
}

function jsonValue(res, selector) {
    if (res.status !== 200) return null;
    try {
        return selector ? res.json(selector) : res.json();
    } catch (e) {
        return null;
    }
}

function userEmail(n) {
    return `lt_user_${String(n).padStart(4, '0')}@sai.com`;
}

function pick(items) {
    return items[Math.floor(Math.random() * items.length)];
}

function think() {
    sleep(1 + Math.random() * 2);
}

function toSeconds(duration) {
    const match = /^(\d+)(s|m|h)$/.exec(duration);
    const unit = { s: 1, m: 60, h: 3600 }[match[2]];
    return Number(match[1]) * unit;
}
