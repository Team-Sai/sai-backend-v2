-- 알림 배치 전용 실행: 01_cleanup.sql -> 02_seed.sql -> 03_matching_bulk.sql -> 이 파일.
-- retry 시나리오와 DB 초기화를 공유하지 말 것. 실행 중 스케줄러와 다른 Job은 중지한다.
-- 기준일 기본 KST 오늘. 날짜 고정은 같은 SQL 세션에서 SET @base_date='2026-10-19'; 후 실행.
SET @base_date = COALESCE(@base_date, DATE(UTC_TIMESTAMP() + INTERVAL 9 HOUR));
-- 일자 버킷: 0=어제/연체 후보, 1=D-3, 2=D-1, 3=당일, 4=D-2(제외), 5=PAID(제외).
-- 새 MLT 대상만 수정하며 기존 seed 데이터의 배치 처리량은 별도로 구분한다.
DROP TABLE IF EXISTS lt_schedule_expectation;
CREATE TABLE lt_schedule_expectation AS
SELECT r.schedule_id, r.contract_id, c.debtor_id AS user_id,
       MOD(CAST(SUBSTRING_INDEX(c.contract_alias, '-', -1) AS UNSIGNED), 6) AS bucket,
       r.due_date, r.status AS expected_status,
       CAST(NULL AS CHAR(50)) AS expected_notification
FROM repayment_schedule r JOIN loan_contract c ON c.contract_id = r.contract_id
WHERE c.contract_alias LIKE 'MLT-%';
ALTER TABLE lt_schedule_expectation ADD PRIMARY KEY (schedule_id);
UPDATE lt_schedule_expectation SET
 due_date = DATE_ADD(@base_date, INTERVAL CASE bucket WHEN 0 THEN -1 WHEN 1 THEN 3 WHEN 2 THEN 1 WHEN 3 THEN 0 WHEN 4 THEN 2 ELSE 0 END DAY),
 expected_status = CASE bucket WHEN 0 THEN 'OVERDUE' WHEN 5 THEN 'PAID' ELSE 'PENDING' END,
 expected_notification = CASE bucket WHEN 1 THEN 'REPAYMENT_DUE_REMINDER_D3'
                                    WHEN 2 THEN 'REPAYMENT_DUE_REMINDER_D1'
                                    WHEN 3 THEN 'REPAYMENT_DUE_REMINDER_DDAY' ELSE NULL END;
UPDATE repayment_schedule r JOIN lt_schedule_expectation e ON e.schedule_id = r.schedule_id
SET r.due_date = e.due_date, r.status = IF(e.bucket = 5, 'PAID', 'PENDING'),
    r.paid_at = IF(e.bucket = 5, NOW(), NULL);

DROP TABLE IF EXISTS lt_obligation_expectation;
CREATE TABLE lt_obligation_expectation AS
SELECT po.payment_obligation_id, s.settlement_id, p.user_id,
       MOD(CAST(SUBSTRING_INDEX(s.title, '-', -1) AS UNSIGNED), 6) AS bucket,
       s.due_date, CAST(NULL AS DATETIME) AS expected_overdue_since,
       CAST(NULL AS CHAR(50)) AS expected_notification
FROM payment_obligation po JOIN settlement_participant p ON p.participant_id = po.participant_id
JOIN settlement s ON s.settlement_id = p.settlement_id WHERE s.title LIKE 'MLT-%';
ALTER TABLE lt_obligation_expectation ADD PRIMARY KEY (payment_obligation_id);
UPDATE lt_obligation_expectation SET
 due_date = DATE_ADD(@base_date, INTERVAL CASE bucket WHEN 0 THEN -1 WHEN 1 THEN 3 WHEN 2 THEN 1 WHEN 3 THEN 0 WHEN 4 THEN 2 ELSE 0 END DAY),
 expected_overdue_since = IF(bucket = 0, CAST(@base_date AS DATETIME), NULL),
 expected_notification = CASE bucket WHEN 1 THEN 'SETTLEMENT_DUE_REMINDER_D3'
                                    WHEN 2 THEN 'SETTLEMENT_DUE_REMINDER_D1'
                                    WHEN 3 THEN 'SETTLEMENT_DUE_REMINDER_DDAY' ELSE NULL END;
UPDATE settlement s JOIN lt_obligation_expectation e ON e.settlement_id = s.settlement_id SET s.due_date = e.due_date;
UPDATE payment_obligation po JOIN lt_obligation_expectation e ON e.payment_obligation_id = po.payment_obligation_id
SET po.payment_status = IF(e.bucket = 5, 'PAID', 'UNPAID'), po.overdue_since = NULL;

-- 실행 전 알림이 이미 있다면 초기화가 누락된 것이다. 0이어야 함.
SELECT COUNT(*) AS unexpected_existing_notifications FROM notification n
WHERE (n.notification_type LIKE 'REPAYMENT_DUE_REMINDER_%' AND n.reference_id IN (SELECT schedule_id FROM lt_schedule_expectation))
   OR (n.notification_type LIKE 'SETTLEMENT_DUE_REMINDER_%' AND n.reference_id IN (SELECT payment_obligation_id FROM lt_obligation_expectation));
SELECT @base_date AS required_BASE_DATE;
SELECT 'repayment' AS kind, bucket, COUNT(*) AS targets FROM lt_schedule_expectation GROUP BY bucket
UNION ALL SELECT 'settlement', bucket, COUNT(*) FROM lt_obligation_expectation GROUP BY bucket;
