CREATE TABLE IF NOT EXISTS account_link_operation (
    operation_id varchar(64) NOT NULL,
    user_id bigint NOT NULL,
    request_hash varchar(64) NOT NULL,
    previous_user_key varchar(255) DEFAULT NULL,
    new_user_key varchar(255) DEFAULT NULL,
    status varchar(32) NOT NULL,
    created_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation_id),
    KEY idx_link_operation_user_status (user_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE account_link_operation MODIFY COLUMN new_user_key VARCHAR(255) NULL;
