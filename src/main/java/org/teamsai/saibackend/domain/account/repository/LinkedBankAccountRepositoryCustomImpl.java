package org.teamsai.saibackend.domain.account.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Slf4j
@Repository
@RequiredArgsConstructor
public class LinkedBankAccountRepositoryCustomImpl
        implements LinkedBankAccountRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Long insertOne(LinkedBankAccount account) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        """
                        INSERT INTO linked_bank_account (
                            user_id,
                            account_id,
                            bank_code,
                            account_number,
                            account_alias,
                            account_holder_name,
                            balance,
                            connection_status,
                            created_at,
                            updated_at
                        )
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                        """,
                        Statement.RETURN_GENERATED_KEYS
                );

                ps.setLong(1, account.getUserId());
                ps.setLong(2, account.getAccountId());
                ps.setString(3, account.getBankCode());
                ps.setString(4, account.getAccountNumber());
                ps.setString(5, account.getAccountAlias());
                ps.setString(6, account.getAccountHolderName());
                ps.setBigDecimal(7, account.getBalance());
                ps.setString(8, account.getConnectionStatus().name());

                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            var existing = jdbcTemplate.queryForList("""
                    SELECT linked_account_id FROM linked_bank_account
                    WHERE user_id = ? AND account_id = ? FOR UPDATE
                    """, Long.class, account.getUserId(), account.getAccountId());
            if (existing.isEmpty()) {
                throw e;
            }
            log.info(
                    "이미 연동된 계좌라 저장을 건너뜁니다. userId={}, accountId={}",
                    account.getUserId(),
                    account.getAccountId()
            );
            return null;
        }

        Number key = keyHolder.getKey();

        if (key == null) {
            throw new IllegalStateException(
                    "연동 계좌 INSERT 후 generated key를 가져오지 못했습니다."
            );
        }

        return key.longValue();
    }
}
