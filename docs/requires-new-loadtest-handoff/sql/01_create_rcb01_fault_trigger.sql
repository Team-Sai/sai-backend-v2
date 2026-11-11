-- RCB01: deterministic fault injection used for the one-user consistency reproduction.
-- Database: sai_wanjang. This is a test-only trigger; it deliberately makes sync fail.
-- It rejects ONLY updates of RCB01-prefixed PENDING transactions to APPLIED.
-- Review SHOW TRIGGERS and confirm target DB before creating it.
USE sai_wanjang;

DROP TRIGGER IF EXISTS trg_lt_rcb01_reject_applied;
DELIMITER //
CREATE TRIGGER trg_lt_rcb01_reject_applied
BEFORE UPDATE ON bank_transaction
FOR EACH ROW
BEGIN
    IF OLD.external_transaction_id LIKE 'RCB01-%'
       AND OLD.processing_status = 'PENDING'
       AND NEW.processing_status = 'APPLIED' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'RCB01 load-test: reject outer transaction status update';
    END IF;
END//
DELIMITER ;

-- Verify this trigger exists before running the fault-injection scenario:
SHOW TRIGGERS FROM sai_wanjang LIKE 'bank_transaction';
