package org.teamsai.saibackend.domain.contract.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface RepaymentScheduleRepository extends JpaRepository<RepaymentSchedule, Long> {

    List<RepaymentSchedule> findByContractIdOrderBySequenceAsc(Long contractId);

    List<RepaymentSchedule> findByStatusAndDueDateBefore(RepaymentScheduleStatus status, LocalDate date);

    List<RepaymentSchedule> findByDueDateInAndStatus(List<LocalDate> dueDate, RepaymentScheduleStatus status);

    List<RepaymentSchedule> findByStatusAndDueDateLessThanEqual(RepaymentScheduleStatus status, LocalDate cutoffDate);

    Optional<RepaymentSchedule> findFirstByContractIdAndStatusInOrderBySequenceAsc(
            Long contractId, List<RepaymentScheduleStatus> statuses
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RepaymentSchedule r WHERE r.scheduleId = :scheduleId")
    Optional<RepaymentSchedule> findByIdForUpdate(@Param("scheduleId") Long scheduleId);

    void deleteByContractIdAndStatus(Long contractId, RepaymentScheduleStatus status);

    @Query(value = """
        SELECT
            r.schedule_id          AS scheduleId,
            r.contract_id          AS contractId,
            r.sequence              AS sequence,
            r.due_date              AS dueDate,
            r.principal_due         AS principalDue,
            r.interest_due          AS interestDue,
            r.total_payment_due     AS totalPaymentDue,
            GREATEST(
                r.total_payment_due - COALESCE((
                    SELECT SUM(pr.amount)
                    FROM payment_record pr
                    WHERE pr.payment_target_type = 'LOAN'
                      AND pr.target_id = r.schedule_id
                      AND pr.record_status = 'CONFIRMED'
                ), 0),
                0
            ) AS remainingPaymentAmount,
            r.remaining_principal   AS remainingPrincipal,
            r.status                AS status,
            r.paid_at               AS paidAt,
            r.created_at            AS createdAt
        FROM repayment_schedule r
        WHERE r.contract_id IN (:contractIds)
        ORDER BY r.sequence ASC
        """, nativeQuery = true)
    List<RepaymentScheduleWithRemainingProjection> findByContractIds(@Param("contractIds") List<Long> contractIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RepaymentSchedule r WHERE r.contractId = :contractId ORDER BY r.sequence Asc")
    List<RepaymentSchedule> findByContractIdForUpdate(@Param("contractId")Long contractId);

    @Modifying
    @Query("UPDATE RepaymentSchedule r SET r.status = :status " +
            "WHERE r.scheduleId IN :scheduleIds AND r.status = :expectedStatus")
    int updateStatusBulk(
            @Param("scheduleIds") List<Long> scheduleIds,
            @Param("status") RepaymentScheduleStatus status,
            @Param("expectedStatus") RepaymentScheduleStatus expectedStatus);

    @Modifying
    @Query("UPDATE RepaymentSchedule r SET r.status = :newStatus " +
            "WHERE r.status = :expectedStatus AND r.dueDate < :baseDate")
    int markOverdueBulk(
            @Param("newStatus") RepaymentScheduleStatus newStatus,
            @Param("expectedStatus") RepaymentScheduleStatus expectedStatus,
            @Param("baseDate") LocalDate baseDate);

    @Query("SELECT r.scheduleId FROM RepaymentSchedule r " +
            "WHERE r.status = :status AND r.dueDate <= :cutoffDate")
    List<Long> findScheduleIdsByStatusAndDueDateLessThanEqual(
            @Param("status") RepaymentScheduleStatus status,
            @Param("cutoffDate") LocalDate cutoffDate);
}
