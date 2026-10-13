package org.teamsai.saibackend.domain.calendar.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.calendar.entity.PreparationProposal;

import java.time.Instant;
import java.util.Optional;

public interface PreparationProposalRepository
        extends JpaRepository<PreparationProposal, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from PreparationProposal p
            where p.proposalId = :proposalId
              and p.userId = :userId
            """)
    Optional<PreparationProposal> findOwnedForUpdate(
            @Param("proposalId") String proposalId,
            @Param("userId") Long userId
    );

    @Modifying
    @Query(
            value = """
            DELETE FROM preparation_proposal
            WHERE confirmed_at IS NULL
              AND expires_at < :cutoff
            ORDER BY expires_at, proposal_id
            LIMIT :chunkSize
            """,
            nativeQuery = true
    )
    int deleteExpiredUnconfirmedChunk(
            @Param("cutoff") Instant cutoff,
            @Param("chunkSize") int chunkSize
    );
}