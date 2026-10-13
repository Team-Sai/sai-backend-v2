package org.teamsai.saibackend.domain.integration.model;

import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SettlementDashboardContext(
        SettlementListResponse settlement,
        BigDecimal roleRemainingAmount,
        BigDecimal originalRoleAmount
) {
    public boolean isOwner() {
        return "OWNER".equals(settlement.role());
    }

    public static SettlementDashboardContext from(
            SettlementListResponse settlement,
            SettlementPaymentStatusResponse paymentStatus,
            Long userId
    ) {
        BigDecimal roleRemaining = "OWNER".equals(settlement.role())
                ? zeroIfNull(paymentStatus.getTotalRemainingAmount())
                : ownRemainingAmount(paymentStatus, userId);
        BigDecimal originalRoleAmount = "OWNER".equals(settlement.role())
                ? zeroIfNull(paymentStatus.getTotalExpectedAmount())
                : ownExpectedAmount(paymentStatus, userId);
        return new SettlementDashboardContext(settlement, roleRemaining, originalRoleAmount);
    }

    private static BigDecimal ownRemainingAmount(SettlementPaymentStatusResponse status, Long userId) {
        if (status.getObligations() == null) {
            return BigDecimal.ZERO;
        }
        return status.getObligations().stream()
                .filter(obligation -> userId.equals(obligation.getUserId()))
                .map(SettlementPaymentObligationResponse::getRemainingAmount)
                .map(SettlementDashboardContext::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal ownExpectedAmount(SettlementPaymentStatusResponse status, Long userId) {
        if (status.getObligations() == null) {
            return BigDecimal.ZERO;
        }
        return status.getObligations().stream()
                .filter(obligation -> userId.equals(obligation.getUserId()))
                .map(SettlementPaymentObligationResponse::getExpectedAmount)
                .map(SettlementDashboardContext::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    public boolean isClosed() {
        return "CLOSED".equals(settlement.settlementStatus());
    }

    public LocalDate effectiveDueDate() {
        return "RECURRING".equals(settlement.settlementType())
                ? settlement.cycleDate()
                : settlement.dueDate();
    }
}
