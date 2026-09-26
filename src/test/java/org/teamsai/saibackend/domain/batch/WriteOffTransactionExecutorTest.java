package org.teamsai.saibackend.domain.batch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.batch.service.WriteOffTransactionExecutor;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WriteOffTransactionExecutorTest {

    @Mock
    private SettlementPaymentService settlementPaymentService;
    @Mock
    private RepaymentScheduleService repaymentScheduleService;
    @InjectMocks
    private WriteOffTransactionExecutor writeOffTransactionExecutor;

    @Test
    void 정산_상각은_배치전체를_결제서비스에_위임한다() {
        List<Long> ids = LongStream.rangeClosed(1, 1200).boxed().collect(Collectors.toList());
        when(settlementPaymentService.writeOffOneBatch(ids)).thenReturn(900);
        // when
        int total = writeOffTransactionExecutor.writeOffOneBatch(ids);
        // then
        assertThat(total).isEqualTo(900);
        verify(settlementPaymentService).writeOffOneBatch(ids);
    }

    @Test
    void 정확히_500건이면_한_번만_호출된다() {
        List<Long> ids = LongStream.rangeClosed(1, 500).boxed().collect(Collectors.toList());
        when(settlementPaymentService.writeOffOneBatch(anyList())).thenReturn(500);
        int total = writeOffTransactionExecutor.writeOffOneBatch(ids);
        assertThat(total).isEqualTo(500);
        verify(settlementPaymentService, times(1)).writeOffOneBatch(anyList());
    }

    @Test
    void 상환스케줄_500건_초과시_청크로_나누어_호출된다() {
        List<Long> ids = LongStream.rangeClosed(1, 700).boxed().collect(Collectors.toList());
        when(repaymentScheduleService.writeOffSchedules(anyList())).thenReturn(500, 200);
        int total = writeOffTransactionExecutor.writeOffSchedulesInNewTransaction(ids);
        assertThat(total).isEqualTo(700);
        verify(repaymentScheduleService, times(2)).writeOffSchedules(anyList());
    }
}
