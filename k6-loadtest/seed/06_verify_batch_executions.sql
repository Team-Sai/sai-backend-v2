-- @base_date와 @started_after를 실제 테스트 기준일/실행 시작(KST)로 지정한 같은 SQL 세션에서 실행.
-- 예: SET @base_date='2026-10-19'; SET @started_after='2026-10-19 14:00:00';
-- DB/JVM 시간대를 KST로 맞추거나 started_after 값을 DB 저장 시간대로 변환한다.
-- 트리거 응답 시간과 별도로 서버 측 Job/Step 시간, 거래가 아닌 read/write 단위를 확인한다.
SELECT j.job_name, e.job_execution_id, e.status, e.start_time, e.end_time,
 TIMESTAMPDIFF(MICROSECOND, e.start_time, e.end_time) / 1000 AS duration_ms,
 s.step_name, s.read_count, s.write_count, s.read_skip_count, s.process_skip_count, s.write_skip_count,
 e.exit_code, e.exit_message, s.exit_message AS step_exit_message
FROM BATCH_JOB_EXECUTION e JOIN BATCH_JOB_INSTANCE j ON j.job_instance_id = e.job_instance_id
LEFT JOIN BATCH_STEP_EXECUTION s ON s.job_execution_id = e.job_execution_id
WHERE e.start_time >= @started_after
 AND EXISTS (SELECT 1 FROM BATCH_JOB_EXECUTION_PARAMS p WHERE p.job_execution_id = e.job_execution_id
             AND p.parameter_name = 'baseDate' AND p.parameter_value = CAST(@base_date AS CHAR))
 AND j.job_name IN ('bankTransactionRetryJob', 'repaymentScheduleOverdueJob', 'settlementOverdueJob',
                    'repaymentDueReminderJob', 'settlementDueReminderJob')
ORDER BY e.job_execution_id, s.step_execution_id;
