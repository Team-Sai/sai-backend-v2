package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.contract.entity.EarlyRepayment;

import java.util.List;

public interface EarlyRepaymentRepository extends JpaRepository<EarlyRepayment, Long> {

    List<EarlyRepayment> findByContractIdOrderByProcessedAtDesc(Long contractId);
}