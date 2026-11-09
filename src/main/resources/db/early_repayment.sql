CREATE TABLE IF NOT EXISTS early_repayment (
                                               early_repayment_id BIGINT NOT NULL AUTO_INCREMENT,
                                               contract_id BIGINT NOT NULL,
                                               bank_transaction_id BIGINT NOT NULL,
                                               amount DECIMAL(15, 2) NOT NULL,
    shortfall_amount DECIMAL(15, 2) NOT NULL,
    accrued_interest DECIMAL(15, 2) NOT NULL,
    fee_amount DECIMAL(15, 2) NOT NULL,
    principal_amount DECIMAL(15, 2) NOT NULL,
    processed_at DATETIME NOT NULL,
    PRIMARY KEY (early_repayment_id),
    UNIQUE KEY uk_early_repayment_bank_transaction (bank_transaction_id),
    CONSTRAINT fk_early_repayment_contract FOREIGN KEY (contract_id) REFERENCES loan_contract(contract_id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;