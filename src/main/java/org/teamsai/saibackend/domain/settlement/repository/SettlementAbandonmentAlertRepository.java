package org.teamsai.saibackend.domain.settlement.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlert;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAbandonmentAlertId;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;
import org.teamsai.saibackend.domain.settlement.type.AbandonmentDeliveryStatus;

public interface SettlementAbandonmentAlertRepository
        extends JpaRepository<SettlementAbandonmentAlert, SettlementAbandonmentAlertId> {

    boolean existsBySettlementIdAndReferenceDate(
            Long settlementId,
            LocalDate referenceDate
    );

    @Query("""
            select a from SettlementAbandonmentAlert a
            where a.deliveryStatus = :status
              and (:afterId is null or a.settlementId > :afterId
                   or (a.settlementId = :afterId and a.referenceDate > :afterDate))
            order by a.settlementId, a.referenceDate
            """)
    List<SettlementAbandonmentAlert> findPendingAfter(
            @Param("status") AbandonmentDeliveryStatus status,
            @Param("afterId") Long afterId, @Param("afterDate") LocalDate afterDate, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from SettlementAbandonmentAlert a where a.settlementId = :id and a.referenceDate = :date")
    Optional<SettlementAbandonmentAlert> findForDelivery(@Param("id") Long settlementId,
                                                       @Param("date") LocalDate referenceDate);
}
