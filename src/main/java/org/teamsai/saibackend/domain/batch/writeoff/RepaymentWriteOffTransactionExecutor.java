package org.teamsai.saibackend.domain.batch.writeoff;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepaymentWriteOffTransactionExecutor {

    private static final int CHUNK_SIZE = 500;

    private final RepaymentScheduleService repaymentScheduleService;

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
