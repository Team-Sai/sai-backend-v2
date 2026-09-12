package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.type.ContractRelationType;

public interface LoanContractRepository extends JpaRepository<LoanContract, Long> {

    // 1. 실제 실행될 JPQL (파라미터 바인딩으로 풀 패키지 경로 제거)
    @Query("""
        SELECT COALESCE(SUM(c.principalAmount), 0)
        FROM LoanContract c
        WHERE c.creditorId = :userId
          AND c.status = :status
          AND c.relationType = :relationType
        """)
    Long sumPrincipalByCreditorAndStatusAndRelationType(
            @Param("userId") Long userId,
            @Param("status") ContractStatus status,
            @Param("relationType") ContractRelationType relationType
    );
}
