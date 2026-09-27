package org.teamsai.saibackend.domain.batch.writeoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementWriteOffTransactionExecutorTest {

    @Mock
    private SettlementPaymentService settlementPaymentService;
    @InjectMocks
    private SettlementWriteOffTransactionExecutor writeOffTransactionExecutor;

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

}
