package org.teamsai.saibackend.domain.batch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.springframework.dao.DataAccessResourceFailureException;
import org.teamsai.saibackend.domain.account.dto.LinkedAccountSyncTargetDTO;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.batch.common.listener.BaseSkipListener;
import org.teamsai.saibackend.domain.batch.common.listener.LoggingJobExecutionListener;
import org.teamsai.saibackend.domain.batch.common.reader.LinkedAccountSyncTargetReaderFactory;
import org.teamsai.saibackend.domain.batch.transaction.config.TransactionSyncJobConfig;
import org.teamsai.saibackend.domain.transaction.exception.RetryableBankTransactionFetchException;
import org.teamsai.saibackend.domain.transaction.service.TransactionSyncFacade;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionSyncJobConfigTest {
    @Test
    @DisplayName("은행 조회 재시도 소진 시 해당 계좌만 skip하고 다음 계좌를 처리한다")
    void skipsExhaustedBankFailureAndProcessesNextAccount() throws Exception {
        var fixture = new Fixture();
        var failed = new LinkedAccountSyncTargetDTO(10L, 1L);
        var next = new LinkedAccountSyncTargetDTO(20L, 2L);
        var failure = retryableFailure();
        fixture.targets(List.of(failed, next));
        when(fixture.facade.syncAndMatch(1L, 10L, true)).thenThrow(failure);

        var execution = fixture.execute();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
            assertThat(step.getProcessSkipCount()).isEqualTo(1);
            assertThat(step.getWriteCount()).isEqualTo(1);
        });
        verify(fixture.facade, times(3)).syncAndMatch(1L, 10L, true);
        verify(fixture.facade).syncAndMatch(2L, 20L, true);
        verify(fixture.listener).onSkipInProcess(failed, failure);
        verifyNoMoreInteractions(fixture.listener);
    }

    @Test
    @DisplayName("은행 조회가 재시도로 복구되면 skip 없이 완료한다")
    void completesWithoutSkipWhenRetrySucceeds() throws Exception {
        var fixture = new Fixture();
        fixture.targets(List.of(new LinkedAccountSyncTargetDTO(10L, 1L)));
        when(fixture.facade.syncAndMatch(1L, 10L, true))
                .thenThrow(retryableFailure()).thenReturn(null);

        var execution = fixture.execute();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
            assertThat(step.getSkipCount()).isZero();
            assertThat(step.getWriteCount()).isEqualTo(1);
        });
        verify(fixture.facade, times(2)).syncAndMatch(1L, 10L, true);
        verifyNoInteractions(fixture.listener);
    }

    @Test
    @DisplayName("동일 에러 코드의 일반 도메인 예외는 재시도하거나 skip하지 않는다")
    void failsForNonRetryableDomainException() throws Exception {
        var fixture = new Fixture();
        fixture.targets(List.of(new LinkedAccountSyncTargetDTO(10L, 1L),
                new LinkedAccountSyncTargetDTO(20L, 2L)));
        when(fixture.facade.syncAndMatch(1L, 10L, true))
                .thenThrow(AccountErrorCode.BANK_SERVER_UNAVAILABLE.toException());

        var execution = fixture.execute();

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).singleElement()
                .satisfies(step -> assertThat(step.getSkipCount()).isZero());
        verify(fixture.facade).syncAndMatch(1L, 10L, true);
        verify(fixture.facade, never()).syncAndMatch(2L, 20L, true);
        verifyNoInteractions(fixture.listener);
    }

    @ParameterizedTest
    @ValueSource(ints = {50, 51})
    @DisplayName("Step 전체에서 50개까지 skip을 허용하고 51번째 실패에서 중단한다")
    void enforcesSkipLimitAcrossChunks(int failureCount) throws Exception {
        var fixture = new Fixture();
        fixture.targets(LongStream.rangeClosed(1, failureCount + 1L)
                .mapToObj(id -> new LinkedAccountSyncTargetDTO(id, 1L)).toList());
        when(fixture.facade.syncAndMatch(eq(1L), anyLong(), eq(true)))
                .thenAnswer(invocation -> {
                    long accountId = invocation.getArgument(1);
                    if (accountId <= failureCount) {
                        throw retryableFailure();
                    }
                    return null;
                });

        var execution = fixture.execute();

        assertThat(execution.getStatus()).isEqualTo(
                failureCount == 50 ? BatchStatus.COMPLETED : BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).singleElement()
                .satisfies(step -> assertThat(step.getProcessSkipCount()).isEqualTo(50));
        verify(fixture.listener, times(50)).onSkipInProcess(any(), any());
        verify(fixture.facade, failureCount == 50 ? times(1) : never())
                .syncAndMatch(1L, failureCount + 1L, true);
    }

    private static RetryableBankTransactionFetchException retryableFailure() {
        return new RetryableBankTransactionFetchException(new SocketTimeoutException("bank timeout"));
    }

    private static class Fixture {
        private final ResourcelessJobRepository repository = new ResourcelessJobRepository();
        private final TransactionSyncFacade facade = mock(TransactionSyncFacade.class);
        @SuppressWarnings("unchecked")
        private final JpaPagingItemReader<LinkedAccountSyncTargetDTO> reader = mock(JpaPagingItemReader.class);
        @SuppressWarnings("unchecked")
        private final BaseSkipListener<LinkedAccountSyncTargetDTO, LinkedAccountSyncTargetDTO> listener =
                mock(BaseSkipListener.class);

        private void targets(List<LinkedAccountSyncTargetDTO> targets) throws Exception {
            var iterator = targets.iterator();
            when(reader.read()).thenAnswer(invocation -> iterator.hasNext() ? iterator.next() : null);
        }

        private JobExecution execute() throws Exception {
            var factory = mock(LinkedAccountSyncTargetReaderFactory.class);
            when(factory.create("transactionSyncReader")).thenReturn(reader);
            var config = new TransactionSyncJobConfig(repository, new ResourcelessTransactionManager(),
                    mock(LoggingJobExecutionListener.class), factory);
            var job = config.transactionSyncJob(config.transactionSyncStep(facade, listener));
            var parameters = new JobParameters();
            var instance = repository.createJobInstance(job.getName(), parameters);
            var execution = repository.createJobExecution(instance, parameters, new ExecutionContext());
            job.execute(execution);
            return execution;
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void processingFailureFailsJobInsteadOfSkipping(boolean databaseFailure) throws Exception {
        var repository = new ResourcelessJobRepository();
        var factory = mock(LinkedAccountSyncTargetReaderFactory.class);
        @SuppressWarnings("unchecked")
        JpaPagingItemReader<LinkedAccountSyncTargetDTO> reader = mock(JpaPagingItemReader.class);
        when(factory.create("transactionSyncReader")).thenReturn(reader);
        when(reader.read()).thenReturn(new LinkedAccountSyncTargetDTO(10L, 1L)).thenReturn(null);
        var facade = mock(TransactionSyncFacade.class);
        RuntimeException failure = databaseFailure
                ? new DataAccessResourceFailureException("database unavailable")
                : new NullPointerException("programming error");
        when(facade.syncAndMatch(1L, 10L, true)).thenThrow(failure);
        var config = new TransactionSyncJobConfig(repository, new ResourcelessTransactionManager(),
                mock(LoggingJobExecutionListener.class), factory);
        var step = config.transactionSyncStep(
                facade,
                new BaseSkipListener<
                                        LinkedAccountSyncTargetDTO,
                                        LinkedAccountSyncTargetDTO
                                        >()
        );

        var job = config.transactionSyncJob(step);
        JobInstance instance = repository.createJobInstance(job.getName(), new JobParameters());
        JobExecution execution = repository.createJobExecution(instance, new JobParameters(), new ExecutionContext());

        job.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).singleElement().satisfies(one_step -> {
            assertThat(one_step.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(one_step.getSkipCount()).isZero();
            assertThat(one_step.getFailureExceptions()).singleElement().satisfies(recorded ->
                    assertThat(recorded).hasRootCause(failure));
        });
    }
}
