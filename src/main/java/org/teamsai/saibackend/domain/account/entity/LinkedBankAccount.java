package org.teamsai.saibackend.domain.account.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "linked_bank_account",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_user_account",
                columnNames = {"user_id", "account_id"}
        )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LinkedBankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "linked_account_id")
    private Long linkedAccountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "bank_code", nullable = false, length = 20)
    private String bankCode;

    @Column(name = "account_number", nullable = false, length = 50)
    private String accountNumber;

    @Column(name = "account_alias", length = 50)
    private String accountAlias;

    @Column(name = "account_holder_name", nullable = false, length = 50)
    private String accountHolderName;

    @Column(name = "balance", precision = 10, scale = 0)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 20)
    private ConnectionStatus connectionStatus;

    @Column(name = "last_synced_transaction_id")
    private Long lastSyncedTransactionId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    public LinkedBankAccount(Long linkedAccountId, Long userId, Long accountId, String bankCode,
                              String accountNumber, String accountAlias, String accountHolderName,
                              BigDecimal balance, ConnectionStatus connectionStatus, Long lastSyncedTransactionId) {
        this.linkedAccountId = linkedAccountId;
        this.userId = userId;
        this.accountId = accountId;
        this.bankCode = bankCode;
        this.accountNumber = accountNumber;
        this.accountAlias = accountAlias;
        this.accountHolderName = accountHolderName;
        this.balance = balance;
        this.connectionStatus = (connectionStatus != null) ? connectionStatus : ConnectionStatus.AVAILABLE;
        this.lastSyncedTransactionId = lastSyncedTransactionId;
    }
}
