package org.teamsai.saibackend.domain.settlement.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;

import java.time.LocalDate;
import java.util.List;


public interface RecurringSettlementRepository extends JpaRepository<RecurringSettlement, Long> {
    @Query("""
            SELECT r 
            FROM RecurringSettlement r
            WHERE r.startDate <= :baseDate
                AND (r.endDate IS NULL OR r.endDate >= :baseDate)
            """)
    List<RecurringSettlement> findActiveInRange(
            @Param("baseDate") LocalDate baseDate
    );
}
