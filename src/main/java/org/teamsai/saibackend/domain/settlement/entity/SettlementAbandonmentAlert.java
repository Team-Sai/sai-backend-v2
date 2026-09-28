package org.teamsai.saibackend.domain.settlement.entity;

import jakarta.persistence.*;
import lombok.*;
import org.teamsai.saibackend.domain.settlement.type.AbandonmentDeliveryStatus;

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

    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 16)
    private AbandonmentDeliveryStatus deliveryStatus;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    public static SettlementAbandonmentAlert pending(
            Long settlementId,
            LocalDate referenceDate,
            String message
    ) {
        return SettlementAbandonmentAlert.builder()
                .settlementId(settlementId)
                .referenceDate(referenceDate)
                .deliveryStatus(AbandonmentDeliveryStatus.PENDING)
                .message(message)
                .build();
    }

    public void markSent(LocalDateTime sentAt) {
        deliveryStatus = AbandonmentDeliveryStatus.SENT;
        notifiedAt = sentAt;
    }
}
