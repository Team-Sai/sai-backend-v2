-- 부하테스트 시드(lt_user_*) 데이터 삭제
-- 02_seed.sql 실행 전, 그리고 테스트가 끝난 뒤 실행한다. 여러 번 실행해도 안전하다.
-- 시드 사용자와 연결된 데이터만 FK 역순으로 지운다.

DROP TABLE IF EXISTS tmp_lt_ids;
CREATE TABLE tmp_lt_ids (PRIMARY KEY (user_id))
    AS SELECT user_id FROM users WHERE email LIKE 'lt\_user\_%@sai.com';

DROP TABLE IF EXISTS tmp_lt_accounts;
CREATE TABLE tmp_lt_accounts (PRIMARY KEY (linked_account_id))
    AS SELECT linked_account_id FROM linked_bank_account WHERE user_id IN (SELECT user_id FROM tmp_lt_ids);

DROP TABLE IF EXISTS tmp_lt_settlements;
CREATE TABLE tmp_lt_settlements (PRIMARY KEY (settlement_id))
    AS SELECT settlement_id FROM settlement WHERE owner_id IN (SELECT user_id FROM tmp_lt_ids);

DROP TABLE IF EXISTS tmp_lt_contracts;
CREATE TABLE tmp_lt_contracts (PRIMARY KEY (contract_id))
    AS SELECT contract_id FROM loan_contract
       WHERE creditor_id IN (SELECT user_id FROM tmp_lt_ids)
          OR debtor_id IN (SELECT user_id FROM tmp_lt_ids);

DELETE FROM notification WHERE user_id IN (SELECT user_id FROM tmp_lt_ids);

DELETE FROM payment_record
WHERE bank_transaction_id IN (
    SELECT bank_transaction_id FROM bank_transaction
    WHERE linked_account_id IN (SELECT linked_account_id FROM tmp_lt_accounts)
);
DELETE FROM bank_transaction_match_candidate
WHERE bank_transaction_id IN (
    SELECT bank_transaction_id FROM bank_transaction
    WHERE linked_account_id IN (SELECT linked_account_id FROM tmp_lt_accounts)
);
DELETE FROM bank_transaction WHERE linked_account_id IN (SELECT linked_account_id FROM tmp_lt_accounts);

DELETE FROM payment_obligation
WHERE participant_id IN (
    SELECT participant_id FROM settlement_participant
    WHERE settlement_id IN (SELECT settlement_id FROM tmp_lt_settlements)
);
DELETE FROM settlement_abandonment_alert WHERE settlement_id IN (SELECT settlement_id FROM tmp_lt_settlements);
DELETE FROM settlement_account
WHERE settlement_id IN (SELECT settlement_id FROM tmp_lt_settlements)
   OR linked_account_id IN (SELECT linked_account_id FROM tmp_lt_accounts);
DELETE FROM settlement_invitation
WHERE settlement_id IN (SELECT settlement_id FROM tmp_lt_settlements)
   OR user_id IN (SELECT user_id FROM tmp_lt_ids);
DELETE FROM settlement_participant
WHERE settlement_id IN (SELECT settlement_id FROM tmp_lt_settlements)
   OR user_id IN (SELECT user_id FROM tmp_lt_ids);
DELETE FROM settlement WHERE settlement_id IN (SELECT settlement_id FROM tmp_lt_settlements);
DELETE FROM recurring_settlement WHERE owner_id IN (SELECT user_id FROM tmp_lt_ids);

DELETE FROM contract_account
WHERE contract_id IN (SELECT contract_id FROM tmp_lt_contracts)
   OR linked_account_id IN (SELECT linked_account_id FROM tmp_lt_accounts);
DELETE FROM loan_contract_change_request WHERE contract_id IN (SELECT contract_id FROM tmp_lt_contracts);
DELETE FROM repayment_schedule WHERE contract_id IN (SELECT contract_id FROM tmp_lt_contracts);
UPDATE loan_contract SET previous_contract_id = NULL WHERE contract_id IN (SELECT contract_id FROM tmp_lt_contracts);
DELETE FROM loan_contract WHERE contract_id IN (SELECT contract_id FROM tmp_lt_contracts);

DELETE FROM linked_bank_account WHERE linked_account_id IN (SELECT linked_account_id FROM tmp_lt_accounts);
DELETE FROM `identity` WHERE user_id IN (SELECT user_id FROM tmp_lt_ids);
DELETE FROM users WHERE user_id IN (SELECT user_id FROM tmp_lt_ids);

DROP TABLE IF EXISTS tmp_lt_ids;
DROP TABLE IF EXISTS tmp_lt_accounts;
DROP TABLE IF EXISTS tmp_lt_settlements;
DROP TABLE IF EXISTS tmp_lt_contracts;
