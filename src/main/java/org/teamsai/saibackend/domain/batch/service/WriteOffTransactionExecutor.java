package org.teamsai.saibackend.domain.batch.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class WriteOffTransactionExecutor {

    private static final int CHUNK_SIZE = 500;

    private final SettlementPaymentService settlementPaymentService;
    private final RepaymentScheduleService repaymentScheduleService;

    public int writeOffOneBatch(List<Long> obligationIds) {
        return settlementPaymentService.writeOffOneBatch(obligationIds);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeOffSchedulesInNewTransaction(List<Long> scheduleIds) {
        int total = 0;
        for (List<Long> chunk : partition(scheduleIds, CHUNK_SIZE)) {
            total += repaymentScheduleService.writeOffSchedules(chunk);
        }
        log.info("상환 스케줄 상각 처리, {}건", total);
        return total;
    }

    private List<List<Long>> partition(List<Long> list, int size) {
        List<List<Long>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}
