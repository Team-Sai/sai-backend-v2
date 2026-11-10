package org.teamsai.saibackend.domain.contract.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.contract.entity.DepositReturn;

import java.util.List;

public interface DepositReturnRepository extends JpaRepository<DepositReturn, Long> {

    List<DepositReturn> findByContractIdOrderByReturnedAtDesc(Long contractId);
}