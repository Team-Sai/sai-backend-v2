package org.teamsai.saibackend.domain.batch.writeoff;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
@ExtendWith(MockitoExtension.class)
class RepaymentWriteOffBatchServiceTest {
    @Mock
    private RepaymentScheduleService repaymentScheduleService;
    @Mock
    private RepaymentWriteOffTransactionExecutor writeOffTransactionExecutor;
    @InjectMocks
    private RepaymentWriteOffBatchService writeOffBatchService;
    private final LocalDate baseDate = LocalDate.of(2026, 8, 19);
    @Nested
    class WriteOffRepaymentSchedules {
        @Test
        void 대상이_없으면_0을_반환한다() {
            when(repaymentScheduleService.findWriteOffCandidateScheduleIds(any()))
                    .thenReturn(Collections.emptyList());
            int result = writeOffBatchService.writeOffRepaymentSchedules(baseDate);
            assertThat(result).isZero();
            verifyNoInteractions(writeOffTransactionExecutor);
        }

        @Test
        void 대상이_있으면_상각건수를_반환한다() {
            List<Long> candidateIds = List.of(10L, 20L, 30L);
            when(repaymentScheduleService.findWriteOffCandidateScheduleIds(any()))
                    .thenReturn(candidateIds);
            when(writeOffTransactionExecutor.writeOffSchedulesInNewTransaction(candidateIds))
                    .thenReturn(3);
            int result = writeOffBatchService.writeOffRepaymentSchedules(baseDate);
            assertThat(result).isEqualTo(3);
            verify(writeOffTransactionExecutor).writeOffSchedulesInNewTransaction(candidateIds);
        }

        @Test
        void cutoffDate가_baseDate에서_30일_전으로_계산되어_전달된다() {
            when(repaymentScheduleService.findWriteOffCandidateScheduleIds(any()))
                    .thenReturn(Collections.emptyList());
            writeOffBatchService.writeOffRepaymentSchedules(baseDate);
            verify(repaymentScheduleService).findWriteOffCandidateScheduleIds(baseDate.minusDays(30));
        }
    }
}
