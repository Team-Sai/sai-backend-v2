package org.teamsai.saibackend.domain.settlement.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SettlementRepository extends JpaRepository<Settlement,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s
            FROM Settlement s
            WHERE s.settlementId = :settlementId
            """)
    Optional<Settlement> findByIdForUpdate(
            @Param("settlementId") Long settlementId
    );

    long countByRecurringSettlementRecurringSettlementId(
            Long recurringSettlementId
    );

    long countBySettlementStatus(
            SettlementStatus settlementStatus
    );

    List<Settlement> findBySettlementStatusOrderBySettlementIdAsc(
            SettlementStatus settlementStatus,
            Pageable pageable
    );

    @Query("""
        SELECT s
        FROM Settlement s
        WHERE s.recurringSettlement.recurringSettlementId = :recurringSettlementId
        ORDER BY s.cycleDate DESC
        """)
    List<Settlement> findLatestByRecurringId(
            @Param("recurringSettlementId") Long recurringSettlementId,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT s
        FROM Settlement s
        WHERE s.recurringSettlement.recurringSettlementId = :recurringSettlementId
        ORDER BY s.cycleDate DESC
        """)
    List<Settlement> findLatestByRecurringIdForUpdate(
            @Param("recurringSettlementId") Long recurringSettlementId,
            Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        UPDATE Settlement s
        SET s.settlementStatus = :closedStatus,
            s.closedAt = :closedAt
        WHERE s.settlementId = :settlementId
          AND s.settlementStatus = :currentStatus
        """)
    int closeSettlement(
            @Param("settlementId") Long settlementId,
            @Param("closedAt") LocalDateTime closedAt,
            @Param("currentStatus") SettlementStatus currentStatus,
            @Param("closedStatus") SettlementStatus closedStatus
    );
}
