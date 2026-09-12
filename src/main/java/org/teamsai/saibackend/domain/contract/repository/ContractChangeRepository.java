package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.contract.entity.LoanContractChangeRequestEntity;

import java.util.List;

public interface ContractChangeRepository
        extends JpaRepository<LoanContractChangeRequestEntity, Long> {

    List<LoanContractChangeRequestEntity> findByContractId(Long contractId);
}
