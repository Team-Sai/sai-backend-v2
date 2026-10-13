package org.teamsai.saibackend.domain.settlement.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.util.List;

public interface SettlementParticipantRepository extends JpaRepository<SettlementParticipant,Long> {

    @Query("""
        SELECT CASE WHEN COUNT(sp) > 0 THEN true ELSE false END
        FROM SettlementParticipant sp
        WHERE sp.settlement.settlementId = :settlementId
          AND sp.user.userId = :userId
          AND sp.participantStatus = :status
        """)
    boolean existsActiveParticipant(
            @Param("settlementId") Long settlementId,
            @Param("userId") Long userId,
            @Param("status") SettlementParticipantStatus status
    );

    @Query("""
        SELECT sp
        FROM SettlementParticipant sp
        JOIN FETCH sp.user
        WHERE sp.settlement.settlementId = :settlementId
          AND sp.participantStatus = :status
    """)
    List<SettlementParticipant> findBySettlementIdAndStatus(
            @Param("settlementId") Long settlementId,
            @Param("status") SettlementParticipantStatus status
    );
}
