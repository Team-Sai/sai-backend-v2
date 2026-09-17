package org.teamsai.saibackend.domain.settlement.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAccount;
import org.teamsai.saibackend.domain.settlement.type.SettlementAccountStatus;

import java.util.Optional;

public interface SettlementAccountRepository extends JpaRepository<SettlementAccount, Long> {

    @Query("""
        SELECT sa
        FROM SettlementAccount sa
        WHERE sa.settlement.settlementId = :settlementId
          AND sa.accountStatus = :status
        """)
    Optional<SettlementAccount> findBySettlementIdAndStatus(
            @Param("settlementId") Long settlementId,
            @Param("status") SettlementAccountStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT sa
        FROM SettlementAccount sa
        WHERE sa.settlement.settlementId = :settlementId
          AND sa.accountStatus = :status
        """)
    Optional<SettlementAccount> findBySettlementIdAndStatusForUpdate(
            @Param("settlementId") Long settlementId,
            @Param("status") SettlementAccountStatus status
    );
}