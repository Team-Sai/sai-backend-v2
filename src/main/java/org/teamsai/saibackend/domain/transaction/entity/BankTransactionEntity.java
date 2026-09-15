package org.teamsai.saibackend.domain.transaction.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "bank_transaction",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_bank_transaction_linked_external",
                        columnNames = {
                                "linked_account_id",
                                "external_transaction_id"
                        }
                )
        }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BankTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "bank_transaction_id")
    private Long bankTransactionId;

    @Column(name = "linked_account_id", nullable = false)
    private Long linkedAccountId;

    @Column(name = "external_transaction_id", nullable = false, length = 100)
    private String externalTransactionId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 20)
    private BankTransactionType transactionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false, length = 30)
    private BankTransactionProcessingStatus processingStatus;

    @Column(name = "transaction_at", nullable = false)
    private LocalDateTime transactionAt;

    @Column(name = "counterparty_name", length = 50)
    private String counterpartyName;

    @Column(name = "memo", length = 50)
    private String memo;

    @Column(name = "synced_at", nullable = false)
    private LocalDateTime syncedAt;

    @Column(name = "retry_count", nullable = false)
    private Integer retryCount;

    // 신규 거래의 입력값만 받으며, ID와 초기 처리 상태는 외부에서 지정하지 않는다.
    public BankTransactionEntity(
            Long linkedAccountId,
            String externalTransactionId,
            BigDecimal amount,
            BankTransactionType transactionType,
            LocalDateTime transactionAt,
            String counterpartyName,
            String memo,
            LocalDateTime syncedAt
    ) {
        this.linkedAccountId = linkedAccountId;
        this.externalTransactionId = externalTransactionId;
        this.amount = amount;
        this.transactionType = transactionType;
        this.transactionAt = transactionAt;
        this.counterpartyName = counterpartyName;
        this.memo = memo;
        this.syncedAt = syncedAt;
        this.processingStatus = BankTransactionProcessingStatus.PENDING;
        this.retryCount = 0;
    }

    public void changeProcessingStatus(
            BankTransactionProcessingStatus nextStatus
    ) {
        this.processingStatus = nextStatus;
    }
    public void resetToPendingForRetry() {
        this.processingStatus = BankTransactionProcessingStatus.PENDING;
        this.retryCount = this.retryCount + 1;
    }
}
