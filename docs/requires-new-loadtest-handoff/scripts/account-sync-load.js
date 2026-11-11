import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

// 기본: 고정 시드를 사용하는 반복 동기화 테스트.
// 신규 거래가 소진되어 0건이 저장돼도 테스트를 계속한다.
// 시드 소진 후에는 신규 INSERT 부하가 아닌 빈 동기화 부하를 측정한다.
//
// 실행 예:
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run /scripts/account-sync-load.js
//
// 매 요청 신규 거래 수를 고정 검증하려면:
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run \
//   -e STRICT_NEW_TRANSACTIONS=true -e NEW_TRANSACTIONS=100 \
//   /scripts/account-sync-load.js
//
// STRICT 모드는 mock이 매 요청마다 커서 이후의 신규 transactionId와
// 고유 transactionKey를 NEW_TRANSACTIONS개씩 공급해야 한다.
// NEW_TRANSACTIONS는 기대값이며, 은행 거래를 생성하지 않는다.
//
// 준비:
// - lt_user_0001@sai.com부터 최대 VU 수만큼 사용자 준비.
// - 각 사용자에게 AVAILABLE 연결 계좌와 대응하는 mock-bank 계좌 준비.
// - 테스트 중 외부 동기화, 배치, 거래 삭제 중지.
// - 실행 중 토큰이 만료되지 않도록 테스트 환경 설정.
//
// 측정:
// - sync_latency: /sync HTTP 호출 시간. 은행 조회·저장·자동매칭 포함.
// - sync_accounts: HTTP 200으로 완료된 동기화 요청 수.
// - sync_transactions_stored: 전후 totalCount 증가량의 합.
// - sync_with_new_transactions: 저장량 검증이 가능한 요청 중 신규 저장 비율.
// - sync_empty: 저장량 검증이 가능한 요청 중 신규 저장 0건 비율.
// - sync_storage_verified: 전체 동기화 요청 중 저장량 검증 성공 비율.
// - sync_fixed_transaction_count: STRICT 모드의 고정 저장량 검증 비율.
//
// 거래 수 검증용 GET 요청도 서버 부하에 포함되지만 sync_latency에서는 제외한다.
// 저장량은 API 전후 차이이므로 외부 쓰기가 없어야 정확하다.
// HTTP 실패여도 저장이 먼저 커밋됐을 수 있어 사후 거래 수를 조회한다.

const BASE_URL = (
    __ENV.BASE_URL || 'http://host.docker.internal:8080'
).replace(/\/+$/, '');

const PASSWORD = __ENV.TEST_PASSWORD || 'loadtest1234!';
const LEVELS = (__ENV.VU_LEVELS || '10,25,50,100')
    .split(',')
    .map((value) => Number(value.trim()));

const FIRST_USER = positiveInt(__ENV.FIRST_USER || '1', 'FIRST_USER');
const NEW_TRANSACTIONS = positiveInt(
    __ENV.NEW_TRANSACTIONS || '20',
    'NEW_TRANSACTIONS'
);
const STRICT = booleanValue(
    __ENV.STRICT_NEW_TRANSACTIONS || 'false',
    'STRICT_NEW_TRANSACTIONS'
);
const P95 = positiveInt(__ENV.P95_MS || '2000', 'P95_MS');
const P99 = positiveInt(__ENV.P99_MS || '5000', 'P99_MS');
const SLEEP_SECONDS = nonNegativeNumber(
    __ENV.SLEEP_SECONDS || '0',
    'SLEEP_SECONDS'
);
const REQUEST_TIMEOUT = __ENV.REQUEST_TIMEOUT || '30s';

if (
    LEVELS.some(
        (value, index) =>
            !Number.isSafeInteger(value) ||
            value < 1 ||
            (index > 0 && value <= LEVELS[index - 1])
    )
) {
    throw new Error('VU_LEVELS는 증가하는 양의 정수 목록이어야 합니다.');
}

const stages = [];
LEVELS.forEach((target) => {
    stages.push({
        duration: __ENV.RAMP_DURATION || '30s',
        target,
    });
    stages.push({
        duration: __ENV.HOLD_DURATION || '2m',
        target,
    });
});
stages.push({
    duration: __ENV.RAMP_DOWN_DURATION || '30s',
    target: 0,
});

const latency = new Trend('sync_latency', true);
const accounts = new Counter('sync_accounts');
const stored = new Counter('sync_transactions_stored');
const failures = new Rate('sync_failed');
const storageVerified = new Rate('sync_storage_verified');
const withNewTransactions = new Rate('sync_with_new_transactions');
const empty = new Rate('sync_empty');

// STRICT 모드에서만 기록하고 threshold를 적용한다.
const expected = new Counter('sync_transactions_expected');
const fixedTransactionCount = new Rate('sync_fixed_transaction_count');

const thresholds = {
    sync_latency: [`p(95)<${P95}`, `p(99)<${P99}`],
    sync_failed: ['rate<0.01'],
    sync_storage_verified: ['rate==1'],
    checks: ['rate==1'],
};

if (STRICT) {
    thresholds.sync_fixed_transaction_count = ['rate==1'];
}

export const options = {
    setupTimeout: '10m',
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(95)', 'p(99)'],
    systemTags: [
        'status',
        'method',
        'name',
        'scenario',
        'expected_response',
    ],
    scenarios: {
        account_sync: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages,
            gracefulRampDown: '1m',
            gracefulStop: '1m',
        },
    },
    thresholds,
};

