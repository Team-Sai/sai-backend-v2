package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementPaymentStatusChecker {

    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationRepository paymentObligationRepository;
    private final PaymentRecordRepository paymentRecordRepository;

    @Transactional(readOnly = true)
    public boolean areAllObligationsResolved(Long settlementId) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        settlementId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return false;
        }

        List<Long> participantIds = participants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        List<PaymentObligationEntity> obligations =
                paymentObligationRepository.findByParticipantIdIn(participantIds);

        if (obligations.isEmpty()) {
            return false;
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

        return obligations.stream().allMatch(obligation -> {
            BigDecimal paidAmount = paidAmountMap.getOrDefault(
                    obligation.getPaymentObligationId(),
                    BigDecimal.ZERO
            );

            return isResolved(obligation.getObligationStatus())
                    || paidAmount.compareTo(obligation.getExpectedAmount()) >= 0;
        });
    }

    private boolean isResolved(ObligationStatus status) {
        return status == ObligationStatus.WRITTEN_OFF
                || status == ObligationStatus.EXCLUDED
                || status == ObligationStatus.CANCELLED;
    }
}