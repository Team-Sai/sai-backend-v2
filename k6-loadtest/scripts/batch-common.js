import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// dev 프로필의 실제 Job 트리거를 사용한다. 응답 COMPLETED일 때만 완료로 인정한다.
// 비동기 JobOperator 응답(STARTING/STARTED)은 완료 측정으로 인정하지 않고 실패한다.
// 정확한 서버 Job 시간/skip/오류는 seed 검증 SQL의 BATCH_* 메타데이터로 확인한다.
const BASE_URL = __ENV.BASE_URL || 'http://host.docker.internal:8080';
const BASE_DATE = __ENV.BASE_DATE;
if (!/^\d{4}-\d{2}-\d{2}$/.test(BASE_DATE || '') ||
    new Date(`${BASE_DATE}T00:00:00Z`).toISOString().slice(0, 10) !== BASE_DATE) {
    throw new Error('BASE_DATE=YYYY-MM-DD를 SQL 준비 기준일과 동일하게 지정하세요.');
}
const duration = new Trend('batch_completion_ms', true);
const reads = new Counter('batch_read_items');
const writes = new Counter('batch_write_items');

export function runJob(job, run) {
    const tags = { job, run: String(run) };
    const res = http.post(`${BASE_URL}/dev/batch/${job}?baseDate=${BASE_DATE}`, null, {
        timeout: __ENV.JOB_TIMEOUT || '30m',
        tags: { name: 'batch-trigger', job: job, run: String(run) },
        headers: __ENV.ACCESS_TOKEN ? { Authorization: `Bearer ${__ENV.ACCESS_TOKEN}` } : {},
    });
    let body = {};
    try { body = res.json() || {}; } catch (_) { /* check로 오류 처리 */ }
    const completed = res.status === 200 && body.jobName === job && body.baseDate === BASE_DATE &&
        body.status === 'COMPLETED' && Number.isSafeInteger(body.readCount) && body.readCount >= 0 &&
        Number.isSafeInteger(body.writeCount) && body.writeCount >= 0;
    check(res, { 'Job 완료 및 처리 건수 응답': () => completed }, tags);
    console.log(JSON.stringify({ job, run, status: body.status, httpStatus: res.status,
        triggerElapsedMs: res.timings.duration, readCount: body.readCount, writeCount: body.writeCount }));
    if (!completed) throw new Error(`${job}: 완료 확인 실패. BATCH_JOB_EXECUTION/오류 로그 확인 후 다음 실행을 중지하세요.`);
    duration.add(res.timings.duration, tags);
    reads.add(body.readCount, tags);
    writes.add(body.writeCount, tags);
    // dev 트리거의 millisecond runId 충돌을 피한다. 같은 baseDate, 새 JobInstance로 재실행한다.
    sleep(0.01);
    return body;
}
