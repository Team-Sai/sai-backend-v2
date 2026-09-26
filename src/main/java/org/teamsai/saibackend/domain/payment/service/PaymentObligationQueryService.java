package org.teamsai.saibackend.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PaymentObligationQueryService {

    private final PaymentObligationRepository paymentObligationRepository;

    public List<PaymentObligationEntity> findByParticipantIds(List<Long> participantIds) {
        if (participantIds == null || participantIds.isEmpty()) {
            return List.of();
        }
        return paymentObligationRepository.findByParticipantIdIn(participantIds);
    }

    public List<PaymentObligationEntity> findLatestActiveByParticipantIds(List<Long> participantIds) {
        if (participantIds == null || participantIds.isEmpty()) {
            return List.of();
        }
        return paymentObligationRepository.findLatestByParticipantIds(
                participantIds,
                ObligationStatus.ACTIVE
        );
    }

    public Map<Long, BigDecimal> findLatestExpectedAmountsByParticipantIdsAndStatuses(
            List<Long> participantIds,
            List<ObligationStatus> statuses
    ) {
        if (participantIds == null || participantIds.isEmpty()
                || statuses == null || statuses.isEmpty()) {
            return Map.of();
        }
        return paymentObligationRepository
                .findLatestByParticipantIdsAndObligationStatuses(participantIds, statuses)
                .stream()
                .collect(Collectors.toMap(
                        PaymentObligationEntity::getParticipantId,
                        PaymentObligationEntity::getExpectedAmount
                ));
    }

    public List<Long> findSettlementIdsByObligationIds(List<Long> obligationIds) {
        if (obligationIds == null || obligationIds.isEmpty()) {
            return List.of();
        }
        return paymentObligationRepository.findSettlementIdsByObligationIds(obligationIds);
    }

    public List<Long> findWriteOffCandidateIds(
            ObligationStatus obligationStatus,
            List<PaymentStatus> paymentStatuses,
            LocalDateTime cutoffDateTime
    ) {
        return paymentObligationRepository.findWriteOffCandidateIds(
                obligationStatus, paymentStatuses, cutoffDateTime
        );
    }

}
