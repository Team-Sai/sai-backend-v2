package org.teamsai.saibackend.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.teamsai.saibackend.domain.settlement.type.SettlementAccountStatus;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Table(name = "settlement_account")
public class SettlementAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_account_id")
    private Long settlementAccountId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "settlement_id", nullable = false)
    private Settlement settlement;

    @Column(name = "linked_account_id", nullable = false)
    private Long linkedAccountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_status", nullable = false)
    private SettlementAccountStatus accountStatus;

    @Column(name = "selected_at", nullable = false)
    private LocalDateTime selectedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    public static SettlementAccount create(
            Settlement settlement,
            Long linkedAccountId,
            LocalDateTime selectedAt
    ) {
        return SettlementAccount.builder()
                .settlement(settlement)
                .linkedAccountId(linkedAccountId)
                .accountStatus(SettlementAccountStatus.ACTIVE)
                .selectedAt(selectedAt)
                .build();
    }

    public void replace(LocalDateTime endedAt) {
        this.accountStatus = SettlementAccountStatus.REPLACED;
        this.endedAt = endedAt;
    }
}