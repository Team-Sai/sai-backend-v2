package org.teamsai.saibackend.domain.transaction.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record BankTransactionDTO(
        Long transactionId,
        String transactionKey,
        Long accountId,
        String transactionType,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String counterpartyName,
        String maskedCounterpartyAccountNumber,
        String memo,
        LocalDateTime transactionAt,
        Long transferId
) {}
