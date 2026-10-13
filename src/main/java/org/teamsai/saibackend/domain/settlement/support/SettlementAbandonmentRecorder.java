package org.teamsai.saibackend.domain.settlement.support;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlert;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAbandonmentAlertRepository;

import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.teamsai.saibackend.domain.settlement.type.AbandonmentDeliveryStatus;

@Repository
@RequiredArgsConstructor
public class SettlementAbandonmentRecorder {
    private final SettlementAbandonmentAlertRepository alertRepository;
    private final EntityManager entityManager;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recordIfAbsent(Long settlementId, LocalDate referenceDate, String message) {
        if (alertRepository.existsBySettlementIdAndReferenceDate(settlementId, referenceDate)) {
            return false;
        }
        // Assigned composite IDs must be inserted, not merged: a concurrent insert must fail.
        entityManager.persist(SettlementAbandonmentAlert.pending(settlementId, referenceDate, message));
        entityManager.flush();
        return true;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean exists(Long settlementId, LocalDate referenceDate) {
        return alertRepository.existsBySettlementIdAndReferenceDate(settlementId, referenceDate);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public List<SettlementAbandonmentAlert> pendingAfter(Long afterId, LocalDate afterDate) {
        // See records committed after the outer Tasklet's first read.
        return alertRepository.findPendingAfter(AbandonmentDeliveryStatus.PENDING,
                afterId, afterDate, PageRequest.of(0, 100));
    }
}
