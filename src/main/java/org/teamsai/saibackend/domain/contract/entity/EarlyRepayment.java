package org.teamsai.saibackend.domain.contract.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "early_repayment")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EarlyRepayment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "early_repayment_id")
    private Long earlyRepaymentId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "bank_transaction_id", nullable = false, unique = true)
    private Long bankTransactionId;

    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "shortfall_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal shortfallAmount;

    @Column(name = "accrued_interest", precision = 15, scale = 2, nullable = false)
    private BigDecimal accruedInterest;

    @Column(name = "fee_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal feeAmount;

    @Column(name = "principal_amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal principalAmount;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public EarlyRepayment(Long contractId, Long bankTransactionId, BigDecimal amount,
                          BigDecimal shortfallAmount, BigDecimal accruedInterest, BigDecimal feeAmount,
                          BigDecimal principalAmount, LocalDateTime processedAt) {
        this.contractId = contractId;
        this.bankTransactionId = bankTransactionId;
        this.amount = amount;
        this.shortfallAmount = shortfallAmount;
        this.accruedInterest = accruedInterest;
        this.feeAmount = feeAmount;
        this.principalAmount = principalAmount;
        this.processedAt = processedAt;
    }
}