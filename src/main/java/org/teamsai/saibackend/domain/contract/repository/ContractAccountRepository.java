package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.contract.dto.ContractAccountStatus;
import org.teamsai.saibackend.domain.contract.entity.ContractAccount;

import java.util.Optional;

public interface ContractAccountRepository extends JpaRepository<ContractAccount, Long> {

    @Query("""
            SELECT ca FROM ContractAccount ca
            WHERE ca.loanContract.contractId = :contractId
              AND ca.accountStatus = :status
            ORDER BY ca.selectedAt DESC, ca.contractAccountId DESC
            LIMIT 1
            """)
    Optional<ContractAccount> findLatestByContractIdAndStatus(
            @Param("contractId") Long contractId,
            @Param("status") ContractAccountStatus status
    );
}
