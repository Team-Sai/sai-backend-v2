import http from 'k6/http';
import { check, group } from 'k6';
import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';

// 공동정산 동시 생성 시나리오 (정산 계좌 선택 데드락 재현 / 수정 전후 비교용)
//   POST /api/settlements/shared 만 초당 RATE 건씩 보낸다.
//   새 정산은 정산 계좌가 0건이라 계좌 조회 FOR UPDATE 가 인덱스 끝 gap 을 잠그고,
//   동시에 들어온 다른 생성 요청의 INSERT 와 엇갈리면서 데드락 -> 500 이 난다.
//
// 준비: 01_cleanup.sql -> 02_seed.sql (측정마다 다시)
// 실행 예) docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e PROFILE=load -e RATE=100 /scripts/settlement-deadlock.js
// 데드락 수는 측정 전후 SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks' 차이로 확인한다.

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const PROFILE = __ENV.PROFILE || 'load';
const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const RATE = Number(__ENV.RATE || 100); // 초당 정산 생성 수
const MAX_VUS = Number(__ENV.MAX_VUS || 200);
const USER_COUNT = 1000;
const HEAVY_USER_COUNT = 10; // lt_user_0001~0010 은 제외 (데이터 쏠림 방지)
const PARTICIPANT_COUNT = 3;
const SETUP_BATCH = 20;

const PROFILES = {
    smoke: { startRate: 1, stages: [{ duration: '30s', target: 2 }] },
    load: {
        startRate: 0,
        stages: [
            { duration: '1m', target: RATE },
            { duration: '3m', target: RATE },
            { duration: '30s', target: 0 },
        ],
    },
};

if (!PROFILES[PROFILE]) {
    throw new Error(`알 수 없는 PROFILE: ${PROFILE} (${Object.keys(PROFILES).join(', ')})`);
}

const emails = new SharedArray('emails', () => {
    const list = [];
    for (let n = HEAVY_USER_COUNT + 1; n <= USER_COUNT; n++) {
        list.push(`lt_user_${String(n).padStart(4, '0')}@sai.com`);
    }
    return list;
});

const createFailed = new Counter('settlement_create_failed');
const createFailed5xx = new Counter('settlement_create_5xx');

export const options = {
    setupTimeout: '5m',
    scenarios: {
        settlement_creators: {
            executor: 'ramping-arrival-rate',
            exec: 'createSettlement',
            timeUnit: '1s',
            startRate: PROFILES[PROFILE].startRate,
            stages: PROFILES[PROFILE].stages,
            preAllocatedVUs: Math.min(50, MAX_VUS),
            maxVUs: MAX_VUS,
        },
    },
    thresholds: {
        settlement_create_5xx: ['count==0'],
        'http_req_duration{name:settlement-create}': ['p(95)<1000'],
    },
};

// 전 계정 로그인 + 연결 계좌 조회를 미리 해 둔다 (측정 구간에는 정산 생성만 남김)
export function setup() {
    const users = [];
    for (let from = 0; from < emails.length; from += SETUP_BATCH) {
        const batch = emails.slice(from, from + SETUP_BATCH);
        const logins = http.batch(batch.map((email) => ({
            method: 'POST',
            url: `${BASE_URL}/api/auth/login`,
            body: JSON.stringify({ email, password: PASSWORD }),
            params: { headers: { 'Content-Type': 'application/json' }, tags: { name: 'setup-login' } },
        })));
        const loggedIn = [];
        logins.forEach((res, i) => {
            if (res.status === 200) {
                loggedIn.push({ email: batch[i], accessToken: res.json('accessToken'), userToken: res.json('userToken') });
            }
        });
        const accounts = http.batch(loggedIn.map((user) => ({
            method: 'GET',
            url: `${BASE_URL}/api/linked-accounts`,
            params: { headers: { Authorization: `Bearer ${user.accessToken}` }, tags: { name: 'setup-accounts' } },
        })));
        accounts.forEach((res, i) => {
            const list = res.status === 200 ? res.json() : [];
            if (list.length > 0) users.push(Object.assign(loggedIn[i], { linkedAccountId: list[0].linkedAccountId }));
        });
    }
    console.log(`setup 준비 완료 ${users.length}/${emails.length} 계정`);
    if (users.length < 100) {
        throw new Error('준비된 계정이 너무 적습니다. 01_cleanup.sql -> 02_seed.sql 을 먼저 실행하세요.');
    }
    return { users };
}

// iterationInTest 로 정산 생성자를 돌려써서, 같은 사용자가 동시에 두 건을 만들지 않게 한다
export function createSettlement(data) {
    const users = data.users;
    const iteration = exec.scenario.iterationInTest;
    const owner = users[iteration % users.length];
    const participants = pickOthers(users, owner.email, PARTICIPANT_COUNT);

    group('settlement-create', () => {
        const res = http.post(
            `${BASE_URL}/api/settlements/shared`,
            JSON.stringify({
                settlementCategory: '식비',
                title: `데드락테스트 정산 ${iteration}`,
                dueDate: isoDate(7),
                totalAmount: 40000,
                linkedAccountId: owner.linkedAccountId,
                splitType: 'EQUAL',
                participants: participants.map((p) => ({ userToken: p.userToken })),
            }),
            {
                headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${owner.accessToken}` },
                tags: { name: 'settlement-create' },
            }
        );
        const passed = res.status === 200 || res.status === 201;
        check(res, { 'settlement-create status ok': () => passed });
        if (!passed) {
            createFailed.add(1);
            if (res.status >= 500) createFailed5xx.add(1);
            console.warn(`settlement-create 실패 HTTP ${res.status}: ${String(res.body).slice(0, 200)}`);
        }
    });
}

function pickOthers(users, excludeEmail, count) {
    const picked = [];
    const used = new Set([excludeEmail]);
    while (picked.length < count) {
        const user = users[Math.floor(Math.random() * users.length)];
        if (used.has(user.email)) continue;
        used.add(user.email);
        picked.push(user);
    }
    return picked;
}

function isoDate(daysFromToday) {
    const date = new Date(Date.now() + daysFromToday * 24 * 60 * 60 * 1000);
    return date.toISOString().slice(0, 10);
}
