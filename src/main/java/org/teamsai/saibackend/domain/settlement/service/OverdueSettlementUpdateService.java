package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class OverdueSettlementUpdateService {

    private final SettlementParticipantRepository participantRepository;
    private final SettlementPaymentService settlementPaymentService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateOverdueForSettlement(Settlement settlement, LocalDate referenceDate) {
        List<Long> activeParticipantIds = participantRepository.
                findBySettlementIdAndStatus(
                        settlement.getSettlementId(),
                        SettlementParticipantStatus.ACTIVE
                )
                .stream()
                .map(SettlementParticipant::getParticipantId)
                .toList();

        if (activeParticipantIds.isEmpty()) {
            return;
        }

        LocalDateTime overdueSince = referenceDate.plusDays(1).atStartOfDay();
        settlementPaymentService.markOverdueByParticipantIds(activeParticipantIds, overdueSince);
    }
}
