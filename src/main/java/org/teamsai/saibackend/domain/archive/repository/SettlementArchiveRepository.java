package org.teamsai.saibackend.domain.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.teamsai.saibackend.domain.archive.entity.SettlementArchiveSnapshot;

import java.util.Optional;

public interface SettlementArchiveRepository extends JpaRepository<SettlementArchiveSnapshot, Long> {

    Optional<SettlementArchiveSnapshot> findBySettlementId(Long settlementId);
}
