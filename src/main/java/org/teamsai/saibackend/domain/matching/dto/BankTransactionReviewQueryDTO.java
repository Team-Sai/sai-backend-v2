package org.teamsai.saibackend.domain.matching.dto;

import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankTransactionReviewQueryDTO(
        Long bankTransactionId,
        Long linkedAccountId,
        BigDecimal amount,
        BankTransactionType transactionType,
        BankTransactionProcessingStatus processingStatus,
        LocalDateTime transactionAt,
        String counterpartyName,
        String memo,
        LocalDateTime syncedAt
) {
}
