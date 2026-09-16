package org.teamsai.saibackend.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@IdClass(SettlementAbandonmentAlertId.class)
@Table(name = "settlement_abandonment_alert")
public class SettlementAbandonmentAlert {

    @Id
    @Column(name = "settlement_id", nullable = false)
    private Long settlementId;

    @Id
    @Column(name = "reference_date", nullable = false)
    private LocalDate referenceDate;

    @Column(name = "notified_at", nullable = false)
    private LocalDateTime notifiedAt;

    public static SettlementAbandonmentAlert create(
            Long settlementId,
            LocalDate referenceDate,
            LocalDateTime notifiedAt
    ) {
        return SettlementAbandonmentAlert.builder()
                .settlementId(settlementId)
                .referenceDate(referenceDate)
                .notifiedAt(notifiedAt)
                .build();
    }
}