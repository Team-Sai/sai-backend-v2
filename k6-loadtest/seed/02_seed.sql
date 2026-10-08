

-- 부하테스트용 목 데이터 (MariaDB 10.x 이상, 기본 활성화된 SEQUENCE 엔진의 seq_1_to_N 사용)
--
-- 전제: 앱을 한 번 실행해 스키마가 만들어져 있어야 하고, 01_cleanup.sql 을 먼저 실행한다.
-- 모든 시드 계정: lt_user_0001@sai.com ~ lt_user_1000@sai.com / 비밀번호 loadtest1234!
--
-- 생성 규모 (핵심 테이블 1만 건 기준)
--   users                 1,000   (0001~0010 은 데이터가 몰린 헤비 유저)
--   linked_bank_account   1,000
--   loan_contract        10,000   (COMPLETED 85%, PENDING 5%, TERMINATED 5%, DRAFT 5%)
--   repayment_schedule   ~108,000 (COMPLETED/TERMINATED 계약당 12회차)
--   settlement           10,000   (SHARED 90%, RECURRING 10% / IN_PROGRESS 70%, CLOSED 30%)
--   settlement_participant 40,000 (정산당 OWNER 1 + MEMBER 3)
--   payment_obligation   30,000   (MEMBER 당 1건)
--   bank_transaction / payment_record  ~110,000 (상환·납부 완료분)
--   notification         ~45,000
--
-- 사용자 분포
--   계약/정산의 10% 는 헤비 유저 10명에게 몰아준다 -> 헤비 유저 1명당 계약 약 120건, 정산 약 130건.
--   나머지 990명은 계약 약 19건, 정산 약 40건 수준이다.
--   실서비스처럼 데이터가 고르게 퍼져 있지 않은 상황(N+1, 메모리 집계)을 재현하려는 의도다.

SET @pw  := '$2a$10$qgVbFuBWa6d.s5Y5p3QbW.r8NO5mhFDm92hyen4P44IlTukjkT.gm'; -- BCrypt("loadtest1234!")
SET @sig := REPEAT('A', 2048); -- 서명 이미지(base64) 크기 흉내. 실제 서명 크기에 맞춰 조절

-- ---------------------------------------------------------------------------
-- 1. 사용자 / 연결 계좌
-- ---------------------------------------------------------------------------
INSERT INTO users (user_token, email, password, name, birth_date)
SELECT CONCAT('lt-', LPAD(seq, 6, '0'), '-', LEFT(REPLACE(UUID(), '-', ''), 26)),
       CONCAT('lt_user_', LPAD(seq, 4, '0'), '@sai.com'),
       @pw,
       CONCAT('부하유저', seq),
       DATE('1975-01-01') + INTERVAL (seq * 11) MOD 10000 DAY
FROM seq_1_to_1000;

