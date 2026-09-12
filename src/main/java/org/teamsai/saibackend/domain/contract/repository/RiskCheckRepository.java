package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;

public interface RiskCheckRepository extends JpaRepository<LoanContract, Long> {

    @Query("""
            SELECT COALESCE(SUM(c.principalAmount), 0)
            FROM LoanContract c
            WHERE c.creditorId = :userId
              AND c.status = org.teamsai.saibackend.domain.contract.dto.request.ContractStatus.COMPLETED
              AND c.relationType = org.teamsai.saibackend.domain.contract.type.ContractRelationType.FAMILY
            """)
    Long sumCompletedPrincipalByCreditor(@Param("userId") Long userId);
}
