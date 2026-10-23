-- MariaDB 전용. 격리된 부하 DB에서 01_cleanup.sql -> 02_seed.sql -> 이 파일 순서로 실행.
-- 재측정도 위 순서로 초기화한다 (이 파일 단독 재실행 금지).
-- 기본: 그룹당 100 사용자, 사용자당 100 PENDING 입금 = 총 30,000 거래.
-- mock-bank는 선택 계좌에 대해 HTTP 200과 빈 거래 배열을 반환하도록 준비한다.
-- 기존 seed의 가상 account_id는 실제 mock-bank 계좌로 교체/등록해야 한다.
-- 은행 통신은 유지하되 대량 PENDING 거래는 DB에 미리 넣어 매칭 비용을 집중 측정한다.
SET @mlt_users = 100; -- 그룹당 사용자 수. 3배가 기존 seed 사용자 수 이하여야 함
SET @mlt_trades = 100; -- 계좌당 거래 수 (최대 1000)
SET @mlt_created = NOW() - INTERVAL 2 DAY;
SET @mlt_at = NOW() - INTERVAL 1 DAY;
START TRANSACTION;
CREATE TEMPORARY TABLE mlt_fixture AS
SELECT u.user_id AS owner_id, a.linked_account_id, s.seq AS k,
       CASE WHEN n.seq <= @mlt_users THEN 'matchable'
            WHEN n.seq <= 2 * @mlt_users THEN 'ambiguous' ELSE 'unmatched' END AS cohort,
       CONCAT('MLT-', n.seq, '-', s.seq) AS tag,
       CONCAT('mlt', n.seq, 'x', s.seq) AS person
FROM seq_1_to_1000 n
JOIN users u ON u.email = CONCAT('lt_user_', LPAD(n.seq, 4, '0'), '@sai.com')
JOIN (SELECT user_id, MIN(linked_account_id) AS linked_account_id
      FROM linked_bank_account WHERE connection_status = 'AVAILABLE' GROUP BY user_id) a ON a.user_id = u.user_id
JOIN seq_1_to_1000 s ON s.seq <= @mlt_trades
WHERE n.seq <= 3 * @mlt_users;

-- 각 거래마다 다른 이름을 사용해 다른 거래의 후보와 간섭하지 않는다.
INSERT INTO users (user_token, email, password, name, birth_date)
SELECT CONCAT('mlt-', REPLACE(UUID(), '-', '')), CONCAT('lt_user_mlt_', tag, '@sai.com'),
       '$2a$10$qgVbFuBWa6d.s5Y5p3QbW.r8NO5mhFDm92hyen4P44IlTukjkT.gm', person, '1990-01-01'
FROM mlt_fixture;
ALTER TABLE mlt_fixture ADD COLUMN person_id BIGINT;
UPDATE mlt_fixture f JOIN users u ON u.email = CONCAT('lt_user_mlt_', f.tag, '@sai.com') SET f.person_id = u.user_id;

-- matchable: 짝수 거래에는 정산 후보 하나, 홀수에는 상환 후보 하나.
-- ambiguous: 동일 이름/금액의 정산 + 상환 후보 둘. unmatched: 후보 없음.
INSERT INTO settlement
(owner_id, settlement_type, settlement_status, settlement_category, title, split_type, total_amount, due_date, created_at)
SELECT owner_id, 'SHARED', 'IN_PROGRESS', '기타', tag, 'EQUAL', 20000, CURDATE() + INTERVAL 7 DAY, @mlt_created
FROM mlt_fixture WHERE cohort = 'ambiguous' OR (cohort = 'matchable' AND MOD(k, 2) = 0);
INSERT INTO settlement_account (settlement_id, linked_account_id, account_status, selected_at)
SELECT s.settlement_id, f.linked_account_id, 'ACTIVE', @mlt_created
FROM mlt_fixture f JOIN settlement s ON s.title = f.tag AND s.owner_id = f.owner_id;
INSERT INTO settlement_participant (settlement_id, user_id, participant_role, participant_status, joined_at)
SELECT s.settlement_id, f.owner_id, 'OWNER', 'ACTIVE', @mlt_created
FROM mlt_fixture f JOIN settlement s ON s.title = f.tag AND s.owner_id = f.owner_id;
INSERT INTO settlement_participant (settlement_id, user_id, participant_role, participant_status, joined_at)
SELECT s.settlement_id, f.person_id, 'MEMBER', 'ACTIVE', @mlt_created
FROM mlt_fixture f JOIN settlement s ON s.title = f.tag AND s.owner_id = f.owner_id;
INSERT INTO payment_obligation (participant_id, expected_amount, payment_status, review_status, obligation_status)
SELECT p.participant_id, 10000, 'UNPAID', 'NORMAL', 'ACTIVE'
FROM mlt_fixture f JOIN settlement s ON s.title = f.tag AND s.owner_id = f.owner_id
JOIN settlement_participant p ON p.settlement_id = s.settlement_id AND p.user_id = f.person_id;

INSERT INTO loan_contract
(creditor_id, debtor_id, relation_type, principal_amount, interest_rate, repayment_type,
 start_date, maturity_date, repayment_day, status, creditor_address, debtor_address,
 contract_alias, terms, creditor_signature, debtor_signature, created_at, updated_at)
SELECT owner_id, person_id, 'ACQUAINTANCE', 10000, 0, 'BULLET_REPAYMENT',
 DATE(@mlt_created), CURDATE() + INTERVAL 7 DAY, 1, 'COMPLETED', '테스트', '테스트',
 tag, '부하테스트', REPEAT('A', 2048), REPEAT('A', 2048), @mlt_created, @mlt_created
FROM mlt_fixture WHERE cohort = 'ambiguous' OR (cohort = 'matchable' AND MOD(k, 2) = 1);
INSERT INTO contract_account (contract_id, linked_account_id, account_status, selected_at)
SELECT c.contract_id, f.linked_account_id, 'ACTIVE', @mlt_created
FROM mlt_fixture f JOIN loan_contract c ON c.contract_alias = f.tag AND c.creditor_id = f.owner_id;
INSERT INTO repayment_schedule
(contract_id, sequence, due_date, principal_due, interest_due, total_payment_due, remaining_principal, status, created_at)
SELECT c.contract_id, 1, CURDATE() + INTERVAL 7 DAY, 10000, 0, 10000, 0, 'PENDING', @mlt_created
FROM mlt_fixture f JOIN loan_contract c ON c.contract_alias = f.tag AND c.creditor_id = f.owner_id;

INSERT INTO bank_transaction
(linked_account_id, external_transaction_id, amount, transaction_type, processing_status,
 transaction_at, counterparty_name, memo, synced_at, retry_count)
SELECT linked_account_id, tag, 10000, 'DEPOSIT', 'PENDING', @mlt_at, person,
 CONCAT('MLT:', cohort, ':', IF(MOD(k, 2) = 0, 'SETTLEMENT', 'LOAN'), ':', k), NOW(), 0
FROM mlt_fixture;
COMMIT;
SELECT cohort, COUNT(*) AS transactions, COUNT(DISTINCT linked_account_id) AS accounts FROM mlt_fixture GROUP BY cohort;
DROP TEMPORARY TABLE mlt_fixture;

