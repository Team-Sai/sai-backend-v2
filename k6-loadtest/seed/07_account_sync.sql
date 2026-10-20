-- 계좌 수 증가 시나리오(transactionSyncJob) 준비: 01_cleanup.sql -> 02_seed.sql -> 이 파일.
-- sai_backend 에 접속해서 실행한다. 같은 MariaDB 의 sai_mock_bank 도 함께 수정한다.
-- 계좌 수는 사용자(1,000명)당 계좌 수로 정한다. 1 -> 1,000개 / 5 -> 5,000개 / 10 -> 10,000개
--   mariadb ... sai_backend -e "SET @accounts_per_user=5; source k6-loadtest/seed/07_account_sync.sql"
-- 여러 번 실행해도 된다. 실행할 때마다 이전 계좌 동기화 데이터(양쪽 DB)를 지우고 다시 만든다.
--
-- 가정
--   계좌마다 이미 동기화된 과거 거래 @history_per_account 건이 있고, 커서는 그 마지막 거래에 있다.
--   자정 배치는 커서 이후 새 거래 @new_per_account 건(하루치 입출금)만 받아온다.
--   새 입금은 매칭 후보 조회까지 실행되지만 입금자명이 사용자와 달라 UNMATCHED 로 끝난다(상환·정산 상태는 바뀌지 않음).
--   계좌 1개(account_id = 900000000 + n)는 02_seed 의 계좌로 정산·차용증과 연결돼 있고, 추가 계좌는 연결된 대상이 없다.
--
-- mock-bank 는 X-User-Key 의 HMAC-SHA256(key-hash-secret) 을 bank_user.user_key_hash 와 비교한다.
-- 아래 @ipad/@opad 는 sai-mock-bank application-dev.yml 의 key-hash-secret(random-secret-hash-value) 으로 만든 값이다.
-- secret 이 바뀌면 다시 계산해야 한다.

SET @accounts_per_user   = COALESCE(@accounts_per_user, 1);
SET @history_per_account = COALESCE(@history_per_account, 20);
SET @new_per_account     = COALESCE(@new_per_account, 3);
SET @ipad := UNHEX('44575852595b1b4553554453421b5e57455e1b40575a435336363636363636363636363636363636363636363636363636363636363636363636363636363636');
SET @opad := UNHEX('2e3d32383331712f393f2e392871343d2f34712a3d3029395c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c5c');

