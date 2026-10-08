import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';

const TEST_USER = {
    email: __ENV.TEST_EMAIL || 'loadtest@sai.com',
    password: __ENV.TEST_PASSWORD || 'loadtest1234!',
    name: '부하테스트',
    birthDate: '1990-01-01',
};

const JSON_HEADERS = { 'Content-Type': 'application/json' };

// 외부 연동 없이 DB만 조회하는 API들. name은 결과를 API별로 나눠 보기 위한 태그
const READ_APIS = [
    { name: 'contract-dashboard', path: '/api/contracts/dashboard' },
    { name: 'contract-previous-sum', path: '/api/contracts/previous-sum' },
    { name: 'settlement-list', path: '/api/settlements' },
    { name: 'settlement-summary', path: '/api/settlements/summary' },
    { name: 'notification-list', path: '/api/notifications' },
    { name: 'integration-dashboard', path: '/api/integration/dashboard' },
];

export const options = {
    // 가상 사용자를 단계적으로 늘려 100명에서 버티는지 확인한다
    stages: [
        { duration: '30s', target: 10 },
        { duration: '30s', target: 30 },
        { duration: '30s', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '1m', target: 100 },
        { duration: '30s', target: 0 },
    ],
    thresholds: {
        http_req_failed: ['rate<0.01'],
        http_req_duration: ['p(95)<500'],
    },
};

export function setup() {
    // 이미 가입된 계정이면 409가 나오므로 201과 409 모두 정상 응답으로 본다
    http.post(`${BASE_URL}/api/auth/signup`, JSON.stringify(TEST_USER), {
        headers: JSON_HEADERS,
        responseCallback: http.expectedStatuses(201, 409),
    });

    const loginResponse = http.post(
        `${BASE_URL}/api/auth/login`,
        JSON.stringify({ email: TEST_USER.email, password: TEST_USER.password }),
        { headers: JSON_HEADERS }
    );

    const loggedIn = check(loginResponse, {
        'login status is 200': (r) => r.status === 200,
    });
    if (!loggedIn) {
        throw new Error(`로그인 실패 (HTTP ${loginResponse.status}): ${loginResponse.body}`);
    }

    return { token: loginResponse.json('accessToken') };
}

export default function (data) {
    const headers = { Authorization: `Bearer ${data.token}` };

    for (const api of READ_APIS) {
        const response = http.get(`${BASE_URL}${api.path}`, {
            headers,
            tags: { name: api.name },
        });

        check(response, {
            [`${api.name} status is 200`]: (r) => r.status === 200,
        });
    }

    sleep(1);
}
