package org.teamsai.saibackend.domain.payment.dto;

import org.teamsai.saibackend.domain.payment.entity.PaymentObligation;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PaymentObligationView(
        Long paymentObligationId,
        Long participantId,
        BigDecimal expectedAmount,
        PaymentStatus paymentStatus,
        ObligationStatus obligationStatus,
        LocalDateTime overdueSince
) {
    public static PaymentObligationView from(PaymentObligation entity) {
        return new PaymentObligationView(
                entity.getPaymentObligationId(),
                entity.getParticipantId(),
                entity.getExpectedAmount(),
                entity.getPaymentStatus(),
                entity.getObligationStatus(),
                entity.getOverdueSince()
        );
    }
}
