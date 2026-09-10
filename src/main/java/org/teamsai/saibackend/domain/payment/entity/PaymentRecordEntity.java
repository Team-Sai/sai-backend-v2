package org.teamsai.saibackend.domain.payment.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "payment_record",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_payment_record_bank_transaction",
                        columnNames = "bank_transaction_id"
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_record_id")
    private Long paymentRecordId;

    @Column(name = "bank_transaction_id", nullable = false)
    private Long bankTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_target_type", nullable = false, length = 30)
    private PaymentTargetType paymentTargetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 30)
    private SourceType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "record_status", nullable = false, length = 30)
    private RecordStatus recordStatus;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    @Column(name = "cancelled_by_id")
    private Long cancelledById;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "memo", length = 500)
    private String memo;

    public PaymentRecordEntity(
            Long bankTransactionId,
            PaymentTargetType paymentTargetType,
            Long targetId,
            BigDecimal amount,
            SourceType sourceType,
            RecordStatus recordStatus,
            LocalDateTime recordedAt
    ) {
        this.bankTransactionId = bankTransactionId;
        this.paymentTargetType = paymentTargetType;
        this.targetId = targetId;
        this.amount = amount;
        this.sourceType = sourceType;
        this.recordStatus = recordStatus;
        this.recordedAt = recordedAt;
    }
}