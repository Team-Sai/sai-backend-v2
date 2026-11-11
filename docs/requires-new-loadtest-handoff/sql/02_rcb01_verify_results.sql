-- RCB01: run AFTER all k6 sync requests and server-side work have completed.
-- A row in confirmed_but_pending is the target inconsistency.
USE sai_wanjang;

SELECT
    COUNT(*) AS total_run_transactions,
    COALESCE(SUM(bt.processing_status = 'PENDING'), 0) AS pending_transactions,
    COALESCE(SUM(bt.processing_status = 'APPLIED'), 0) AS applied_transactions,
    COALESCE(SUM(pr.record_status = 'CONFIRMED'), 0) AS confirmed_payment_rows,
    COALESCE(SUM(pr.record_status = 'CONFIRMED'
                 AND bt.processing_status = 'PENDING'), 0) AS confirmed_but_pending,
    COALESCE(SUM(bt.processing_status = 'APPLIED'
                 AND (pr.payment_record_id IS NULL OR pr.record_status <> 'CONFIRMED')), 0)
        AS applied_but_not_confirmed
FROM bank_transaction bt
LEFT JOIN payment_record pr
  ON pr.bank_transaction_id = bt.bank_transaction_id
WHERE bt.external_transaction_id LIKE 'RCB01-%';

-- Detail rows for the inconsistency, if present.
SELECT
    bt.bank_transaction_id,
    bt.external_transaction_id,
    bt.processing_status,
    pr.payment_record_id,
    pr.record_status,
    pr.payment_target_type,
    pr.target_id,
    pr.amount,
    pr.recorded_at
FROM bank_transaction bt
JOIN payment_record pr
  ON pr.bank_transaction_id = bt.bank_transaction_id
WHERE bt.external_transaction_id LIKE 'RCB01-%'
  AND pr.record_status = 'CONFIRMED'
  AND bt.processing_status = 'PENDING'
ORDER BY pr.recorded_at DESC
LIMIT 100;

-- Grouped summary used in the historical review.
SELECT bt.processing_status, COUNT(*) AS confirmed_payment_count
FROM bank_transaction bt
JOIN payment_record pr
  ON pr.bank_transaction_id = bt.bank_transaction_id
WHERE bt.external_transaction_id LIKE 'RCB01-%'
  AND pr.record_status = 'CONFIRMED'
GROUP BY bt.processing_status;
