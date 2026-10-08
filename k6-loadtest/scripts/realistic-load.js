import http from 'k6/http';
import { check, group, sleep } from 'k6';
import exec from 'k6/execution';

// 시드 데이터(k6-loadtest/seed/02_seed.sql)가 들어간 DB 기준의 다중 사용자 시나리오
// 실행 예) docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e PROFILE=load /scripts/realistic-load.js

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PROFILE = __ENV.PROFILE || 'load';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const USER_COUNT = 1000;
const HEAVY_USER_COUNT = 10; // lt_user_0001~0010 : 계약·정산 100건 이상 보유
const RELOGIN_RATE = Number(__ENV.RELOGIN_RATE || 0.05); // 재방문(새 로그인) 비율

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 일반 사용자 트래픽 단계. 헤비 유저 시나리오는 모든 프로필에서 소수 VU 로 함께 돈다
const PROFILES = {
    smoke: [{ duration: '1m', target: 2 }],
    load: [
        { duration: '1m', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '5m', target: 100 },
        { duration: '1m', target: 0 },
    ],
    stress: [
        { duration: '2m', target: 100 },
        { duration: '3m', target: 200 },
        { duration: '3m', target: 300 },
        { duration: '3m', target: 400 },
        { duration: '2m', target: 0 },
    ],
    spike: [
        { duration: '1m', target: 20 },
        { duration: '10s', target: 300 },
        { duration: '1m', target: 300 },
        { duration: '10s', target: 20 },
        { duration: '1m', target: 20 },
    ],
    soak: [
        { duration: '2m', target: 80 },
        { duration: '30m', target: 80 },
        { duration: '1m', target: 0 },
    ],
};

if (!PROFILES[PROFILE]) {
    throw new Error(`알 수 없는 PROFILE: ${PROFILE} (${Object.keys(PROFILES).join(', ')})`);
}

const totalDuration = PROFILES[PROFILE]
    .map((stage) => stage.duration)
    .reduce((sum, d) => sum + toSeconds(d), 0);

export const options = {
    scenarios: {
        normal_users: {
            executor: 'ramping-vus',
            exec: 'normalUser',
            startVUs: 0,
            stages: PROFILES[PROFILE],
            gracefulRampDown: '30s',
            tags: { user_type: 'normal' },
        },
        heavy_users: {
            executor: 'constant-vus',
            exec: 'heavyUser',
            vus: PROFILE === 'smoke' ? 1 : 5,
            duration: `${totalDuration}s`,
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
        'http_req_duration{name:settlement-summary}': ['p(95)<1000'],
        'http_req_duration{name:contract-dashboard}': ['p(95)<500'],
        'http_req_duration{name:notification-list}': ['p(95)<300'],
    },
};

export function setup() {
    // 시드가 없으면 전부 401 이 나므로 시작 전에 확인한다
    const res = login(userEmail(1));
    if (res.status !== 200) {
        throw new Error(`시드 계정 로그인 실패 (HTTP ${res.status}). seed/02_seed.sql 을 먼저 실행하세요.`);
    }
}

// VU 마다 자기 계정 토큰을 들고 다닌다
let session = null;

export function normalUser() {
    // 헤비 유저를 제외한 990명을 VU 에 순서대로 배정
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

    // 실제 사용 비율을 흉내 낸 가중치. 한 iteration = 사용자 한 번의 방문 흐름
    const roll = Math.random();
    if (roll < 0.35) homeJourney();
    else if (roll < 0.6) contractJourney();
    else if (roll < 0.85) settlementJourney();
    else if (roll < 0.95) notificationJourney();
    else riskCheckJourney();
}

// 1) 로그인 직후 홈: 통합 대시보드 + 알림 배지
function homeJourney() {
    group('home', () => {
        get('/api/integration/dashboard', 'integration-dashboard');
        think();
        get('/api/notifications', 'notification-list');
        think();
    });
}

// 2) 차용증 목록 -> 필터/정렬/페이지 이동 -> 상세 -> 상환 스케줄
function contractJourney() {
    group('contract', () => {
        const first = get('/api/contracts/dashboard?page=1', 'contract-dashboard');
        think();

        const sort = pick(['AMOUNT_DESC', 'DEADLINE', 'CREATED_DESC', 'ALPHABET']);
        const role = pick(['ALL', 'LENT', 'BORROWED']);
        get(`/api/contracts/dashboard?page=1&roleFilter=${role}&sortType=${sort}`, 'contract-dashboard');
        think();

        const totalPages = jsonValue(first, 'totalPages') || 1;
        if (totalPages > 1) {
            get(`/api/contracts/dashboard?page=${2 + Math.floor(Math.random() * (totalPages - 1))}`, 'contract-dashboard');
            think();
        }

        const contracts = jsonValue(first, 'contracts') || [];
        if (contracts.length === 0) return;
        const contractId = pick(contracts).contractId;

        get(`/api/contracts/${contractId}`, 'contract-detail');
        think();
        get(`/api/contracts/${contractId}/schedules`, 'contract-schedules');
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

// 4) 알림 센터만 확인
function notificationJourney() {
    group('notification', () => {
        get('/api/notifications', 'notification-list');
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

function login(email) {
    return http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ email, password: PASSWORD }),
        { headers: JSON_HEADERS, tags: { name: 'login' } }
    );
}

function get(path, name) {
    // id 가 들어간 URL 은 name 태그로 묶어야 InfluxDB 시리즈가 폭증하지 않는다
    const res = http.get(`${BASE_URL}${path}`, {
        headers: { Authorization: `Bearer ${session.token}` },
        tags: { name },
    });
    check(res, { [`${name} status is 200`]: (r) => r.status === 200 });
    if (res.status === 401) session = null; // 토큰 만료 시 다음 iteration 에서 재로그인
    return res;
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
