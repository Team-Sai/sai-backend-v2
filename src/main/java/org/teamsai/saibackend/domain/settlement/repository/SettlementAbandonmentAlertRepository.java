package org.teamsai.saibackend.domain.settlement.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlert;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlertId;

import java.time.LocalDate;

public interface SettlementAbandonmentAlertRepository
        extends JpaRepository<SettlementAbandonmentAlert, SettlementAbandonmentAlertId> {

    boolean existsBySettlementIdAndReferenceDate(
            Long settlementId,
            LocalDate referenceDate
    );
}