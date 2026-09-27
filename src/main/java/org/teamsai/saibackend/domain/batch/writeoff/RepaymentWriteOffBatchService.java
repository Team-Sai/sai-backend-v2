package org.teamsai.saibackend.domain.batch.writeoff;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RepaymentWriteOffBatchService {
    private static final int WRITE_OFF_DAYS_AFTER_OVERDUE = 30;
    private final RepaymentScheduleService repaymentScheduleService;
    private final RepaymentWriteOffTransactionExecutor writeOffTransactionExecutor;

    @Transactional
    public int writeOffRepaymentSchedules(LocalDate baseDate) {
        LocalDate cutoffDate = baseDate.minusDays(WRITE_OFF_DAYS_AFTER_OVERDUE);
        List<Long> candidateIds = repaymentScheduleService.findWriteOffCandidateScheduleIds(cutoffDate);
        if (candidateIds.isEmpty()) {
            return 0;
        }
        return writeOffTransactionExecutor.writeOffSchedulesInNewTransaction(candidateIds);
    }
}