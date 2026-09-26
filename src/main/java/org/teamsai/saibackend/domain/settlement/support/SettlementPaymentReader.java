package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SettlementPaymentReader {

    private final SettlementParticipantRepository settlementParticipantRepository;
    private final PaymentObligationQueryService paymentObligationQueryService;
    private final PaymentRecordService paymentRecordService;

    @Transactional(readOnly = true)
    public SettlementPaymentData read(Long settlementId) {
        List<SettlementParticipant> participants =
                settlementParticipantRepository.findBySettlementIdAndStatus(
                        settlementId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (participants.isEmpty()) {
            return SettlementPaymentData.empty();
        }

        List<Long> participantIds = participants.stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        List<PaymentObligationEntity> obligations =
                paymentObligationQueryService.findByParticipantIds(
                        participantIds
                );

        if (obligations.isEmpty()) {
            return new SettlementPaymentData(
                    participants,
                    List.of(),
                    List.of(),
                    Map.of()
            );
        }

        List<Long> obligationIds = obligations.stream()
                .map(PaymentObligationEntity::getPaymentObligationId)
                .toList();

        List<PaymentRecordEntity> paymentRecords =
                paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.SETTLEMENT,
                        obligationIds
                );

        Map<Long, BigDecimal> paidAmountMap =
                paymentRecords.stream()
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

        return new SettlementPaymentData(
                participants,
                obligations,
                paymentRecords,
                paidAmountMap
        );
    }
}
