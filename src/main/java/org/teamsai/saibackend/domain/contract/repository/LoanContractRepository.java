package org.teamsai.saibackend.domain.contract.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface LoanContractRepository extends JpaRepository<LoanContract, Long> {

    List<LoanContract> findByCreditorOrDebtorOrderByCreatedAtDesc(User creditor, User debtor);

    Optional<LoanContract> findByPreviousContractAndStatus(LoanContract previousContract, ContractStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LoanContract c WHERE c.contractId = :contractId")
    Optional<LoanContract> findWithLockByContractId(@Param("contractId") Long contractId);
}
