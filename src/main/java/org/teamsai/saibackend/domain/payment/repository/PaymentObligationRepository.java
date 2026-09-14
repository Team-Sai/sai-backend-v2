package org.teamsai.saibackend.domain.payment.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;

import java.util.List;
import java.util.Optional;

public interface PaymentObligationRepository
        extends JpaRepository<PaymentObligationEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT paymentObligation
            FROM PaymentObligationEntity paymentObligation
            WHERE paymentObligation.paymentObligationId =
                  :paymentObligationId
            """)
    Optional<PaymentObligationEntity> findByIdForUpdate(
            @Param("paymentObligationId")
            Long paymentObligationId
    );

    @Query("""
            SELECT paymentObligation
            FROM PaymentObligationEntity paymentObligation
            WHERE paymentObligation.participantId IN :participantIds
              AND paymentObligation.obligationStatus =
                  :obligationStatus
              AND paymentObligation.paymentObligationId IN (
                    SELECT MAX(latestPaymentObligation.paymentObligationId)
                    FROM PaymentObligationEntity latestPaymentObligation
                    WHERE latestPaymentObligation.participantId IN :participantIds
                      AND latestPaymentObligation.obligationStatus =
                          :obligationStatus
                    GROUP BY latestPaymentObligation.participantId
              )
            """)
    List<PaymentObligationEntity> findLatestByParticipantIds(
            @Param("participantIds")
            List<Long> participantIds,

            @Param("obligationStatus")
            ObligationStatus obligationStatus
    );

    @Query("""
            SELECT paymentObligation
            FROM PaymentObligationEntity paymentObligation
            WHERE paymentObligation.participantId IN :participantIds
              AND paymentObligation.obligationStatus IN :obligationStatuses
              AND paymentObligation.paymentObligationId IN (
                    SELECT MAX(latestPaymentObligation.paymentObligationId)
                    FROM PaymentObligationEntity latestPaymentObligation
                    WHERE latestPaymentObligation.participantId IN :participantIds
                      AND latestPaymentObligation.obligationStatus IN :obligationStatuses
                    GROUP BY latestPaymentObligation.participantId
              )
            """)
    List<PaymentObligationEntity>
    findLatestByParticipantIdsAndObligationStatuses(
            @Param("participantIds")
            List<Long> participantIds,

            @Param("obligationStatuses")
            List<ObligationStatus> obligationStatuses
    );

    @Query("""
            SELECT paymentObligation
            FROM PaymentObligationEntity paymentObligation
            WHERE paymentObligation.participantId IN :participantIds
              AND paymentObligation.paymentStatus IN :paymentStatuses
              AND paymentObligation.obligationStatus =
                  :obligationStatus
              AND paymentObligation.overdueSince IS NULL
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<PaymentObligationEntity> findUnpaidByParticipantIds(
            @Param("participantIds")
            List<Long> participantIds,

            @Param("paymentStatuses")
            List<PaymentStatus> paymentStatuses,

            @Param("obligationStatus")
            ObligationStatus obligationStatus
    );


    @Query("""
            SELECT paymentObligation.paymentObligationId
            FROM PaymentObligationEntity paymentObligation
            WHERE paymentObligation.obligationStatus =
                  :obligationStatus
              AND paymentObligation.paymentStatus IN :paymentStatuses
              AND paymentObligation.overdueSince IS NOT NULL
              AND paymentObligation.overdueSince <= :cutoffDateTime
            """)
    List<Long> findWriteOffCandidateIds(
            @Param("obligationStatus")
            ObligationStatus obligationStatus,

            @Param("paymentStatuses")
            List<PaymentStatus> paymentStatuses,

            @Param("cutoffDateTime")
            java.time.LocalDateTime cutoffDateTime
    );

    List<PaymentObligationEntity> findByParticipantIdIn(
            List<Long> participantIds
    );

    @Query(
            value = """
                SELECT DISTINCT sp.settlement_id
                FROM payment_obligation po
                JOIN settlement_participant sp
                  ON sp.participant_id = po.participant_id
                WHERE po.payment_obligation_id IN (:obligationIds)
                """,
            nativeQuery = true
    )
    List<Long> findSettlementIdsByObligationIds(
            @Param("obligationIds")
            List<Long> obligationIds
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT paymentObligation
        FROM PaymentObligationEntity paymentObligation
        WHERE paymentObligation.paymentObligationId IN :obligationIds
          AND paymentObligation.obligationStatus = :obligationStatus
          AND paymentObligation.paymentStatus IN :paymentStatuses
        """)
    List<PaymentObligationEntity> findWriteOffTargetsForUpdate(
            @Param("obligationIds")
            List<Long> obligationIds,

            @Param("obligationStatus")
            ObligationStatus obligationStatus,

            @Param("paymentStatuses")
            List<PaymentStatus> paymentStatuses
    );
}
