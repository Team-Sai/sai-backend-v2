package org.teamsai.saibackend.domain.matching.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.matching.entity.BankTransactionMatchCandidateEntity;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateStatus;

import java.util.List;
import java.util.Optional;

public interface BankTransactionMatchCandidateRepository
        extends JpaRepository<BankTransactionMatchCandidateEntity, Long> {

    List<BankTransactionMatchCandidateEntity>
    findAllByBankTransactionIdOrderByMatchCandidateIdAsc(Long bankTransactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT candidate
            FROM BankTransactionMatchCandidateEntity candidate
            WHERE candidate.matchCandidateId = :matchCandidateId
              AND candidate.bankTransactionId = :bankTransactionId
              AND candidate.candidateStatus = :candidateStatus
            """)
    Optional<BankTransactionMatchCandidateEntity> findAvailableForUpdate(
            @Param("matchCandidateId") Long matchCandidateId,
            @Param("bankTransactionId") Long bankTransactionId,
            @Param("candidateStatus") MatchingCandidateStatus candidateStatus
    );

    long countByBankTransactionIdAndCandidateStatus(
            Long bankTransactionId,
            MatchingCandidateStatus candidateStatus
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM BankTransactionMatchCandidateEntity candidate
            WHERE candidate.bankTransactionId = :bankTransactionId
            """)
    int deleteAllByBankTransactionId(@Param("bankTransactionId") Long bankTransactionId);
}
