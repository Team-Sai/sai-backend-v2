-- RCB01: historical test fixture preflight / inventory (read-only)
-- Run after connecting to sai_wanjang. This does not seed or delete rows.
-- Historical baseline recorded before the test: 100 lt_user accounts / 300 linked accounts.
USE sai_wanjang;

SELECT COUNT(*) AS lt_users
FROM users
WHERE email REGEXP '^lt_user_[0-9]{4}@sai[.]com$';

SELECT COUNT(*) AS available_test_linked_accounts
FROM linked_bank_account a
JOIN users u ON u.user_id = a.user_id
WHERE u.email REGEXP '^lt_user_[0-9]{4}@sai[.]com$'
  AND a.connection_status = 'AVAILABLE';

SELECT bt.processing_status, COUNT(*) AS transaction_count
FROM bank_transaction bt
WHERE bt.external_transaction_id LIKE 'RCB01-%'
GROUP BY bt.processing_status
ORDER BY bt.processing_status;

SELECT COUNT(*) AS confirmed_payment_records_for_run
FROM bank_transaction bt
JOIN payment_record pr
  ON pr.bank_transaction_id = bt.bank_transaction_id
WHERE bt.external_transaction_id LIKE 'RCB01-%'
  AND pr.record_status = 'CONFIRMED';
