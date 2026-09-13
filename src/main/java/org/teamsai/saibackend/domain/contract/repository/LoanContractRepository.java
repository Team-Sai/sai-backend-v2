package org.teamsai.saibackend.domain.contract.repository;

<<<<<<< HEAD
import org.springframework.data.jpa.repository.JpaRepository;
=======
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
>>>>>>> 24e20ed550b5797f545b49e0fb6feb7b678449b1
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
<<<<<<< HEAD
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
=======
import org.teamsai.saibackend.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface LoanContractRepository extends JpaRepository<LoanContract, Long> {

    List<LoanContract> findByCreditorOrDebtorOrderByCreatedAtDesc(User creditor, User debtor);

    Optional<LoanContract> findByPreviousContractAndStatus(LoanContract previousContract, ContractStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LoanContract c WHERE c.contractId = :contractId")
    Optional<LoanContract> findWithLockByContractId(@Param("contractId") Long contractId);
>>>>>>> 24e20ed550b5797f545b49e0fb6feb7b678449b1
}
