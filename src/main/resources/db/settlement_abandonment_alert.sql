CREATE TABLE IF NOT EXISTS settlement_abandonment_alert (
    settlement_id BIGINT NOT NULL,
    reference_date DATE NOT NULL,
    notified_at DATETIME NULL DEFAULT NULL,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'SENT',
    message TEXT NULL,
    PRIMARY KEY (settlement_id, reference_date),
    INDEX idx_abandonment_delivery (delivery_status, settlement_id, reference_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Existing rows represent previously processed alerts and must not be replayed.
-- New application records explicitly use PENDING and leave notified_at null.
ALTER TABLE settlement_abandonment_alert
    ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(16) NOT NULL DEFAULT 'SENT',
    ADD COLUMN IF NOT EXISTS message TEXT NULL,
    MODIFY COLUMN notified_at DATETIME NULL DEFAULT NULL;
CREATE INDEX IF NOT EXISTS idx_abandonment_delivery
    ON settlement_abandonment_alert (delivery_status, settlement_id, reference_date);
