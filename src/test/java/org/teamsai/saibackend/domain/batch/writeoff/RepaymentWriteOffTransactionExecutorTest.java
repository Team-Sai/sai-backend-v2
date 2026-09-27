package org.teamsai.saibackend.domain.batch.writeoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.batch.writeoff.RepaymentWriteOffTransactionExecutor;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepaymentWriteOffTransactionExecutorTest {

    @Mock
    private RepaymentScheduleService repaymentScheduleService;
    @InjectMocks
    private RepaymentWriteOffTransactionExecutor writeOffTransactionExecutor;

    @Test
    void 상환스케줄_500건_초과시_청크로_나누어_호출된다() {
        List<Long> ids = LongStream.rangeClosed(1, 700).boxed().collect(Collectors.toList());
        when(repaymentScheduleService.writeOffSchedules(anyList())).thenReturn(500, 200);
        int total = writeOffTransactionExecutor.writeOffSchedulesInNewTransaction(ids);
        assertThat(total).isEqualTo(700);
        verify(repaymentScheduleService, times(2)).writeOffSchedules(anyList());
    }
}
