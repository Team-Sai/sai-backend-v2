package org.teamsai.saibackend.domain.transaction.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Repository
@RequiredArgsConstructor
public class JdbcBankTransactionBulkRepository
        implements BankTransactionBulkRepository {

    // 초기 상한이다. 최적 크기는 추후 측정으로 결정한다.
    private static final int MAX_ROWS_PER_INSERT = 200;

    private static final String INSERT_PREFIX = """
            INSERT INTO bank_transaction (
                linked_account_id,
                external_transaction_id,
                amount,
                transaction_type,
                processing_status,
                transaction_at,
                counterparty_name,
                memo,
                synced_at,
                retry_count
            ) VALUES
            """;

    private static final String ROW_VALUES =
            "(?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, 0)";

    private static final String DUPLICATE_SUFFIX = """

            ON DUPLICATE KEY UPDATE
                bank_transaction_id = LAST_INSERT_ID(bank_transaction_id)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertAllIfAbsent(List<BankTransaction> transactions) {
        Objects.requireNonNull(
                transactions,
                "transactions must not be null"
        );

        for (BankTransaction transaction : transactions) {
            Objects.requireNonNull(
                    transaction,
                    "transactions must not contain null"
            );
        }

        int fromIndex = 0;

        while (fromIndex < transactions.size()) {
            int chunkSize = Math.min(
                    MAX_ROWS_PER_INSERT,
                    transactions.size() - fromIndex
            );
            int toIndex = fromIndex + chunkSize;

            List<BankTransaction> chunk =
                    transactions.subList(fromIndex, toIndex);

            insertChunkIfAbsent(chunk);

            fromIndex = toIndex;
        }
    }

    private void insertChunkIfAbsent(List<BankTransaction> transactions) {
        String values = String.join(
                ", ",
                Collections.nCopies(transactions.size(), ROW_VALUES)
        );

        String sql = INSERT_PREFIX + values + DUPLICATE_SUFFIX;

        jdbcTemplate.update(sql, statement -> {
            int parameterIndex = 1;

            for (BankTransaction transaction : transactions) {
                statement.setObject(
                        parameterIndex++,
                        transaction.getLinkedAccountId(),
                        Types.BIGINT
                );

                setNullableString(
                        statement,
                        parameterIndex++,
                        transaction.getExternalTransactionId()
                );

                statement.setBigDecimal(
                        parameterIndex++,
                        transaction.getAmount()
                );

                setNullableString(
                        statement,
                        parameterIndex++,
                        transaction.getTransactionType() == null
                                ? null
                                : transaction.getTransactionType().name()
                );

                setNullableTimestamp(
                        statement,
                        parameterIndex++,
                        transaction.getTransactionAt()
                );

                setNullableString(
                        statement,
                        parameterIndex++,
                        transaction.getCounterpartyName()
                );

                setNullableString(
                        statement,
                        parameterIndex++,
                        transaction.getMemo()
                );

                setNullableTimestamp(
                        statement,
                        parameterIndex++,
                        transaction.getSyncedAt()
                );
            }
        });
    }

    private static void setNullableString(
            PreparedStatement statement,
            int parameterIndex,
            String value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, Types.VARCHAR);
        } else {
            statement.setString(parameterIndex, value);
        }
    }

    private static void setNullableTimestamp(
            PreparedStatement statement,
            int parameterIndex,
            LocalDateTime value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(parameterIndex, Types.TIMESTAMP);
        } else {
            statement.setTimestamp(
                    parameterIndex,
                    Timestamp.valueOf(value)
            );
        }
    }
}
