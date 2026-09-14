package org.teamsai.saibackend.domain.transaction.dto.response;

import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankTransactionListItemResponse(
        Long bankTransactionId,
        BigDecimal amount,
        BankTransactionType transactionType,
        BankTransactionProcessingStatus processingStatus,
        LocalDateTime transactionAt,
        String counterpartyName,
        String memo
) {
    public static BankTransactionListItemResponse from(
            BankTransactionEntity entity
    ) {
        return new BankTransactionListItemResponse(
                entity.getBankTransactionId(),
                entity.getAmount(),
                entity.getTransactionType(),
                entity.getProcessingStatus(),
                entity.getTransactionAt(),
                entity.getCounterpartyName(),
                entity.getMemo()
        );
    }
}