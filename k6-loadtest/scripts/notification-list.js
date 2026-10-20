import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';

// 알림 목록(GET /api/notifications) 단독 부하. 수정 전후 쿼리 비용 비교용
// 고정 도착률이라 응답이 느려져도 요청 수가 같다 -> 전후 지연·처리량을 같은 조건에서 비교
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const TEST_ID = __ENV.TEST_ID || 'notification-list';
const HEAVY_USER_COUNT = 10;
const NORMAL_USER_COUNT = Number(__ENV.NORMAL_USERS || 200);
const HEAVY_RATIO = Number(__ENV.HEAVY_RATIO || 0.1);
const RATE = Number(__ENV.RATE || 30);
const MAX_VUS = Number(__ENV.MAX_VUS || 300);

const JSON_HEADERS = { 'Content-Type': 'application/json' };

const STAGES = [
    { duration: '30s', target: 10, phase: 'warmup' },
    { duration: '60s', target: 10, phase: 'warmup' },
    { duration: '30s', target: RATE, phase: 'ramp' },
    { duration: '3m', target: RATE, phase: 'steady' },
    { duration: '15s', target: 0, phase: 'end' },
];

const PHASE_ENDS = [];
STAGES.reduce((sum, stage) => {
    const end = sum + toSeconds(stage.duration);
    PHASE_ENDS.push({ end, phase: stage.phase });
    return end;
}, 0);

export const options = {
    tags: { testid: TEST_ID },
    setupTimeout: '5m',
    scenarios: {
        notification_list: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: MAX_VUS,
            stages: STAGES.map(({ duration, target }) => ({ duration, target })),
        },
    },
    thresholds: {
        http_req_failed: ['rate<0.01'],
        'http_req_duration{name:notification-list,phase:steady}': ['p(95)<300'],
    },
};

// 측정 구간에 로그인(BCrypt)이 섞이지 않도록 토큰을 미리 받아 둔다
export function setup() {
    const users = [];
    for (let n = 1; n <= HEAVY_USER_COUNT + NORMAL_USER_COUNT; n++) users.push(n);

    const tokens = { heavy: [], normal: [] };
    for (let i = 0; i < users.length; i += 20) {
        const chunk = users.slice(i, i + 20);
        const responses = http.batch(chunk.map((n) => [
            'POST',
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ email: userEmail(n), password: PASSWORD }),
            { headers: JSON_HEADERS, tags: { name: 'setup-login', phase: 'setup' } },
        ]));
        responses.forEach((res, idx) => {
            if (res.status !== 200) return;
            const n = chunk[idx];
            tokens[n <= HEAVY_USER_COUNT ? 'heavy' : 'normal'].push(res.json('accessToken'));
        });
    }

    if (tokens.heavy.length === 0 || tokens.normal.length === 0) {
        throw new Error('시드 계정 로그인 실패. seed/02_seed.sql 을 먼저 실행하세요.');
    }
    console.log(`setup 로그인: heavy ${tokens.heavy.length}, normal ${tokens.normal.length}`);
    return tokens;
}

export default function (tokens) {
    const heavy = Math.random() < HEAVY_RATIO;
    const pool = heavy ? tokens.heavy : tokens.normal;
    const token = pool[exec.scenario.iterationInTest % pool.length];

    const res = http.get(`${BASE_URL}/api/notifications`, {
        headers: { Authorization: `Bearer ${token}` },
        tags: { name: 'notification-list', phase: currentPhase(), user_type: heavy ? 'heavy' : 'normal' },
    });
    check(res, { 'notification-list status is 200': (r) => r.status === 200 });
}

function currentPhase() {
    const elapsed = (Date.now() - exec.scenario.startTime) / 1000;
    const found = PHASE_ENDS.find((p) => elapsed < p.end);
    return found ? found.phase : 'end';
}

function userEmail(n) {
    return `lt_user_${String(n).padStart(4, '0')}@sai.com`;
}

function toSeconds(duration) {
    const match = /^(\d+)(s|m|h)$/.exec(duration);
    const unit = { s: 1, m: 60, h: 3600 }[match[2]];
    return Number(match[1]) * unit;
}
