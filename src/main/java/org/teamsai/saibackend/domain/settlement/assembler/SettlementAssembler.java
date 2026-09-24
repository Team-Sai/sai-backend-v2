package org.teamsai.saibackend.domain.settlement.assembler;

import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.settlement.dto.response.CreateRecurringSettlementResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementObligationStatusResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentHistoryResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class SettlementAssembler {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int RATE_SCALE = 2;

    private SettlementAssembler() {
    }

    public static CreateRecurringSettlementResponse toCreateRecurringSettlementResponse(
            RecurringSettlement recurringSettlement,
            Settlement firstSettlement
    ) {
        return CreateRecurringSettlementResponse.builder()
                .recurringSettlementId(recurringSettlement.getRecurringSettlementId())
                .firstSettlementId(firstSettlement.getSettlementId())
                .settlementType(firstSettlement.getSettlementType())
                .title(firstSettlement.getTitle())
                .cycleRule(recurringSettlement.getCycleRule())
                .startDate(recurringSettlement.getStartDate())
                .endDate(recurringSettlement.getEndDate())
                .createdAt(recurringSettlement.getCreatedAt())
                .build();
    }

    public static SettlementPaymentObligationResponse toObligationResponse(
            PaymentObligationEntity obligation,
            SettlementParticipant participant,
            List<PaymentRecordEntity> records
    ) {
        BigDecimal paidAmount = records.stream()
                .map(PaymentRecordEntity::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDateTime latestPaymentAt = records.stream()
                .map(PaymentRecordEntity::getRecordedAt)
                .max(LocalDateTime::compareTo)
                .orElse(null);

        BigDecimal remainingAmount = obligation.getExpectedAmount()
                .subtract(paidAmount)
                .max(BigDecimal.ZERO);

        PaymentStatus paymentStatus = calculatePaymentStatus(obligation.getExpectedAmount(), paidAmount);

        return SettlementPaymentObligationResponse.builder()
                .paymentObligationId(obligation.getPaymentObligationId())
                .participantId(obligation.getParticipantId())
                .userId(participant.getUser().getUserId())
                .participantName(participant.getUser().getName())
                .expectedAmount(obligation.getExpectedAmount())
                .paidAmount(paidAmount)
                .remainingAmount(remainingAmount)
                .latestPaymentAt(latestPaymentAt)
                .paymentStatus(paymentStatus)
                .obligationStatus(obligation.getObligationStatus())
                .overdueSince(obligation.getOverdueSince())
                .build();
    }

    public static SettlementPaymentStatusResponse toPaymentStatusResponse(
            Settlement settlement,
            List<SettlementPaymentObligationResponse> obligations
    ) {
        BigDecimal totalExpectedAmount = obligations.stream()
                .map(SettlementPaymentObligationResponse::getExpectedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPaidAmount = obligations.stream()
                .map(SettlementPaymentObligationResponse::getPaidAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRemainingAmount = obligations.stream()
                .map(SettlementPaymentObligationResponse::getRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalResolvedAmount = obligations.stream()
                .map(o -> o.getObligationStatus() == ObligationStatus.WRITTEN_OFF
                        ? o.getExpectedAmount()  // 상각분은 기대액 전체를 "해결됨"으로 카운트
                        : o.getPaidAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal progressRate = calculateProgressRate(totalExpectedAmount, totalResolvedAmount);
        if (progressRate.compareTo(HUNDRED) > 0) {
            progressRate = HUNDRED;
        }

        Map<PaymentStatus, Long> countByStatus = obligations.stream()
                .collect(Collectors.groupingBy(
                        SettlementPaymentObligationResponse::getPaymentStatus,
                        Collectors.counting()
                ));

        return SettlementPaymentStatusResponse.builder()
                .settlementId(settlement.getSettlementId())
                .obligations(obligations)
                .totalExpectedAmount(totalExpectedAmount)
                .totalPaidAmount(totalPaidAmount)
                .totalRemainingAmount(totalRemainingAmount)
                .paidCount(countByStatus.getOrDefault(PaymentStatus.PAID, 0L))
                .partiallyPaidCount(countByStatus.getOrDefault(PaymentStatus.PARTIALLY_PAID, 0L))
                .unpaidCount(countByStatus.getOrDefault(PaymentStatus.UNPAID, 0L))
                .progressRate(progressRate)
                .closable(isFullyResolved(obligations))
                .build();
    }

    public static boolean isFullyResolved(List<SettlementPaymentObligationResponse> obligations) {
        return !obligations.isEmpty()
                && obligations.stream().allMatch(o ->
                o.getObligationStatus() == ObligationStatus.WRITTEN_OFF
                        || o.getPaidAmount().compareTo(o.getExpectedAmount()) == 0
        );
    }

    public static SettlementObligationStatusResponse toObligationStatusResponse(
            PaymentObligationEntity obligation,
            BigDecimal paidAmount
    ) {
        return new SettlementObligationStatusResponse(
                obligation.getPaymentObligationId(),
                obligation.getObligationStatus(),
                obligation.getExpectedAmount(),
                paidAmount
        );
    }

    public static SettlementPaymentHistoryResponse toPaymentHistoryResponse(
            PaymentRecordEntity record,
            BankTransactionEntity transaction,
            String payerName
    ) {
        return SettlementPaymentHistoryResponse.builder()
                .paymentRecordId(record.getPaymentRecordId())
                .recordedAt(record.getRecordedAt())
                .payerName(payerName)
                .amount(record.getAmount())
                .sourceType(record.getSourceType())
                .bankTransactionId(record.getBankTransactionId())
                .counterpartyName(transaction != null ? transaction.getCounterpartyName() : null)
                .externalTransactionId(transaction != null ? transaction.getExternalTransactionId() : null)
                .build();
    }

    private static PaymentStatus calculatePaymentStatus(BigDecimal expectedAmount, BigDecimal paidAmount) {
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) {
            return PaymentStatus.UNPAID;
        }
        if (paidAmount.compareTo(expectedAmount) < 0) {
            return PaymentStatus.PARTIALLY_PAID;
        }
        return PaymentStatus.PAID;
    }

    private static BigDecimal calculateProgressRate(BigDecimal totalExpectedAmount, BigDecimal totalPaidAmount) {
        if (totalExpectedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return totalPaidAmount
                .multiply(HUNDRED)
                .divide(totalExpectedAmount, RATE_SCALE, RoundingMode.HALF_UP);
    }
}