export function setup() {
    const users = [];
    const seen = new Set();
    const maxVUs = LEVELS[LEVELS.length - 1];

    for (let index = 0; index < maxVUs; index++) {
        const email =
            `lt_user_${String(FIRST_USER + index).padStart(4, '0')}@sai.com`;

        const loginRes = http.post(
            `${BASE_URL}/api/auth/login`,
            JSON.stringify({ email, password: PASSWORD }),
            {
                headers: { 'Content-Type': 'application/json' },
                tags: { name: 'setup-login' },
                timeout: REQUEST_TIMEOUT,
            }
        );

        if (loginRes.status !== 200) {
            throw new Error(
                `${email}: 로그인 실패 HTTP ${loginRes.status}`
            );
        }

        const token = jsonField(loginRes, 'accessToken');
        if (typeof token !== 'string' || !token) {
            throw new Error(`${email}: accessToken 없음`);
        }

        const listRes = http.get(
            `${BASE_URL}/api/linked-accounts`,
            params(token, 'setup-accounts')
        );

        if (listRes.status !== 200) {
            throw new Error(
                `${email}: 계좌 조회 실패 HTTP ${listRes.status}`
            );
        }

        const list = jsonField(listRes);
        if (!Array.isArray(list)) {
            throw new Error(`${email}: 계좌 목록 형식 오류`);
        }

        const ids = list
            .filter((account) => account.connectionStatus === 'AVAILABLE')
            .map((account) => account.linkedAccountId)
            .sort((left, right) => left - right);

        if (!ids.length) {
            throw new Error(`${email}: AVAILABLE 계좌 없음`);
        }

        ids.forEach((id) => {
            if (!Number.isSafeInteger(id) || id < 1 || seen.has(id)) {
                throw new Error(`${email}: 계좌 ID 오류/중복 (${id})`);
            }
            seen.add(id);
        });

        users.push({ token, ids });
    }

    console.log(
        `준비 완료: 사용자 ${users.length}, 계좌 ${seen.size}, ` +
        `모드 ${STRICT ? 'STRICT' : 'REPEAT'}` +
        (STRICT ? `, 요청당 기대 신규 거래 ${NEW_TRANSACTIONS}` : '')
    );

    if (!STRICT) {
        console.log(
            'REPEAT 모드: 신규 저장 0건 허용. ' +
            '시드 소진 후에는 빈 동기화 요청의 성능을 측정합니다.'
        );
    }

    return { users };
}

export default function (data) {
    const user = data.users[exec.vu.idInTest - 1];

    if (!user) {
        exec.test.abort('VU에 대응하는 사용자 없음');
        return;
    }

    const id = user.ids[
    exec.vu.iterationInScenario % user.ids.length
        ];
    const path = `${BASE_URL}/api/linked-accounts/${id}`;
    const before = savedCount(path, user.token);

    // 사전 조회 실패 시에도 sync 요청은 실행한다.
    // 저장량을 확인할 수 없다는 사실은 별도 지표와 threshold로 기록한다.
    if (STRICT) {
        expected.add(NEW_TRANSACTIONS);
    }

    const res = http.post(
        `${path}/sync`,
        null,
        params(user.token, 'account-sync')
    );

    latency.add(res.timings.duration);

    const ok = check(res, {
        'sync HTTP 200': (response) => response.status === 200,
    });

    failures.add(!ok);
    accounts.add(ok ? 1 : 0);

    const after = savedCount(path, user.token);
    const delta =
        before === null || after === null ? null : after - before;

    const measurable = delta !== null && delta >= 0;
    storageVerified.add(measurable);

    check(delta, {
        '저장량 검증 가능': () => measurable,
    });

    if (measurable) {
        stored.add(delta);
        withNewTransactions.add(delta > 0);
        empty.add(delta === 0);
    }

    if (STRICT) {
        const fixed = measurable && delta === NEW_TRANSACTIONS;
        fixedTransactionCount.add(fixed);

        check(delta, {
            '신규 저장 거래 수 고정': () => fixed,
        });

        if (!fixed) {
            exec.test.abort(
                `계좌 ${id}: HTTP ${res.status}, ` +
                `이전 ${before}, 이후 ${after}, 신규 거래 ${delta}, ` +
                `기대 ${NEW_TRANSACTIONS}. ` +
                '은행 fixture/커서/중복 transactionKey/외부 쓰기를 확인하세요.'
            );
            return;
        }
    }

    if (SLEEP_SECONDS > 0) {
        sleep(SLEEP_SECONDS);
    }
}

function params(token, name) {
    return {
        headers: { Authorization: `Bearer ${token}` },
        tags: { name },
        timeout: REQUEST_TIMEOUT,
    };
}

function savedCount(path, token) {
    const res = http.get(
        `${path}/transactions?page=0&size=1`,
        params(token, 'verify-stored-count')
    );

    const count = jsonField(res, 'totalCount');
    const ok = check(res, {
        '저장 건수 조회 성공': (response) =>
            response.status === 200 &&
            Number.isSafeInteger(count) &&
            count >= 0,
    });

    return ok ? count : null;
}

function jsonField(response, field) {
    try {
        return field === undefined
            ? response.json()
            : response.json(field);
    } catch (_) {
        return null;
    }
}

function positiveInt(value, name) {
    const number = Number(value);
    if (!Number.isSafeInteger(number) || number < 1) {
        throw new Error(`${name}은 양의 정수여야 합니다.`);
    }
    return number;
}

function nonNegativeNumber(value, name) {
    const number = Number(value);
    if (!Number.isFinite(number) || number < 0) {
        throw new Error(`${name}은 0 이상의 숫자여야 합니다.`);
    }
    return number;
}

function booleanValue(value, name) {
    if (value === 'true') return true;
    if (value === 'false') return false;
    throw new Error(`${name}은 true 또는 false여야 합니다.`);
}