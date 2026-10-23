import { runJob } from './batch-common.js';

// 준비: 05_notification_batch.sql. BASE_DATE를 SQL과 동일하게 지정한다.
// docker compose -f k6-loadtest/docker-compose.loadtest.yml run --rm k6 run -e BASE_DATE=2026-10-19 /scripts/notification-batch.js
// 각 Job을 같은 기준일로 2회 실행한다. 새 runId이므로 애플리케이션의 데이터 중복 방지를 검증한다.
// 두 번째 writeCount가 0일 필요는 없다. 리마인더는 중복을 건너뛰어도 processed를 증가시킨다.
// 실제 삽입 수/수신자/대상/날짜 분류/중복은 05_verify_notification_batch.sql로 검사한다.
const JOBS = ['repaymentScheduleOverdueJob', 'settlementOverdueJob',
    'repaymentDueReminderJob', 'settlementDueReminderJob'];
export const options = {
    scenarios: { notifications: { executor: 'shared-iterations', vus: 1, iterations: 1, maxDuration: '12h' } },
    summaryTrendStats: ['avg', 'min', 'max', 'p(95)', 'p(99)'],
    thresholds: { checks: ['rate==1'] },
};
export default function () {
    for (const job of JOBS) for (let run = 1; run <= 2; run++) runJob(job, run);
}