-- ---------------------------------------------------------------------------
-- 0. 이전 실행 정리
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS tmp_sync_account;
CREATE TABLE tmp_sync_account (
    n                 INT    NOT NULL,
    k                 INT    NOT NULL,
    user_id           BIGINT NOT NULL,
    name              VARCHAR(50) NOT NULL,
    account_id        BIGINT NOT NULL,
    linked_account_id BIGINT NULL,
    PRIMARY KEY (account_id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

DELETE FROM bank_transaction_match_candidate
WHERE bank_transaction_id IN (
    SELECT bank_transaction_id FROM bank_transaction WHERE external_transaction_id LIKE 'LTS-%'
);
DELETE FROM payment_record
WHERE bank_transaction_id IN (
    SELECT bank_transaction_id FROM bank_transaction WHERE external_transaction_id LIKE 'LTS-%'
);
DELETE FROM bank_transaction WHERE external_transaction_id LIKE 'LTS-%';
DELETE FROM linked_bank_account WHERE account_id BETWEEN 900010001 AND 900099999;

DELETE FROM sai_mock_bank.bank_transaction WHERE transaction_key LIKE 'LTS-%';
DELETE FROM sai_mock_bank.bank_transfer WHERE request_key = 'LTS-DUMMY';
DELETE FROM sai_mock_bank.bank_account WHERE account_id BETWEEN 900000001 AND 900099999;
DELETE FROM sai_mock_bank.bank_user WHERE email LIKE 'lt\_user\_%@sai.com';

-- ---------------------------------------------------------------------------
-- 1. sai_backend: 사용자 키 + 추가 연결 계좌
--    account_id = 900000000 + (k - 1) * 10000 + n  (k = 사용자의 몇 번째 계좌인지, k = 1 은 02_seed 계좌)
-- ---------------------------------------------------------------------------
UPDATE users
SET user_key = CONCAT('lt-key-', SUBSTRING(email, 9, 4))
WHERE email LIKE 'lt\_user\_%@sai.com';

INSERT INTO tmp_sync_account (n, k, user_id, name, account_id)
SELECT CAST(SUBSTRING(u.email, 9, 4) AS UNSIGNED), s.seq, u.user_id, u.name,
       900000000 + (s.seq - 1) * 10000 + CAST(SUBSTRING(u.email, 9, 4) AS UNSIGNED)
FROM users u
JOIN seq_1_to_10 s ON s.seq <= @accounts_per_user
WHERE u.email LIKE 'lt\_user\_%@sai.com';

INSERT INTO linked_bank_account
    (user_id, bank_code, account_number, account_alias, account_holder_name,
     connection_status, account_id, balance, last_synced_transaction_id)
SELECT user_id, '088', CONCAT('LTS-', k, '-', LPAD(n, 4, '0')),
       CONCAT('부하테스트 통장 ', k), name, 'AVAILABLE', account_id, 1000000, 0
FROM tmp_sync_account
WHERE k > 1;

UPDATE tmp_sync_account t
JOIN linked_bank_account a ON a.user_id = t.user_id AND a.account_id = t.account_id
SET t.linked_account_id = a.linked_account_id;

-- ---------------------------------------------------------------------------
-- 2. sai_mock_bank: 사용자(키 해시) / 계좌
-- ---------------------------------------------------------------------------
INSERT INTO sai_mock_bank.bank_user
    (name, user_token, user_key_hash, birth_date, email, key_status, issued_at)
SELECT u.name,
       CONCAT('lt-mb-', SUBSTRING(u.email, 9, 4)),
       REPLACE(REPLACE(TRIM(TRAILING '=' FROM TO_BASE64(UNHEX(SHA2(
           CONCAT(@opad, UNHEX(SHA2(CONCAT(@ipad, u.user_key), 256))), 256)))), '+', '-'), '/', '_'),
       u.birth_date, u.email, 'ACTIVE', NOW()
FROM users u
WHERE u.email LIKE 'lt\_user\_%@sai.com';

INSERT INTO sai_mock_bank.bank_account
    (account_id, bank_code, account_number, account_name, account_holder_name, balance, status, bank_user_id)
SELECT t.account_id, '088', CONCAT('LTS-', t.k, '-', LPAD(t.n, 4, '0')),
       CONCAT('부하테스트 통장 ', t.k), t.name, 1000000, 'ACTIVE', bu.bank_user_id
FROM tmp_sync_account t
JOIN users u ON u.user_id = t.user_id
JOIN sai_mock_bank.bank_user bu ON bu.email = u.email;

-- bank_transaction.transfer_id 가 NOT NULL FK 라서 모든 시드 거래가 참조할 이체 1건
INSERT INTO sai_mock_bank.bank_transfer
    (request_key, from_account_id, to_account_id, amount, status, completed_at)
VALUES ('LTS-DUMMY', 900000001, 900000002, 1, 'COMPLETED', NOW());
SET @transfer_id := (SELECT transfer_id FROM sai_mock_bank.bank_transfer WHERE request_key = 'LTS-DUMMY');

-- ---------------------------------------------------------------------------
-- 3. sai_mock_bank: 거래 (과거 거래를 모두 넣은 뒤 새 거래를 넣어 새 거래 ID 가 항상 더 크다)
-- ---------------------------------------------------------------------------
INSERT INTO sai_mock_bank.bank_transaction
    (transaction_key, transaction_type, amount, balance_after, counterparty_name,
     counterparty_account_number, memo, transaction_at, transfer_id, account_id)
SELECT CONCAT('LTS-H-', t.account_id, '-', s.seq),
       IF(s.seq MOD 2 = 0, 'DEPOSIT', 'WITHDRAW'),
       1000 + s.seq * 10, 1000000, '부하테스트거래처', '999-000-000000', '과거 거래',
       NOW() - INTERVAL (@history_per_account - s.seq + 1) DAY,
       @transfer_id, t.account_id
FROM tmp_sync_account t
JOIN seq_1_to_1000 s ON s.seq <= @history_per_account;

INSERT INTO sai_mock_bank.bank_transaction
    (transaction_key, transaction_type, amount, balance_after, counterparty_name,
     counterparty_account_number, memo, transaction_at, transfer_id, account_id)
SELECT CONCAT('LTS-N-', t.account_id, '-', s.seq),
       IF(s.seq MOD 3 = 0, 'WITHDRAW', 'DEPOSIT'),
       1234 + s.seq * 7, 1000000, '부하테스트입금', '999-000-000001', '오늘 거래',
       NOW() - INTERVAL 1 HOUR + INTERVAL s.seq MINUTE,
       @transfer_id, t.account_id
FROM tmp_sync_account t
JOIN seq_1_to_1000 s ON s.seq <= @new_per_account;

-- ---------------------------------------------------------------------------
-- 4. sai_backend: 과거 거래는 이미 동기화된 상태로 만든다 (거래 저장 + 커서 이동)
-- ---------------------------------------------------------------------------
INSERT INTO bank_transaction
    (linked_account_id, external_transaction_id, amount, transaction_type, transaction_at,
     counterparty_name, memo, synced_at, processing_status, retry_count)
SELECT t.linked_account_id, mt.transaction_key, mt.amount,
       IF(mt.transaction_type = 'DEPOSIT', 'DEPOSIT', 'WITHDRAWAL'), mt.transaction_at,
       mt.counterparty_name, mt.memo, NOW(), 'UNMATCHED', 0
FROM tmp_sync_account t
JOIN sai_mock_bank.bank_transaction mt ON mt.account_id = t.account_id
WHERE mt.transaction_key LIKE 'LTS-H-%';

UPDATE linked_bank_account a
JOIN (
    SELECT t.linked_account_id, MAX(mt.transaction_id) AS cursor_id
    FROM tmp_sync_account t
    JOIN sai_mock_bank.bank_transaction mt ON mt.account_id = t.account_id
    WHERE mt.transaction_key LIKE 'LTS-H-%'
    GROUP BY t.linked_account_id
) c ON c.linked_account_id = a.linked_account_id
SET a.last_synced_transaction_id = c.cursor_id;

ANALYZE TABLE linked_bank_account, bank_transaction;
ANALYZE TABLE sai_mock_bank.bank_transaction, sai_mock_bank.bank_account, sai_mock_bank.bank_user;

-- ---------------------------------------------------------------------------
-- 5. 확인
-- ---------------------------------------------------------------------------
SELECT 'sync_accounts' AS item, COUNT(*) AS cnt FROM tmp_sync_account
UNION ALL SELECT 'available_accounts_all', COUNT(*) FROM linked_bank_account WHERE connection_status = 'AVAILABLE'
UNION ALL SELECT 'mock_history_tx', COUNT(*) FROM sai_mock_bank.bank_transaction WHERE transaction_key LIKE 'LTS-H-%'
UNION ALL SELECT 'mock_new_tx (= 배치 후 저장될 건수)', COUNT(*) FROM sai_mock_bank.bank_transaction WHERE transaction_key LIKE 'LTS-N-%'
UNION ALL SELECT 'backend_synced_history_tx', COUNT(*) FROM bank_transaction WHERE external_transaction_id LIKE 'LTS-H-%'
UNION ALL SELECT 'cursor_not_set', COUNT(*) FROM tmp_sync_account t
          JOIN linked_bank_account a ON a.linked_account_id = t.linked_account_id
          WHERE a.last_synced_transaction_id = 0;
