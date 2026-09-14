package org.teamsai.saibackend.domain.transaction.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query(
            value = """
                    SELECT bt.*
                    FROM bank_transaction bt
                    WHERE bt.linked_account_id = :linkedAccountId
                      AND (
                            (
                                bt.processing_status = 'FAILED'
                                AND bt.retry_count < 5
                            )
                            OR (
                                bt.processing_status = 'UNMATCHED'
                                AND bt.retry_count < 14
                            )
                            OR (
                                bt.processing_status = 'NEEDS_CHECK'
                                AND bt.retry_count < 14
                                AND NOT EXISTS (
                                    SELECT 1
                                    FROM bank_transaction_match_candidate c1
                                    JOIN bank_transaction_match_candidate c2
                                      ON c1.bank_transaction_id =
                                         c2.bank_transaction_id
                                    WHERE c1.bank_transaction_id =
                                          bt.bank_transaction_id
                                      AND c1.target_type = 'SETTLEMENT'
                                      AND c2.target_type = 'LOAN'
                                )
                            )
                      )
                    ORDER BY bt.transaction_at ASC,
                             bt.bank_transaction_id ASC
                    """,
            nativeQuery = true
    )
    List<BankTransactionEntity> findRetryCandidates(
            @Param("linkedAccountId") Long linkedAccountId
    );

    @Query(
            value = """
                SELECT bt
                FROM BankTransactionEntity bt
                WHERE bt.linkedAccountId = :linkedAccountId
                  AND (
                      :processingStatus IS NULL
                      OR bt.processingStatus = :processingStatus
                  )
                  AND (
                      :transactionType IS NULL
                      OR bt.transactionType = :transactionType
                  )
                  AND (
                      :keyword IS NULL
                      OR bt.counterpartyName LIKE CONCAT('%', :keyword, '%')
                      OR bt.memo LIKE CONCAT('%', :keyword, '%')
                  )
                  AND (
                      :fromDateTime IS NULL
                      OR bt.transactionAt >= :fromDateTime
                  )
                  AND (
                      :toDateTimeExclusive IS NULL
                      OR bt.transactionAt < :toDateTimeExclusive
                  )
                ORDER BY bt.transactionAt DESC,
                         bt.bankTransactionId DESC
                """,
            countQuery = """
                SELECT COUNT(bt)
                FROM BankTransactionEntity bt
                WHERE bt.linkedAccountId = :linkedAccountId
                  AND (
                      :processingStatus IS NULL
                      OR bt.processingStatus = :processingStatus
                  )
                  AND (
                      :transactionType IS NULL
                      OR bt.transactionType = :transactionType
                  )
                  AND (
                      :keyword IS NULL
                      OR bt.counterpartyName LIKE CONCAT('%', :keyword, '%')
                      OR bt.memo LIKE CONCAT('%', :keyword, '%')
                  )
                  AND (
                      :fromDateTime IS NULL
                      OR bt.transactionAt >= :fromDateTime
                  )
                  AND (
                      :toDateTimeExclusive IS NULL
                      OR bt.transactionAt < :toDateTimeExclusive
                  )
                """
    )
    Page<BankTransactionEntity> search(
            @Param("linkedAccountId")
            Long linkedAccountId,

            @Param("processingStatus")
            BankTransactionProcessingStatus processingStatus,

            @Param("transactionType")
            BankTransactionType transactionType,

            @Param("keyword")
            String keyword,

            @Param("fromDateTime")
            LocalDateTime fromDateTime,

            @Param("toDateTimeExclusive")
            LocalDateTime toDateTimeExclusive,

            Pageable pageable
    );
}
