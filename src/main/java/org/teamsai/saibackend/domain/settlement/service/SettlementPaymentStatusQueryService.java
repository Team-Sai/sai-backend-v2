package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementObligationStatusResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPaymentStatusQueryService {

    private static final BigDecimal HUNDRED =
            BigDecimal.valueOf(100);
    private static final int RATE_SCALE = 2;
    private final SettlementRepository settlementRepository;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationRepository paymentObligationRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final SettlementValidator settlementValidator;

    @Transactional(readOnly = true)
    public SettlementPaymentStatusResponse getPaymentStatus(
            Long settlementId,
            Long userId
    ) {
        Settlement settlement =
                settlementRepository.findById(settlementId)
                        .orElseThrow(
                                SettlementErrorCode
                                        .SETTLEMENT_NOT_FOUND
                                        ::toException
                        );
        settlementValidator.validateAccessibleUser(settlement,userId);
        List<SettlementPaymentObligationResponse> obligations =
                buildPaymentObligationResponses(
                        settlement.getSettlementId()
                );
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

        BigDecimal progressRate =
                calculateProgressRate(
                        totalExpectedAmount,
                        totalResolvedAmount
                );
        if(progressRate.compareTo(HUNDRED) > 0){
            progressRate = HUNDRED;
        }
        boolean closable = isFullyResolved(obligations);

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
                .closable(closable)
                .build();
    }

    private BigDecimal calculateProgressRate(
            BigDecimal totalExpectedAmount,
            BigDecimal totalPaidAmount
    ) {
        if (totalExpectedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        return totalPaidAmount
                .multiply(HUNDRED)
                .divide(
                        totalExpectedAmount,
                        RATE_SCALE,
                        RoundingMode.HALF_UP
                );
    }

    @Transactional(readOnly = true)
    public boolean areAllObligationsResolved(Long settlementId) {
        List<SettlementObligationStatusResponse> obligations =
                buildObligationStatusResponses(settlementId);
        return !obligations.isEmpty()
                && obligations.stream().allMatch(o ->
                isResolved(o.obligationStatus())
                        || o.paidAmount().compareTo(o.expectedAmount()) >= 0
        );
    }

    private List<SettlementPaymentObligationResponse> buildPaymentObligationResponses(
            Long settlementId
    ) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        settlementId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return List.of();
        }

        List<Long> participantIds = participants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        List<PaymentObligationEntity> obligations =
                paymentObligationRepository.findByParticipantIdsAndObligationStatuses(
                        participantIds,
                        List.of(
                                ObligationStatus.ACTIVE,
                                ObligationStatus.WRITTEN_OFF
                        )
                );

        if (obligations.isEmpty()) {
            return List.of();
        }

        List<Long> obligationIds = obligations.stream()
                .map(PaymentObligationEntity::getPaymentObligationId)
                .toList();

        List<PaymentRecordEntity> paymentRecords =
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        obligationIds,
                        RecordStatus.CONFIRMED
                );

        Map<Long, SettlementParticipant> participantMap = participants.stream()
                .collect(Collectors.toMap(
                        SettlementParticipant::getParticipantId,
                        participant -> participant
                ));

        Map<Long, List<PaymentRecordEntity>> paymentRecordMap = paymentRecords.stream()
                .collect(Collectors.groupingBy(
                        PaymentRecordEntity::getTargetId
                ));

        return obligations.stream()
                .map(obligation -> {
                    SettlementParticipant participant =
                            participantMap.get(obligation.getParticipantId());

                    List<PaymentRecordEntity> records =
                            paymentRecordMap.getOrDefault(
                                    obligation.getPaymentObligationId(),
                                    List.of()
                            );

                    BigDecimal paidAmount = records.stream()
                            .map(PaymentRecordEntity::getAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);

                    LocalDateTime latestPaymentAt = records.stream()
                            .map(PaymentRecordEntity::getRecordedAt)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);

                    BigDecimal remainingAmount =
                            obligation.getExpectedAmount()
                                    .subtract(paidAmount)
                                    .max(BigDecimal.ZERO);

                    PaymentStatus paymentStatus =
                            calculatePaymentStatus(
                                    obligation.getExpectedAmount(),
                                    paidAmount
                            );

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
                })
                .toList();
    }

    private List<SettlementObligationStatusResponse> buildObligationStatusResponses(
            Long settlementId
    ) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        settlementId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return List.of();
        }

        List<Long> participantIds = participants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        List<PaymentObligationEntity> obligations =
                paymentObligationRepository.findByParticipantIdIn(
                        participantIds
                );

        if (obligations.isEmpty()) {
            return List.of();
        }

        List<Long> obligationIds = obligations.stream()
                .map(PaymentObligationEntity::getPaymentObligationId)
                .toList();

        List<PaymentRecordEntity> paymentRecords =
                paymentRecordRepository.findConfirmedByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        obligationIds,
                        RecordStatus.CONFIRMED
                );

        Map<Long, BigDecimal> paidAmountMap = paymentRecords.stream()
                .collect(Collectors.groupingBy(
                        PaymentRecordEntity::getTargetId,
                        Collectors.mapping(
                                PaymentRecordEntity::getAmount,
                                Collectors.reducing(
                                        BigDecimal.ZERO,
                                        BigDecimal::add
                                )
                        )
                ));

        return obligations.stream()
                .map(obligation ->
                        new SettlementObligationStatusResponse(
                                obligation.getPaymentObligationId(),
                                obligation.getObligationStatus(),
                                obligation.getExpectedAmount(),
                                paidAmountMap.getOrDefault(
                                        obligation.getPaymentObligationId(),
                                        BigDecimal.ZERO
                                )
                        )
                )
                .toList();
    }

    private PaymentStatus calculatePaymentStatus(
            BigDecimal expectedAmount,
            BigDecimal paidAmount
    ) {
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) {
            return PaymentStatus.UNPAID;
        }

        if (paidAmount.compareTo(expectedAmount) < 0) {
            return PaymentStatus.PARTIALLY_PAID;
        }

        return PaymentStatus.PAID;
    }

    private boolean isResolved(ObligationStatus status) {
        return status == ObligationStatus.WRITTEN_OFF
                || status == ObligationStatus.EXCLUDED
                || status == ObligationStatus.CANCELLED;
    }

    private boolean isFullyResolved(List<SettlementPaymentObligationResponse> obligations) {
        return !obligations.isEmpty()
                && obligations.stream().allMatch(o ->
                o.getObligationStatus() == ObligationStatus.WRITTEN_OFF
                        || o.getPaidAmount().compareTo(o.getExpectedAmount()) == 0
        );
    }
}
