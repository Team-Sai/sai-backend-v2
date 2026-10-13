package org.teamsai.saibackend.domain.contract.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequest;

import java.util.List;
import java.util.Optional;

public interface ContractChangeRepository
        extends JpaRepository<LoanContractChangeRequest, Long> {

    List<LoanContractChangeRequest> findByContractId(Long contractId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM LoanContractChangeRequest c WHERE c.changeRequestId = :changeRequestId")
    Optional<LoanContractChangeRequest> findByIdForUpdate(@Param("changeRequestId") Long changeRequestId);
}
