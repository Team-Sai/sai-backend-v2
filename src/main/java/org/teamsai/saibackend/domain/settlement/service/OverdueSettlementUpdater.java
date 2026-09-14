package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.settlement.dto.SettlementDTO;
import org.teamsai.saibackend.domain.settlement.dto.SettlementParticipantDTO;
import org.teamsai.saibackend.domain.settlement.mapper.SettlementParticipantMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OverdueSettlementUpdater {

    private final SettlementParticipantMapper participantMapper;
    private final PaymentObligationRepository paymentObligationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOverdueForSettlement(SettlementDTO settlement, LocalDate referenceDate) {
        List<Long> activeParticipantIds = participantMapper.
                findActiveBySettlementId(settlement.getSettlementId())
                .stream()
                .map(SettlementParticipantDTO::getParticipantId)
                .toList();

        if (activeParticipantIds.isEmpty()) {
            return;
        }

        List<PaymentObligationEntity> unpaidObligations =
                paymentObligationRepository.findUnpaidByParticipantIds(
                        activeParticipantIds,
                        List.of(
                                PaymentStatus.UNPAID,
                                PaymentStatus.PARTIALLY_PAID
                        ),
                        ObligationStatus.ACTIVE);

        if (unpaidObligations.isEmpty()) {
            return;
        }

        LocalDateTime overdueSince = referenceDate.plusDays(1).atStartOfDay();
        unpaidObligations.forEach(obligation ->
                obligation.markOverdue(overdueSince)
        );
    }
}
