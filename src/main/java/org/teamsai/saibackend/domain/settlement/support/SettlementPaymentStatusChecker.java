package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class SettlementPaymentStatusChecker {

    private final SettlementPaymentReader settlementPaymentReader;

    @Transactional(readOnly = true)
    public boolean areAllObligationsResolved(Long settlementId) {
        SettlementPaymentData data =
                settlementPaymentReader.read(settlementId);

        if (data.obligations().isEmpty()) {
            return false;
        }

        return data.obligations().stream()
                .allMatch(obligation -> {
                    BigDecimal paidAmount =
                            data.paidAmountMap().getOrDefault(
                                    obligation.paymentObligationId(),
                                    BigDecimal.ZERO
                            );

                    return isResolved(obligation.obligationStatus())
                            || paidAmount.compareTo(
                            obligation.expectedAmount()
                    ) >= 0;
                });
    }

    private boolean isResolved(ObligationStatus status) {
        return status == ObligationStatus.WRITTEN_OFF
                || status == ObligationStatus.EXCLUDED
                || status == ObligationStatus.CANCELLED;
    }
}
