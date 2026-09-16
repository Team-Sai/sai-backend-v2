package org.teamsai.saibackend.domain.account.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;

import java.sql.PreparedStatement;
import java.sql.Statement;

@Repository
@RequiredArgsConstructor
public class LinkedBankAccountRepositoryCustomImpl
        implements LinkedBankAccountRepositoryCustom {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public Long insertOne(LinkedBankAccount account) {
        KeyHolder keyHolder = new GeneratedKeyHolder();

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

        Number key = keyHolder.getKey();

        if (key == null) {
            throw new IllegalStateException(
                    "연동 계좌 INSERT 후 generated key를 가져오지 못했습니다."
            );
        }

        return key.longValue();
    }
}