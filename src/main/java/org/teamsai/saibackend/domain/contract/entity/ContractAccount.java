package org.teamsai.saibackend.domain.contract.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.contract.dto.ContractAccountStatus;

import java.time.LocalDateTime;

@Entity
@Table(name="contract_account")
@Getter
@NoArgsConstructor(access= AccessLevel.PROTECTED)
public class ContractAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long contractAccountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linked_account_id", nullable = false)
    private LinkedBankAccount linkedAccount;

    @Enumerated(EnumType.STRING)
    @ColumnDefault("ACTIVE")
    private ContractAccountStatus accountStatus;

    @CreationTimestamp
    @Column(name = "selected_at", nullable = false, updatable = false)
    private LocalDateTime selectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "contract_id", nullable = false)
    private LoanContract loanContract;

    @Builder
    public ContractAccount(LinkedBankAccount linkedAccount,ContractAccountStatus accountStatus,
                           LoanContract loanContract){
        this.linkedAccount = linkedAccount;
        this.accountStatus = (accountStatus != null) ? accountStatus : ContractAccountStatus.ACTIVE;
        this.loanContract = loanContract;
    }

    public void deactivate(ContractAccountStatus accountStatus) {
        this.accountStatus = accountStatus;
        this.endedAt = LocalDateTime.now();
    }
}
