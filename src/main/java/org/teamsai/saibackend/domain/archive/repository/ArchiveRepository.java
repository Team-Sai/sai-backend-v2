package org.teamsai.saibackend.domain.archive.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.archive.entity.ArchiveStatus;
import org.teamsai.saibackend.domain.archive.entity.ArchiveFile;

import java.util.List;

public interface ArchiveRepository extends JpaRepository<ArchiveFile, Long> {

    List<ArchiveFile> findByDomainTypeAndReferenceIdOrderByCreatedAtDesc(ArchiveStatus domainType, Long referenceId);

    @Query("""
            SELECT f FROM ArchiveFile f
            JOIN LoanContract c ON c.contractId = f.referenceId
            WHERE c.creditor.userId = :userId OR c.debtor.userId = :userId
            ORDER BY f.createdAt DESC
            """)
    List<ArchiveFile> findAllByUserId(@Param("userId") Long userId);
}
