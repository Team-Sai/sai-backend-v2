import { runJob } from './batch-common.js';

// 계좌 수 증가 시나리오: 자정 파이프라인 첫 단계 transactionSyncJob 을 1회 실행해 완료 시간을 잰다.
// 준비: 01_cleanup.sql -> 02_seed.sql -> 07_account_sync.sql (@accounts_per_user 로 계좌 수 지정). mock-bank(8081) 실행 필요.
// 다시 잴 때는 07_account_sync.sql 만 다시 실행하면 된다 (커서와 동기화 거래를 되돌린다).
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e BASE_DATE=2026-10-20 -e TEST_ID=sync-1k /scripts/account-sync-batch.js
// readCount = 처리한 계좌 수(AVAILABLE 전체). 계좌당 시간 = batch_completion_ms / readCount.
// Step 시간과 skip 은 BATCH_STEP_EXECUTION 에서, 저장 건수는 07 의 확인 쿼리(LTS-N-%)로 대조한다.
export const options = {
    tags: { testid: __ENV.TEST_ID || 'account-sync' },
    scenarios: { sync: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '10m' } },
    summaryTrendStats: ['avg', 'min', 'max'],
    thresholds: { checks: ['rate==1'] },
};

export default function () {
    const body = runJob('transactionSyncJob', 1);
    console.log(`처리 계좌 ${body.readCount}개`);
}
