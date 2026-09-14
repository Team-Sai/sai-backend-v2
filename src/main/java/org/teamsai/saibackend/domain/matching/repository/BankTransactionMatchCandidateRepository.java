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

    @Query(value = """
            SELECT
            mc.match_candidate_id,
            mc.bank_transaction_id,
            mc.target_type,
            mc.target_id,
            mc.expected_remaining_amount,
            mc.amount_match_type,
            mc.candidate_status,
            mc.invalidated_at,
            mc.invalidation_reason,
            mc.created_at
            FROM bank_transaction_match_candidate mc
            JOIN bank_transaction bt
                ON bt.bank_transaction_id = mc.bank_transaction_id
            WHERE mc.match_candidate_id = :matchCandidateId
            AND mc.bank_transaction_id = :bankTransactionId
            AND mc.candidate_status = 'AVAILABLE'
            AND (
                (
                    mc.target_type = 'SETTLEMENT'
                    AND EXISTS (
                        SELECT 1
                        FROM payment_obligation po
                        JOIN settlement_participant sp
                            ON sp.participant_id = po.participant_id
                        JOIN settlement s
                            ON s.settlement_id = sp.settlement_id
                        WHERE po.payment_obligation_id = mc.target_id
                        AND po.obligation_status = 'ACTIVE'
                        AND sp.participant_status = 'ACTIVE'
                        AND s.settlement_status = 'IN_PROGRESS'
                        AND po.expected_amount > COALESCE((
                            SELECT SUM(pr.amount)
                            FROM payment_record pr
                            WHERE pr.payment_target_type = 'SETTLEMENT'
                            AND pr.target_id = po.payment_obligation_id
                            AND pr.record_status = 'CONFIRMED'
                        ), 0)
                    )
                )
                OR (
                    mc.target_type = 'LOAN'
                    AND EXISTS (
                        SELECT 1
                        FROM repayment_schedule rs
                        WHERE rs.schedule_id = mc.target_id
                        AND rs.status = 'PENDING'
                        AND rs.total_payment_due > COALESCE((
                            SELECT SUM(pr.amount)
                            FROM payment_record pr
                            WHERE pr.payment_target_type = 'LOAN'
                            AND pr.target_id = rs.schedule_id
                            AND pr.record_status = 'CONFIRMED'
                        ), 0)
                    )
                )
            )
            """, nativeQuery = true)
    Optional<BankTransactionMatchCandidateEntity> findByIdAndBankTransactionId(
            @Param("matchCandidateId") Long matchCandidateId,
            @Param("bankTransactionId") Long bankTransactionId
    );

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
