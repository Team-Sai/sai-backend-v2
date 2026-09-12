package org.teamsai.saibackend.domain.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.archive.entity.ArchiveStatus;
import org.teamsai.saibackend.domain.archive.entity.File;

import java.util.List;

public interface ArchiveRepository extends JpaRepository<File, Long> {

    List<File> findByDomainTypeAndReferenceIdOrderByCreatedAtDesc(ArchiveStatus domainType, Long referenceId);

    @Query("""
            SELECT f FROM File f
            JOIN LoanContract c ON c.contractId = f.referenceId
            WHERE c.creditorId = :userId OR c.debtorId = :userId
            ORDER BY f.createdAt DESC
            """)
    List<File> findAllByUserId(@Param("userId") Long userId);
}
