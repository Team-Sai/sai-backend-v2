-- 실행한 ROUNDS와 동일하게 설정. 위반 결과는 모두 0행이어야 한다.
SET @rounds = 15;
SELECT e.bank_transaction_id, e.cohort, b.processing_status, b.retry_count,
       IF(e.cohort = 'recover', 'APPLIED', IF(e.cohort IN ('exhausted', 'persistent_failed'), e.initial_status, 'UNMATCHED')) AS expected_status,
       CASE WHEN e.cohort = 'recover' THEN e.initial_retry + 1
            WHEN e.cohort = 'persistent_failed' THEN LEAST(5, e.initial_retry + @rounds)
            WHEN e.cohort = 'exhausted' THEN e.initial_retry
            ELSE LEAST(14, e.initial_retry + @rounds) END AS expected_retry
FROM lt_retry_expectation e JOIN bank_transaction b ON b.bank_transaction_id = e.bank_transaction_id
WHERE b.processing_status <> IF(e.cohort = 'recover', 'APPLIED', IF(e.cohort IN ('exhausted', 'persistent_failed'), e.initial_status, 'UNMATCHED'))
   OR b.retry_count <> CASE WHEN e.cohort = 'recover' THEN e.initial_retry + 1
                           WHEN e.cohort = 'persistent_failed' THEN LEAST(5, e.initial_retry + @rounds)
            WHEN e.cohort = 'exhausted' THEN e.initial_retry ELSE LEAST(14, e.initial_retry + @rounds) END;
-- 누락/삭제 탐지
SELECT e.bank_transaction_id AS missing FROM lt_retry_expectation e
LEFT JOIN bank_transaction b ON b.bank_transaction_id = e.bank_transaction_id WHERE b.bank_transaction_id IS NULL;
-- 상태별 성공 전환/소진 건수 (FAILED->UNMATCHED 전환 후 한도는 14).
SELECT e.cohort, b.processing_status, b.retry_count, COUNT(*) AS transactions
FROM lt_retry_expectation e JOIN bank_transaction b ON b.bank_transaction_id = e.bank_transaction_id
GROUP BY e.cohort, b.processing_status, b.retry_count;
-- 소진 webhook에 정확히 1회 등장해야 할 거래 ID (이미 소진된 거래는 알림 재발송 대상 아님).
SELECT e.bank_transaction_id AS expected_exhaustion_notification
FROM lt_retry_expectation e WHERE e.cohort <> 'recover' AND e.cohort <> 'exhausted'
 AND e.initial_retry + @rounds >= IF(e.cohort = 'persistent_failed', 5, 14);
-- 이미 소진된 거래 ID: webhook에 등장하면 오류.
SELECT bank_transaction_id AS forbidden_exhaustion_notification FROM lt_retry_expectation WHERE cohort = 'exhausted';
-- 선택적 계좌 오류는 롤백되므로 retry_count=0 유지 여부 검사.
SELECT bank_transaction_id, processing_status, retry_count FROM bank_transaction
WHERE memo = 'BRT:account-error' AND (retry_count <> 0 OR processing_status <> 'FAILED');

-- 성공 거래의 payment_record는 정확히 하나, 지속 실패/미매칭 거래에는 없어야 한다.
SELECT e.bank_transaction_id, e.cohort, COUNT(p.payment_record_id) AS records
FROM lt_retry_expectation e LEFT JOIN payment_record p ON p.bank_transaction_id = e.bank_transaction_id
GROUP BY e.bank_transaction_id, e.cohort HAVING COUNT(p.payment_record_id) <> IF(e.cohort = 'recover', 1, 0);


-- 오류 로그가 거래별 LEAST(5, @rounds)회 있어야 할 ID.
SELECT bank_transaction_id AS expected_persistent_error FROM lt_retry_expectation WHERE cohort = 'persistent_failed';
