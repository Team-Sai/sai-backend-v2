package org.teamsai.saibackend.domain.transaction.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BankTransactionRepository
        extends JpaRepository<BankTransactionEntity, Long> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO bank_transaction (
                linked_account_id, external_transaction_id, amount,
                transaction_type, processing_status, transaction_at,
                counterparty_name, memo, synced_at, retry_count
            ) VALUES (
                :linkedAccountId,
                :externalTransactionId,
                :amount,
                :transactionType,
                'PENDING',
                :transactionAt,
                :counterpartyName,
                :memo,
                :syncedAt,
                0
            )
            ON DUPLICATE KEY UPDATE
                bank_transaction_id = LAST_INSERT_ID(bank_transaction_id)
            """, nativeQuery = true)
    void insertIfAbsent(
            @Param("linkedAccountId") Long linkedAccountId,
            @Param("externalTransactionId") String externalTransactionId,
            @Param("amount") BigDecimal amount,
            @Param("transactionType") String transactionType,
            @Param("transactionAt") LocalDateTime transactionAt,
            @Param("counterpartyName") String counterpartyName,
            @Param("memo") String memo,
            @Param("syncedAt") LocalDateTime syncedAt
    );

    @Query(value = """
            SELECT bank_transaction_id
            FROM bank_transaction
            WHERE linked_account_id = :linkedAccountId
              AND external_transaction_id = :externalTransactionId
            FOR UPDATE
            """, nativeQuery = true)
    Optional<Long> findIdByExternalKeyForUpdate(
            @Param("linkedAccountId") Long linkedAccountId,
            @Param("externalTransactionId") String externalTransactionId
    );

    Optional<BankTransactionEntity>
    findByBankTransactionIdAndLinkedAccountId(
            Long bankTransactionId,
            Long linkedAccountId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BankTransactionEntity> findLockedByBankTransactionId(
            Long bankTransactionId
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<BankTransactionEntity>
    findLockedByBankTransactionIdAndLinkedAccountId(
            Long bankTransactionId,
            Long linkedAccountId
    );

    @Query("""
            SELECT bt
            FROM BankTransactionEntity bt
            WHERE bt.processingStatus = :processingStatus
              AND bt.transactionType = :transactionType
            ORDER BY bt.transactionAt ASC,
                     bt.bankTransactionId ASC
            """)
    List<BankTransactionEntity> findPendingDeposits(
            @Param("processingStatus")
            BankTransactionProcessingStatus processingStatus,

            @Param("transactionType")
            BankTransactionType transactionType
    );

    @Query("""
            SELECT bt
            FROM BankTransactionEntity bt
            WHERE bt.linkedAccountId = :linkedAccountId
              AND bt.processingStatus = :processingStatus
              AND bt.transactionType = :transactionType
            ORDER BY bt.transactionAt ASC,
                     bt.bankTransactionId ASC
            """)
    List<BankTransactionEntity> findPendingDepositsByLinkedAccountId(
            @Param("linkedAccountId")
            Long linkedAccountId,

            @Param("processingStatus")
            BankTransactionProcessingStatus processingStatus,

            @Param("transactionType")
            BankTransactionType transactionType
    );


}
