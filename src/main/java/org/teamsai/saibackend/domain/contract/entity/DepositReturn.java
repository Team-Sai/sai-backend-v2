package org.teamsai.saibackend.domain.contract.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "deposit_return")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DepositReturn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "deposit_return_id")
    private Long depositReturnId;

    @Column(name = "contract_id", nullable = false)
    private Long contractId;

    @Column(name = "bank_transaction_id", nullable = false, unique = true)
    private Long bankTransactionId;

    @Column(name = "amount", precision = 15, scale = 2, nullable = false)
    private BigDecimal amount;

    @Column(name = "transaction_at", nullable = false)
    private LocalDateTime transactionAt;

    @Column(name = "returned_at", nullable = false)
    private LocalDateTime returnedAt;

    public DepositReturn(Long contractId, Long bankTransactionId, BigDecimal amount,
                         LocalDateTime transactionAt, LocalDateTime returnedAt) {
        this.contractId = contractId;
        this.bankTransactionId = bankTransactionId;
        this.amount = amount;
        this.transactionAt = transactionAt;
        this.returnedAt = returnedAt;
    }
}