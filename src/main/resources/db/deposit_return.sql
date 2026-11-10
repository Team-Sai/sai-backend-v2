CREATE TABLE IF NOT EXISTS deposit_return (
                                              deposit_return_id BIGINT NOT NULL AUTO_INCREMENT,
                                              contract_id BIGINT NOT NULL,
                                              bank_transaction_id BIGINT NOT NULL,
                                              amount DECIMAL(15, 2) NOT NULL,
    transaction_at DATETIME NOT NULL,
    returned_at DATETIME NOT NULL,
    PRIMARY KEY (deposit_return_id),
    UNIQUE KEY uk_deposit_return_bank_transaction (bank_transaction_id),
    CONSTRAINT fk_deposit_return_contract FOREIGN KEY (contract_id) REFERENCES loan_contract(contract_id) ON DELETE CASCADE
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;