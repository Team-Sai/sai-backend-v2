package org.teamsai.saibackend.domain.payment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.ReviewStatus;

import java.math.BigDecimal;

import java.time.LocalDateTime;

@Entity
@Table(name = "payment_obligation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentObligationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "payment_obligation_id")
    private Long paymentObligationId;

    @Column(name = "participant_id", nullable = false)
    private Long participantId;

    @Column(
            name = "expected_amount",
            nullable = false,
            precision = 19,
            scale = 2
    )
    private BigDecimal expectedAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    private PaymentStatus paymentStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private ReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "obligation_status", nullable = false, length = 30)
    private ObligationStatus obligationStatus;

    @Column(name = "overdue_since")
    private LocalDateTime overdueSince;

    public PaymentObligationEntity(
            Long participantId,
            BigDecimal expectedAmount
    ) {
        this.participantId = participantId;
        this.expectedAmount = expectedAmount;
        this.paymentStatus = PaymentStatus.UNPAID;
        this.reviewStatus = ReviewStatus.NORMAL;
        this.obligationStatus = ObligationStatus.ACTIVE;
        this.overdueSince = null;
    }
    public void changePaymentStatus(
            PaymentStatus paymentStatus
    ) {
        this.paymentStatus = paymentStatus;
    }
    public void markOverdue(
            LocalDateTime overdueSince
    ) {
        this.overdueSince = overdueSince;
    }
    public void clearOverdue() {
        this.overdueSince = null;
    }
    public void writeOff() {
        this.obligationStatus = ObligationStatus.WRITTEN_OFF;
    }
}