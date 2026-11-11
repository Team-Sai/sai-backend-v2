package org.teamsai.saibackend.domain.contract.dto.response;

import org.teamsai.saibackend.domain.contract.entity.EarlyRepayment;
import org.teamsai.saibackend.domain.contract.type.EarlyRepaymentMethod;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record EarlyRepaymentResponse(
        Long earlyRepaymentId,
        BigDecimal amount,
        BigDecimal shortfallAmount,
        BigDecimal accruedInterest,
        BigDecimal feeAmount,
        BigDecimal principalAmount,
        LocalDateTime processedAt,
        EarlyRepaymentMethod method
) {
    public static EarlyRepaymentResponse from(EarlyRepayment entity) {
        return new EarlyRepaymentResponse(
                entity.getEarlyRepaymentId(), entity.getAmount(), entity.getShortfallAmount(),
                entity.getAccruedInterest(), entity.getFeeAmount(), entity.getPrincipalAmount(),
                entity.getProcessedAt(), entity.getMethod());
    }
}