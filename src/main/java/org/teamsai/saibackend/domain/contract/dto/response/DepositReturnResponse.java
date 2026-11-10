package org.teamsai.saibackend.domain.contract.dto.response;

import org.teamsai.saibackend.domain.contract.entity.DepositReturn;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record DepositReturnResponse(
        Long bankTransactionId,
        BigDecimal amount,
        LocalDateTime transactionAt,
        LocalDateTime returnedAt
) {
    public static DepositReturnResponse from(DepositReturn entity) {
        return new DepositReturnResponse(
                entity.getBankTransactionId(), entity.getAmount(),
                entity.getTransactionAt(), entity.getReturnedAt());
    }
}