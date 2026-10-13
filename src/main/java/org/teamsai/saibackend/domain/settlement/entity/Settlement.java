package org.teamsai.saibackend.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.teamsai.saibackend.domain.settlement.type.SettlementDirection;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementType;
import org.teamsai.saibackend.domain.settlement.type.SplitType;
import org.teamsai.saibackend.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "settlement")

public class Settlement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "settlement_id")
    private Long settlementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_settlement_id")
    private RecurringSettlement recurringSettlement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id",nullable = false)
    private User owner;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_type",nullable = false)
    private SettlementType settlementType;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_status",nullable = false)
    private SettlementStatus settlementStatus;

    @Column(name = "settlement_category",nullable = false)
    private String settlementCategory;

    @Column(name = "title",nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type")
    private SplitType splitType;

    @Column(name = "total_amount",nullable = false,  precision = 19, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "cycle_date")
    private LocalDate cycleDate;

    @Column(name = "created_at",nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status")
    private SettlementDirection status;
}
