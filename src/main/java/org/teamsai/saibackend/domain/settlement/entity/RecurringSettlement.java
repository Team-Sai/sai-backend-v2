package org.teamsai.saibackend.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.teamsai.saibackend.domain.settlement.type.CycleRule;
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
@Table(name = "recurring_settlement")

public class RecurringSettlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "recurring_settlement_id")
    private Long recurringSettlementId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id",nullable = false)
    private User owner;

    @Column(name = "settlement_category", nullable = false)
    private String settlementCategory;

    @Column(name = "title",nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false)
    private SplitType splitType;

    @Column(name = "total_amount", nullable = false,precision = 19,scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "cycle_rule", nullable = false)
    private CycleRule cycleRule;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
