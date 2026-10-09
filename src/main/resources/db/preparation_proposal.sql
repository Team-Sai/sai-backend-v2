CREATE TABLE IF NOT EXISTS preparation_proposal (
                                      proposal_id VARCHAR(36) NOT NULL,
                                      user_id BIGINT NOT NULL,
                                      payload_json LONGTEXT NOT NULL,
                                      created_at TIMESTAMP(6) NOT NULL,
                                      expires_at TIMESTAMP(6) NOT NULL,
                                      confirmed_at TIMESTAMP(6) NULL,
                                      result_json LONGTEXT NULL,

                                      PRIMARY KEY (proposal_id),

                                      INDEX idx_proposal_user_created (user_id, created_at),

                                      CONSTRAINT fk_preparation_proposal_user
                                          FOREIGN KEY (user_id) REFERENCES users(user_id)
);