DROP TABLE IF EXISTS tmp_lt_user;
CREATE TABLE tmp_lt_user (
    n                 INT    NOT NULL PRIMARY KEY,
    user_id           BIGINT NOT NULL,
    name              VARCHAR(50) NOT NULL,
    linked_account_id BIGINT NULL
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
INSERT INTO tmp_lt_user (n, user_id, name)
SELECT CAST(SUBSTRING(email, 9, 4) AS UNSIGNED), user_id, name
FROM users
WHERE email LIKE 'lt\_user\_%@sai.com';

INSERT INTO linked_bank_account
    (user_id, bank_code, account_number, account_alias, account_holder_name,
     connection_status, account_id, balance, last_synced_transaction_id)
SELECT user_id, '088', CONCAT('110-', LPAD(n, 3, '0'), '-', LPAD(n * 7919 MOD 1000000, 6, '0')),
       '부하테스트 통장', name, 'AVAILABLE', 900000000 + n, 1000000, 0
FROM tmp_lt_user;

UPDATE tmp_lt_user t
JOIN linked_bank_account a ON a.user_id = t.user_id AND a.account_id = 900000000 + t.n
SET t.linked_account_id = a.linked_account_id;

-- ---------------------------------------------------------------------------
-- 2. 차용 계약 10,000건
--    alias 앞에 [LT#00001] 태그를 붙여 시드 데이터임을 구분한다.
-- ---------------------------------------------------------------------------
INSERT INTO loan_contract
    (creditor_id, debtor_id, relation_type, principal_amount, interest_rate, repayment_type,
     start_date, maturity_date, repayment_day, status, creditor_address, debtor_address,
     contract_alias, terms, creditor_signature, debtor_signature, created_at, updated_at)
SELECT cu.user_id,
       IF(c.st = 'DRAFT', NULL, du.user_id),
       IF(c.k MOD 3 = 0, 'FAMILY', 'ACQUAINTANCE'),
       (1 + (c.k * 37) MOD 500) * 100000,
       1 + c.k MOD 12,
       ELT(1 + c.k MOD 3, 'EQUAL_PRINCIPAL_AND_INTEREST', 'EQUAL_PRINCIPAL', 'BULLET_REPAYMENT'),
       c.start_date,
       c.start_date + INTERVAL 12 MONTH,
       LEAST(DAY(c.start_date), 28),
       c.st,
       '서울특별시 중구 세종대로9길 20',
       IF(c.st = 'DRAFT', NULL, '서울특별시 강남구 테헤란로 152'),
       CONCAT('[LT#', LPAD(c.k, 5, '0'), '] ',
              ELT(1 + c.k MOD 6, '생활비', '전세보증금', '학자금', '사업자금', '병원비', '차량구입')),
       '제1조(목적) 부하테스트용 차용 계약입니다.',
       IF(c.st = 'DRAFT', NULL, @sig),
       IF(c.st IN ('COMPLETED', 'TERMINATED'), @sig, NULL),
       c.start_date - INTERVAL 3 DAY + INTERVAL (c.k * 97) MOD 86400 SECOND,
       c.start_date - INTERVAL 3 DAY + INTERVAL (c.k * 97) MOD 86400 SECOND
FROM (
    SELECT seq AS k,
           IF(seq MOD 10 = 0, 1 + (seq DIV 10) MOD 10, 1 + ((seq - seq DIV 10) * 7919) MOD 1000) AS cn,
           CURDATE() - INTERVAL (seq * 13) MOD 730 DAY AS start_date,
           CASE
               WHEN seq MOD 20 < 17 THEN 'COMPLETED'
               WHEN seq MOD 20 = 17 THEN 'PENDING'
               WHEN seq MOD 20 = 18 THEN 'TERMINATED'
               ELSE 'DRAFT'
           END AS st
    FROM seq_1_to_10000
) c
JOIN tmp_lt_user cu ON cu.n = c.cn
-- 채무자는 채권자와 겹치지 않도록 1~999 만큼 떨어진 사용자로 고른다
JOIN tmp_lt_user du ON du.n = 1 + (c.cn + (c.k * 104729) MOD 999) MOD 1000;

-- ---------------------------------------------------------------------------
-- 3. 상환 스케줄 (COMPLETED / TERMINATED 계약, 12회차)
--    이자는 원금 기준 단순 월할 계산이다. 지난 회차는 90% PAID, 10% OVERDUE.
-- ---------------------------------------------------------------------------
INSERT INTO repayment_schedule
    (contract_id, sequence, due_date, principal_due, interest_due, total_payment_due,
     remaining_principal, status, paid_at, created_at)
SELECT x.contract_id,
       x.sq,
       x.due_date,
       x.principal_due,
       x.interest_due,
       x.principal_due + x.interest_due,
       IF(x.sq = 12, 0, GREATEST(x.principal_amount - x.principal_cum, 0)),
       CASE
           WHEN x.due_date >= CURDATE() THEN 'PENDING'
           WHEN (x.contract_id + x.sq) MOD 10 = 0 THEN 'OVERDUE'
           ELSE 'PAID'
       END,
       IF(x.due_date < CURDATE() AND (x.contract_id + x.sq) MOD 10 <> 0,
          x.due_date + INTERVAL 10 HOUR, NULL),
       x.created_at
FROM (
    SELECT lc.contract_id,
           s.seq AS sq,
           lc.principal_amount,
           lc.created_at,
           lc.start_date + INTERVAL s.seq MONTH AS due_date,
           CASE
               WHEN lc.repayment_type = 'BULLET_REPAYMENT' THEN IF(s.seq = 12, lc.principal_amount, 0)
               ELSE ROUND(lc.principal_amount / 12, 2)
           END AS principal_due,
           ROUND(lc.principal_amount * lc.interest_rate / 100 / 12, 2) AS interest_due,
           CASE
               WHEN lc.repayment_type = 'BULLET_REPAYMENT' THEN IF(s.seq = 12, lc.principal_amount, 0)
               ELSE ROUND(lc.principal_amount / 12, 2) * s.seq
           END AS principal_cum
    FROM loan_contract lc
    JOIN seq_1_to_12 s
    WHERE lc.contract_alias LIKE '[LT#%'
      AND lc.status IN ('COMPLETED', 'TERMINATED')
) x;

-- 납부 완료 회차 -> 채권자 계좌 입금 거래 + 상환 기록
INSERT INTO bank_transaction
    (linked_account_id, external_transaction_id, amount, transaction_type, processing_status,
     transaction_at, counterparty_name, memo, synced_at)
SELECT cu.linked_account_id,
       CONCAT('LT-L-', rs.schedule_id),
       rs.total_payment_due,
       'DEPOSIT',
       'APPLIED',
       rs.paid_at,
       du.name,
       '차용금 상환',
       rs.paid_at + INTERVAL 5 MINUTE
FROM repayment_schedule rs
JOIN loan_contract lc ON lc.contract_id = rs.contract_id
JOIN tmp_lt_user cu ON cu.user_id = lc.creditor_id
JOIN tmp_lt_user du ON du.user_id = lc.debtor_id
WHERE lc.contract_alias LIKE '[LT#%'
  AND rs.status = 'PAID';

INSERT INTO payment_record
    (bank_transaction_id, payment_target_type, target_id, amount, source_type, record_status, recorded_at)
SELECT bank_transaction_id, 'LOAN', CAST(SUBSTRING(external_transaction_id, 6) AS UNSIGNED),
       amount, 'AUTO_MATCH', 'CONFIRMED', synced_at
FROM bank_transaction
WHERE external_transaction_id LIKE 'LT-L-%';

-- ---------------------------------------------------------------------------
-- 4. 정산 10,000건
--    title 앞의 [LT#00001] 태그(10자)로 시드 순번과 실제 ID 를 매핑한다.
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS tmp_lt_settlement;
CREATE TABLE tmp_lt_settlement (
    k                       INT          NOT NULL PRIMARY KEY,
    tag                     VARCHAR(10)  NOT NULL,
    owner_n                 INT          NOT NULL,
    owner_id                BIGINT       NOT NULL,
    owner_account_id        BIGINT       NOT NULL,
    step                    INT          NOT NULL,
    settlement_type         VARCHAR(10)  NOT NULL,
    settlement_status       VARCHAR(12)  NOT NULL,
    category                VARCHAR(50)  NOT NULL,
    title                   VARCHAR(200) NOT NULL,
    total_amount            DECIMAL(19, 2) NOT NULL,
    created_at              DATETIME     NOT NULL,
    due_date                DATE         NOT NULL,
    recurring_settlement_id BIGINT       NULL,
    settlement_id           BIGINT       NULL
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

INSERT INTO tmp_lt_settlement
    (k, tag, owner_n, owner_id, owner_account_id, step, settlement_type, settlement_status,
     category, title, total_amount, created_at, due_date)
SELECT b.k,
       CONCAT('[LT#', LPAD(b.k, 5, '0'), ']'),
       u.n,
       u.user_id,
       u.linked_account_id,
       1 + b.k MOD 300,
       IF(b.k MOD 10 = 5, 'RECURRING', 'SHARED'),
       IF(b.k MOD 10 IN (1, 2, 3), 'CLOSED', 'IN_PROGRESS'),
       IF(b.k MOD 10 = 5,
          ELT(1 + b.k MOD 4, 'OTT·구독', '정기회비', '공과금', '공동생활비'),
          ELT(1 + b.k MOD 6, '여행', '생활비', '회식', '공동구매', '모임', '기타')),
       CONCAT('[LT#', LPAD(b.k, 5, '0'), '] ',
              IF(b.k MOD 10 = 5, '정기 정산', ELT(1 + b.k MOD 4, '제주 여행', '팀 회식', '생활비 나눔', '모임 회비'))),
       (1 + (b.k * 31) MOD 200) * 4000,
       NOW() - INTERVAL (b.k * 17) MOD 365 DAY - INTERVAL (b.k * 97) MOD 86400 SECOND,
       DATE(NOW() - INTERVAL (b.k * 17) MOD 365 DAY) + INTERVAL 14 DAY
FROM (
    SELECT seq AS k,
           IF(seq MOD 10 = 0, 1 + (seq DIV 10) MOD 10, 1 + ((seq - seq DIV 10) * 6007) MOD 1000) AS owner_n
    FROM seq_1_to_10000
) b
JOIN tmp_lt_user u ON u.n = b.owner_n;

INSERT INTO recurring_settlement
    (owner_id, settlement_category, title, split_type, total_amount, cycle_rule, start_date, end_date, created_at)
SELECT owner_id, category, title, 'EQUAL', total_amount, 'MONTHLY',
       DATE(created_at), DATE(created_at) + INTERVAL 12 MONTH, created_at
FROM tmp_lt_settlement
WHERE settlement_type = 'RECURRING';

UPDATE tmp_lt_settlement t
JOIN recurring_settlement r ON r.owner_id = t.owner_id AND LEFT(r.title, 10) = t.tag
SET t.recurring_settlement_id = r.recurring_settlement_id
WHERE t.settlement_type = 'RECURRING';

INSERT INTO settlement
    (recurring_settlement_id, owner_id, settlement_type, settlement_status, settlement_category,
     title, split_type, total_amount, due_date, created_at, closed_at, cycle_date)
SELECT recurring_settlement_id, owner_id, settlement_type, settlement_status, category,
       title, 'EQUAL', total_amount, due_date, created_at,
       IF(settlement_status = 'CLOSED', created_at + INTERVAL 20 DAY, NULL),
       IF(settlement_type = 'RECURRING', DATE(created_at), NULL)
FROM tmp_lt_settlement;

UPDATE tmp_lt_settlement t
JOIN settlement s ON s.owner_id = t.owner_id AND LEFT(s.title, 10) = t.tag
SET t.settlement_id = s.settlement_id;

INSERT INTO settlement_account (settlement_id, linked_account_id, account_status, selected_at)
SELECT settlement_id, owner_account_id, 'ACTIVE', created_at
FROM tmp_lt_settlement;

-- 참여자: OWNER 1명 + MEMBER 3명 (step 간격으로 뽑아 서로 겹치지 않게 함)
INSERT INTO settlement_participant (settlement_id, user_id, participant_role, participant_status, joined_at)
SELECT settlement_id, owner_id, 'OWNER', 'ACTIVE', created_at
FROM tmp_lt_settlement;

INSERT INTO settlement_participant (settlement_id, user_id, participant_role, participant_status, joined_at)
SELECT t.settlement_id, mu.user_id, 'MEMBER', 'ACTIVE', t.created_at + INTERVAL 1 MINUTE
FROM tmp_lt_settlement t
JOIN seq_1_to_3 m
JOIN tmp_lt_user mu ON mu.n = 1 + (t.owner_n - 1 + m.seq * t.step) MOD 1000;

-- 납부 의무: MEMBER 1인당 총액/4. CLOSED 정산은 전원 PAID, 진행 중 정산은 UNPAID/PARTIALLY_PAID/PAID 를 섞는다.
INSERT INTO payment_obligation
    (participant_id, expected_amount, payment_status, review_status, obligation_status, overdue_since)
SELECT x.participant_id,
       x.expected_amount,
       x.payment_status,
       'NORMAL',
       'ACTIVE',
       IF(x.payment_status <> 'PAID' AND x.due_date < CURDATE(), x.due_date + INTERVAL 1 DAY, NULL)
FROM (
    SELECT p.participant_id,
           t.total_amount / 4 AS expected_amount,
           t.due_date,
           CASE
               WHEN t.settlement_status = 'CLOSED' THEN 'PAID'
               WHEN p.participant_id MOD 3 = 0 THEN 'UNPAID'
               WHEN p.participant_id MOD 3 = 1 THEN 'PARTIALLY_PAID'
               ELSE 'PAID'
           END AS payment_status
    FROM settlement_participant p
    JOIN tmp_lt_settlement t ON t.settlement_id = p.settlement_id
    WHERE p.participant_role = 'MEMBER'
) x;

-- 납부분 -> 정산 소유자 계좌 입금 거래 + 납부 기록 (PARTIALLY_PAID 는 절반만 납부)
INSERT INTO bank_transaction
    (linked_account_id, external_transaction_id, amount, transaction_type, processing_status,
     transaction_at, counterparty_name, memo, synced_at)
SELECT t.owner_account_id,
       CONCAT('LT-S-', po.payment_obligation_id),
       IF(po.payment_status = 'PAID', po.expected_amount, po.expected_amount / 2),
       'DEPOSIT',
       'APPLIED',
       t.created_at + INTERVAL 2 DAY,
       mu.name,
       '정산 입금',
       t.created_at + INTERVAL 2 DAY + INTERVAL 5 MINUTE
FROM payment_obligation po
JOIN settlement_participant p ON p.participant_id = po.participant_id
JOIN tmp_lt_settlement t ON t.settlement_id = p.settlement_id
JOIN tmp_lt_user mu ON mu.user_id = p.user_id
WHERE po.payment_status IN ('PAID', 'PARTIALLY_PAID');

INSERT INTO payment_record
    (bank_transaction_id, payment_target_type, target_id, amount, source_type, record_status, recorded_at)
SELECT bank_transaction_id, 'SETTLEMENT', CAST(SUBSTRING(external_transaction_id, 6) AS UNSIGNED),
       amount, 'AUTO_MATCH', 'CONFIRMED', synced_at
FROM bank_transaction
WHERE external_transaction_id LIKE 'LT-S-%';

-- ---------------------------------------------------------------------------
-- 5. 알림
-- ---------------------------------------------------------------------------
-- 계약 요청 알림 -> 채무자
INSERT INTO notification (user_id, notification_type, title, content, reference_id, secondary_reference_id, created_at)
SELECT lc.debtor_id, 'CONTRACT_REQUESTED', '차용증 서명 요청이 도착했어요',
       CONCAT(lc.contract_alias, ' 계약서를 확인해 주세요.'), lc.contract_id, NULL, lc.created_at
FROM loan_contract lc
WHERE lc.contract_alias LIKE '[LT#%'
  AND lc.debtor_id IS NOT NULL;

-- 정산 참여 알림 -> MEMBER
INSERT INTO notification (user_id, notification_type, title, content, reference_id, secondary_reference_id, created_at)
SELECT p.user_id, 'SETTLEMENT_PARTICIPANT_ADDED', '정산에 초대되었어요',
       CONCAT(t.title, ' 정산에 참여자로 추가되었습니다.'), t.settlement_id, NULL, p.joined_at
FROM settlement_participant p
JOIN tmp_lt_settlement t ON t.settlement_id = p.settlement_id
WHERE p.participant_role = 'MEMBER';

-- 연체 회차 상환일 알림 -> 채무자
INSERT INTO notification (user_id, notification_type, title, content, reference_id, secondary_reference_id, created_at)
SELECT lc.debtor_id, 'REPAYMENT_DUE_REMINDER_DDAY', '오늘은 상환일이에요',
       CONCAT(lc.contract_alias, ' ', rs.sequence, '회차 상환일입니다.'), rs.schedule_id, lc.contract_id,
       rs.due_date + INTERVAL 9 HOUR
FROM repayment_schedule rs
JOIN loan_contract lc ON lc.contract_id = rs.contract_id
WHERE lc.contract_alias LIKE '[LT#%'
  AND rs.status = 'OVERDUE';

-- ---------------------------------------------------------------------------
-- 6. 마무리
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS tmp_lt_user;
DROP TABLE IF EXISTS tmp_lt_settlement;

ANALYZE TABLE users, linked_bank_account, loan_contract, repayment_schedule, settlement,
    settlement_participant, settlement_account, recurring_settlement, payment_obligation,
    payment_record, bank_transaction, notification;

-- 결과 확인
SELECT 'users' AS tbl, COUNT(*) AS cnt FROM users WHERE email LIKE 'lt\_user\_%'
UNION ALL SELECT 'loan_contract', COUNT(*) FROM loan_contract WHERE contract_alias LIKE '[LT#%'
UNION ALL SELECT 'repayment_schedule', COUNT(*) FROM repayment_schedule rs JOIN loan_contract lc ON lc.contract_id = rs.contract_id WHERE lc.contract_alias LIKE '[LT#%'
UNION ALL SELECT 'settlement', COUNT(*) FROM settlement WHERE title LIKE '[LT#%'
UNION ALL SELECT 'settlement_participant', COUNT(*) FROM settlement_participant sp JOIN settlement s ON s.settlement_id = sp.settlement_id WHERE s.title LIKE '[LT#%'
UNION ALL SELECT 'bank_transaction', COUNT(*) FROM bank_transaction WHERE external_transaction_id LIKE 'LT-%'
UNION ALL SELECT 'notification', COUNT(*) FROM notification n JOIN users u ON u.user_id = n.user_id WHERE u.email LIKE 'lt\_user\_%';
