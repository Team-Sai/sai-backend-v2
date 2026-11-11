import http from 'k6/http';

export const options = {
    vus: 1,
    iterations: 1,
};

export default function () {
    const base = 'http://host.docker.internal:18080';

    const login = http.post(
        `${base}/api/auth/login`,
        JSON.stringify({
            email: 'lt_user_0001@sai.com',
            password: __ENV.TEST_PASSWORD || 'loadtest1234!',
        }),
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: '30s',
        }
    );

    console.log(`login HTTP ${login.status}`);
    if (login.status !== 200) return;

    const token = login.json('accessToken');
    if (!token) {
        console.log('accessToken 없음');
        return;
    }

    const params = {
        headers: { Authorization: `Bearer ${token}` },
        timeout: '30s',
    };

    const accounts = http.get(`${base}/api/linked-accounts`, params);
    console.log(`accounts HTTP ${accounts.status}`);
    if (accounts.status !== 200) return;

    const account = accounts.json()
        .filter((a) => a.connectionStatus === 'AVAILABLE')
        .sort((a, b) => a.linkedAccountId - b.linkedAccountId)[0];

    if (!account) {
        console.log('AVAILABLE 계좌 없음');
        return;
    }

    console.log(`linkedAccountId=${account.linkedAccountId}`);

    const sync = http.post(
        `${base}/api/linked-accounts/${account.linkedAccountId}/sync`,
        null,
        params
    );

    console.log(`sync HTTP ${sync.status}`);
    if (sync.status !== 200) {
        console.log(`sync error=${sync.error || ''}`);
        console.log(`sync body=${sync.body}`);
    }
}
