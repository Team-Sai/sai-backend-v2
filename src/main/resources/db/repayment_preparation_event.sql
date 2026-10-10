CREATE TABLE IF NOT EXISTS repayment_preparation_event (
                                             event_id BIGINT NOT NULL AUTO_INCREMENT,
                                             user_id BIGINT NOT NULL,
                                             contract_id BIGINT NOT NULL,
                                             schedule_id BIGINT NOT NULL,
                                             title VARCHAR(120) NOT NULL,
                                             starts_at TIMESTAMP(6) NOT NULL,
                                             ends_at TIMESTAMP(6) NOT NULL,
                                             created_at TIMESTAMP(6) NOT NULL,
                                             reminder_processed_at TIMESTAMP(6) NULL,
                                             revision BIGINT NOT NULL DEFAULT 0,

                                             PRIMARY KEY (event_id),

                                             CONSTRAINT uk_preparation_user_schedule
                                                 UNIQUE (user_id, schedule_id),

                                             INDEX idx_preparation_user_time (user_id, starts_at),
                                             INDEX idx_preparation_reminder_due (reminder_processed_at, starts_at, user_id),

                                             CONSTRAINT fk_preparation_user
                                                 FOREIGN KEY (user_id) REFERENCES users(user_id),

                                             CONSTRAINT fk_preparation_contract
                                                 FOREIGN KEY (contract_id)
                                                     REFERENCES loan_contract(contract_id),

                                             CONSTRAINT fk_preparation_schedule
                                                 FOREIGN KEY (schedule_id)
                                                     REFERENCES repayment_schedule(schedule_id)
);

ALTER TABLE repayment_preparation_event
    ADD COLUMN IF NOT EXISTS reminder_processed_at TIMESTAMP(6) NULL,
    ADD INDEX IF NOT EXISTS idx_preparation_reminder_due (reminder_processed_at, starts_at, user_id);

ALTER TABLE repayment_preparation_event
    ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 0;