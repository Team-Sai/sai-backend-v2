package org.teamsai.saibackend.domain.batch.writeoff;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SettlementWriteOffTransactionExecutor {

    private final SettlementPaymentService settlementPaymentService;

    public int writeOffOneBatch(List<Long> obligationIds) {
        return settlementPaymentService.writeOffOneBatch(obligationIds);
    }

}
