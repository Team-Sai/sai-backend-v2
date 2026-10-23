import { runJob } from './batch-common.js';

// 준비/검증: ../BATCH-SCENARIOS.md. 04_retry_batch.sql 실행 후 이 파일 실행.
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e BASE_DATE=2026-10-19 /scripts/retry-batch.js
// ROUNDS 기본 15: UNMATCHED 한도 14회 + 소진 뒤 한 번 더 실행.
const ROUNDS = Number(__ENV.ROUNDS || 15);
if (!Number.isSafeInteger(ROUNDS) || ROUNDS < 1) throw new Error('ROUNDS는 양의 정수');
export const options = {
    scenarios: {
        retry: {
            executor: 'per-vu-iterations',
            vus: 100,
            iterations: 1,
            maxDuration: '12h',
        },
    },
    summaryTrendStats: ['avg', 'min', 'max', 'p(95)', 'p(99)'],
    thresholds: { checks: ['rate==1'] },
};
export default function () {
    for (let round = 1; round <= ROUNDS; round++) runJob('bankTransactionRetryJob', round);
}
