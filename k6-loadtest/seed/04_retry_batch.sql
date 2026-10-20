-- 격리 DB: 01_cleanup.sql -> 02_seed.sql -> 03_matching_bulk.sql -> 이 파일.
-- 03 기본 규모: 성공 전환 7,500건, 지속 FAILED 2,500건, 미매칭/한도/소진 혼합.
-- 03의 matchable 그룹에는 유일 후보가 있어 FAILED/UNMATCHED에서 APPLIED로 복구된다.
-- ambiguous 그룹은 UNKNOWN 이름으로 후보를 없애고 FAILED -> UNMATCHED 상태 전환을 검증한다.
-- 마지막 그룹은 지속 UNMATCHED/한도 직전/이미 소진된 FAILED·UNMATCHED를 섞는다.
-- BRT expectation 테이블은 실행 전 스냅샷이다. 재실행할 때도 SQL로 상태를 덮어쓰지 않는다.
DROP TABLE IF EXISTS lt_retry_expectation;
CREATE TABLE lt_retry_expectation AS
SELECT bank_transaction_id, linked_account_id, memo AS original_memo,
       CAST(SUBSTRING_INDEX(external_transaction_id, '-', -1) AS UNSIGNED) AS k,
       CASE WHEN memo LIKE 'MLT:matchable:%' THEN 'recover'
            WHEN memo LIKE 'MLT:ambiguous:%' THEN 'failed_to_unmatched'
            WHEN MOD(CAST(SUBSTRING_INDEX(external_transaction_id, '-', -1) AS UNSIGNED), 3) = 0 THEN 'boundary'
            WHEN MOD(CAST(SUBSTRING_INDEX(external_transaction_id, '-', -1) AS UNSIGNED), 3) = 1 THEN 'exhausted'
            ELSE 'persistent' END AS cohort,
       processing_status AS initial_status, retry_count AS initial_retry
FROM bank_transaction WHERE memo LIKE 'MLT:%';
ALTER TABLE lt_retry_expectation ADD PRIMARY KEY (bank_transaction_id);
UPDATE lt_retry_expectation SET cohort = 'persistent_failed' WHERE cohort = 'recover' AND MOD(k, 4) = 0;
UPDATE lt_retry_expectation SET
 initial_status = CASE WHEN cohort = 'recover' THEN IF(MOD(k, 2) = 0, 'FAILED', 'UNMATCHED')
                       WHEN cohort IN ('failed_to_unmatched', 'persistent_failed') THEN 'FAILED'
                       WHEN cohort = 'exhausted' AND MOD(k, 2) = 0 THEN 'FAILED' ELSE 'UNMATCHED' END,
 initial_retry = CASE WHEN cohort = 'boundary' THEN 13
                      WHEN cohort = 'exhausted' THEN IF(MOD(k, 2) = 0, 5, 14) ELSE 0 END;
UPDATE bank_transaction b JOIN lt_retry_expectation e ON e.bank_transaction_id = b.bank_transaction_id
SET b.processing_status = e.initial_status, b.retry_count = e.initial_retry,
    b.counterparty_name = IF(e.cohort IN ('recover', 'persistent_failed'), b.counterparty_name, '__BRT_NO_MATCH__'),
    b.memo = IF(e.cohort = 'persistent_failed', 'BRT:persistent-failed', b.memo);

-- 선택적 계좌 단위 장애: 잘못된 금액은 매칭 모델 생성에서 예외를 발생시킨다.
-- 기본 0. 1~10 설정 시 마지막 seed 사용자 계좌에 1건씩 생성 (50 skip 한도 미만).
-- 이는 트랜잭션 롤백/skip/error 경로 검사이며 FAILED 5회 소진을 흉내내지 않는다.
-- 지속 FAILED 5회 경로는 support의 opt-in 5xx 장애 주입 설정을 사용한다 (문서 참고).
SET @fault_accounts = 0;
INSERT INTO bank_transaction
(linked_account_id, external_transaction_id, amount, transaction_type, processing_status,
 transaction_at, counterparty_name, memo, synced_at, retry_count)
SELECT a.linked_account_id, CONCAT('BRT-FAULT-', n.seq), 0, 'DEPOSIT', 'FAILED',
       NOW() - INTERVAL 1 DAY, 'BRT_FAULT', 'BRT:account-error', NOW(), 0
FROM seq_1_to_10 n JOIN users u ON u.email = CONCAT('lt_user_', LPAD(1001 - n.seq, 4, '0'), '@sai.com')
JOIN (SELECT user_id, MIN(linked_account_id) AS linked_account_id FROM linked_bank_account
      WHERE connection_status = 'AVAILABLE' GROUP BY user_id) a ON a.user_id = u.user_id
WHERE n.seq <= @fault_accounts;
SELECT cohort, initial_status, initial_retry, COUNT(*) AS expected_count
FROM lt_retry_expectation GROUP BY cohort, initial_status, initial_retry;
SELECT bank_transaction_id AS expected_error_log_transaction FROM bank_transaction WHERE memo = 'BRT:account-error';


