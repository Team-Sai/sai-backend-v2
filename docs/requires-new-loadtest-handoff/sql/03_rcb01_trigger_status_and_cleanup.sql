-- RCB01 trigger status check (read-only): run on sai_wanjang.
USE sai_wanjang;
SHOW TRIGGERS FROM sai_wanjang LIKE 'bank_transaction';

-- If trg_lt_rcb01_reject_applied is present and the experiment is over,
-- remove ONLY this test trigger. This does not delete the captured mismatch rows.
DROP TRIGGER IF EXISTS sai_wanjang.trg_lt_rcb01_reject_applied;

-- Confirm removal: this should return no row for trg_lt_rcb01_reject_applied.
SHOW TRIGGERS FROM sai_wanjang LIKE 'bank_transaction';
