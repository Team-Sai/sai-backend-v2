package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.contract.entity.EarlyRepayment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface EarlyRepaymentRepository extends JpaRepository<EarlyRepayment, Long> {

    List<EarlyRepayment> findByContractIdOrderByProcessedAtDesc(Long contractId);

    Optional<EarlyRepayment> findFirstByContractIdAndPrincipalAmountGreaterThanOrderByProcessedAtDesc(
            Long contractId, BigDecimal principalAmount);
}