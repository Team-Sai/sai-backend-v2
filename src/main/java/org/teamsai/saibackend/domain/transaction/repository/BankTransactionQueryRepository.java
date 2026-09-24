package org.teamsai.saibackend.domain.transaction.repository;

import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.transaction.dto.response.IntegratedBankTransactionResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.time.LocalDateTime;
import java.util.List;

public interface BankTransactionQueryRepository
        extends Repository<BankTransactionEntity, Long> {

    @Query(value = """
            SELECT new org.teamsai.saibackend.domain.transaction.dto.response.IntegratedBankTransactionResponse(
                bt, account.bankCode, account.accountNumber)

            FROM BankTransactionEntity bt
            JOIN LinkedBankAccount account ON account.linkedAccountId = bt.linkedAccountId
            WHERE account.userId = :userId AND account.connectionStatus = :connectionStatus
              AND (:linkedAccountId IS NULL OR bt.linkedAccountId = :linkedAccountId)
              AND (:processingStatus IS NULL OR bt.processingStatus = :processingStatus)
              AND (:transactionType IS NULL OR bt.transactionType = :transactionType)
              AND (:keyword IS NULL OR bt.counterpartyName LIKE CONCAT('%', :keyword, '%')
                   OR bt.memo LIKE CONCAT('%', :keyword, '%'))
              AND (:fromDateTime IS NULL OR bt.transactionAt >= :fromDateTime)
              AND (:toDateTimeExclusive IS NULL OR bt.transactionAt < :toDateTimeExclusive)
            ORDER BY bt.transactionAt DESC, bt.bankTransactionId DESC
            """, countQuery = """
            SELECT COUNT(bt)

            FROM BankTransactionEntity bt
            JOIN LinkedBankAccount account ON account.linkedAccountId = bt.linkedAccountId
            WHERE account.userId = :userId AND account.connectionStatus = :connectionStatus
              AND (:linkedAccountId IS NULL OR bt.linkedAccountId = :linkedAccountId)
              AND (:processingStatus IS NULL OR bt.processingStatus = :processingStatus)
              AND (:transactionType IS NULL OR bt.transactionType = :transactionType)
              AND (:keyword IS NULL OR bt.counterpartyName LIKE CONCAT('%', :keyword, '%')
                   OR bt.memo LIKE CONCAT('%', :keyword, '%'))
              AND (:fromDateTime IS NULL OR bt.transactionAt >= :fromDateTime)
              AND (:toDateTimeExclusive IS NULL OR bt.transactionAt < :toDateTimeExclusive)
            """)
    Page<IntegratedBankTransactionResponse> searchIntegrated(
            @Param("userId") Long userId,
            @Param("connectionStatus") ConnectionStatus connectionStatus,
            @Param("linkedAccountId") Long linkedAccountId,
            @Param("processingStatus") BankTransactionProcessingStatus processingStatus,
            @Param("transactionType") BankTransactionType transactionType,
            @Param("keyword") String keyword,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toDateTimeExclusive") LocalDateTime toDateTimeExclusive,
            Pageable pageable);
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