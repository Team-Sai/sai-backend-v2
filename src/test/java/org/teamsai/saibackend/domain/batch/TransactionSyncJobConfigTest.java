package org.teamsai.saibackend.domain.batch;

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
import org.teamsai.saibackend.domain.batch.common.listener.LoggingJobExecutionListener;
import org.teamsai.saibackend.domain.batch.common.reader.LinkedAccountSyncTargetReaderFactory;
import org.teamsai.saibackend.domain.batch.transaction.config.TransactionSyncJobConfig;
import org.teamsai.saibackend.domain.transaction.service.TransactionSyncFacade;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransactionSyncJobConfigTest {
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
        var job = config.transactionSyncJob(config.transactionSyncStep(facade));
        JobInstance instance = repository.createJobInstance(job.getName(), new JobParameters());
        JobExecution execution = repository.createJobExecution(instance, new JobParameters(), new ExecutionContext());

        job.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getStepExecutions()).singleElement().satisfies(step -> {
            assertThat(step.getStatus()).isEqualTo(BatchStatus.FAILED);
            assertThat(step.getSkipCount()).isZero();
            assertThat(step.getFailureExceptions()).singleElement().satisfies(recorded ->
                    assertThat(recorded).hasRootCause(failure));
        });
    }
}
