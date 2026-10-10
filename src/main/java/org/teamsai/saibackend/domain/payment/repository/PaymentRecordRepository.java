package org.teamsai.saibackend.domain.payment.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface PaymentRecordRepository
        extends JpaRepository<PaymentRecord, Long> {

    boolean existsByBankTransactionId(
            Long bankTransactionId
    );

    @Query("""
            SELECT COALESCE(SUM(paymentRecord.amount), 0)
            FROM PaymentRecord paymentRecord
            WHERE paymentRecord.paymentTargetType = :paymentTargetType
              AND paymentRecord.targetId = :targetId
              AND paymentRecord.recordStatus = :recordStatus
            """)
    BigDecimal sumConfirmedAmountByTarget(
            @Param("paymentTargetType")
            PaymentTargetType paymentTargetType,

            @Param("targetId")
            Long targetId,

            @Param("recordStatus")
            RecordStatus recordStatus
    );

    @Query("""
            SELECT paymentRecord
            FROM PaymentRecord paymentRecord
            WHERE paymentRecord.paymentTargetType = :paymentTargetType
              AND paymentRecord.targetId IN :targetIds
              AND paymentRecord.recordStatus = :recordStatus
            ORDER BY paymentRecord.recordedAt DESC
            """)
    List<PaymentRecord> findConfirmedByTargetIds(
            @Param("paymentTargetType")
            PaymentTargetType paymentTargetType,

            @Param("targetIds")
            List<Long> targetIds,

            @Param("recordStatus")
            RecordStatus recordStatus
    );

    @Query(value = """
        SELECT
            COUNT(*) AS confirmedRecordCount,
            COALESCE(SUM(pr.amount), 0) AS confirmedRecordedAmount,
            COUNT(DISTINCT pr.target_id) AS recordedScheduleCount
        FROM payment_record pr
        JOIN repayment_schedule rs
          ON rs.schedule_id = pr.target_id
        JOIN loan_contract lc
          ON lc.contract_id = rs.contract_id
        WHERE pr.payment_target_type = 'LOAN'
          AND pr.record_status = 'CONFIRMED'
          AND lc.debtor_id = :userId
          AND pr.recorded_at >= :fromInclusive
          AND pr.recorded_at < :toExclusive
        """, nativeQuery = true)
    PreparationRepaymentHistoryProjection summarizePreparationHistory(
            @Param("userId") Long userId,
            @Param("fromInclusive") LocalDateTime fromInclusive,
            @Param("toExclusive") LocalDateTime toExclusive
    );
}