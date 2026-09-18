package org.teamsai.saibackend.domain.contract.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "repayment_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RepaymentScheduleEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "`sequence`", nullable = false)
    private Integer sequence;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(name = "principal_due", precision = 15, scale = 2, nullable = false)
    private BigDecimal principalDue;

    @Column(name = "interest_due", precision = 15, scale = 2, nullable = false)
    private BigDecimal interestDue;

    @Column(name = "total_payment_due", precision = 15, scale = 2, nullable = false)
    private BigDecimal totalPaymentDue;

    @Column(name = "remaining_principal", precision = 15, scale = 2, nullable = false)
    private BigDecimal remainingPrincipal;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RepaymentScheduleStatus status;

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void overDue() {
        this.status = RepaymentScheduleStatus.OVERDUE;
    }

    public void fullPayment(LocalDateTime paidAt){
        this.status = RepaymentScheduleStatus.PAID;
        this.paidAt = paidAt;
    }

    public void writeOff() {
        this.status = RepaymentScheduleStatus.WRITTEN_OFF;
    }

    public RepaymentScheduleEntity(
            Long contractId,
            Integer sequence,
            LocalDate dueDate,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal totalPaymentDue,
            BigDecimal remainingPrincipal,
            RepaymentScheduleStatus status,
            LocalDateTime createdAt
    ) {
        this.contractId = contractId;
        this.sequence = sequence;
        this.dueDate = dueDate;
        this.principalDue = principalDue;
        this.interestDue = interestDue;
        this.totalPaymentDue = totalPaymentDue;
        this.remainingPrincipal = remainingPrincipal;
        this.status = status;
        this.createdAt = createdAt;
    }
}
