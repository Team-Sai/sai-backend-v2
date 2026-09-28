package org.teamsai.saibackend.domain.settlement.support;

import org.teamsai.saibackend.domain.payment.dto.PaymentObligationView;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record SettlementPaymentData(
        List<SettlementParticipant> participants,
        List<PaymentObligationView> obligations,
        List<PaymentRecord> paymentRecords,
        Map<Long, BigDecimal> paidAmountMap
) {

    public static SettlementPaymentData empty() {
        return new SettlementPaymentData(
                List.of(),
                List.of(),
                List.of(),
                Map.of()
        );
    }
}
