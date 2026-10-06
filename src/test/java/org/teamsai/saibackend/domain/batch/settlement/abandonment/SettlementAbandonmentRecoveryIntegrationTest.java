package org.teamsai.saibackend.domain.batch.settlement.abandonment;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.repository.support.ResourcelessJobRepository;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAbandonmentAlertRepository;
import org.teamsai.saibackend.domain.batch.common.listener.LoggingJobExecutionListener;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.support.OverdueCriteria;
import org.teamsai.saibackend.domain.settlement.support.SettlementAbandonmentRecorder;
import org.teamsai.saibackend.global.notification.SlackNotifier;

import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {SettlementAbandonmentRecorder.class, SettlementAbandonmentNotifier.class,
        SettlementAbandonmentDetectionService.class, SettlementAbandonmentRecoveryIntegrationTest.Config.class})
@ActiveProfiles("test")
@org.junit.jupiter.api.Tag("integration")
class SettlementAbandonmentRecoveryIntegrationTest {
    private static final long ID = 889201L;
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 9, 1);
    private static final String MESSAGE = "original detection message";

    @TestConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "org.teamsai.saibackend.domain")
    @EnableJpaRepositories(basePackageClasses = SettlementAbandonmentAlertRepository.class)
    static class Config {}

    @Autowired SettlementAbandonmentRecorder recorder;
    @Autowired SettlementAbandonmentNotifier notifier;
    @Autowired SettlementAbandonmentDetectionService detection;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean SettlementRepository settlements;
    @MockitoBean OverdueCriteria criteria;
    @MockitoBean SlackNotifier slack;

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM settlement_abandonment_alert WHERE settlement_id = ?", ID);
    }

    @Test
    void committedPendingRecordIsRecoveredEvenWhenSettlementIsNoLongerACandidate() {
        // Simulate interruption after the preparation commit and before any Slack call.
        assertThat(recorder.recordIfAbsent(ID, REFERENCE_DATE, MESSAGE)).isTrue();
        assertThat(status()).isEqualTo("PENDING");
        when(slack.trySend(MESSAGE)).thenReturn(true);

        // Matches the outer Tasklet transaction; delivery must survive its rollback.
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            detection.detectAbandoned(REFERENCE_DATE.plusDays(4));
            outer.setRollbackOnly();
        });

        assertThat(status()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT notified_at FROM settlement_abandonment_alert WHERE settlement_id = ?",
                java.sql.Timestamp.class, ID)).isNotNull();
        detection.detectAbandoned(REFERENCE_DATE.plusDays(5));
        verify(slack, times(1)).trySend(MESSAGE);
    }

    @Test
    void failedOrDisabledSlackRemainsPendingAndNextRunRetries() {
        recorder.recordIfAbsent(ID, REFERENCE_DATE, MESSAGE);
        when(slack.trySend(MESSAGE)).thenReturn(false, true);

        detection.detectAbandoned(REFERENCE_DATE.plusDays(4));
        assertThat(status()).isEqualTo("PENDING");
        detection.detectAbandoned(REFERENCE_DATE.plusDays(5));
        assertThat(status()).isEqualTo("SENT");
        verify(slack, times(2)).trySend(MESSAGE);
    }

    @Test
    void detectionFailureMarksJobFailedButPendingDeliveryCommits() throws Exception {
        recorder.recordIfAbsent(ID, REFERENCE_DATE, MESSAGE);
        var failure = new IllegalStateException("new detection failed");
        when(settlements.countBySettlementStatus(
                org.teamsai.saibackend.domain.settlement.type.SettlementStatus.IN_PROGRESS))
                .thenThrow(failure);
        when(slack.trySend(MESSAGE)).thenReturn(true);

        var repository = new ResourcelessJobRepository();
        var config = new SettlementAbandonmentJobConfig(repository, transactionManager,
                mock(LoggingJobExecutionListener.class));
        var job = config.settlementAbandonmentJob(config.settlementAbandonmentStep(detection));
        var parameters = new JobParametersBuilder()
                .addString("baseDate", REFERENCE_DATE.plusDays(4).toString()).toJobParameters();
        var instance = repository.createJobInstance(job.getName(), parameters);
        var execution = repository.createJobExecution(instance, parameters, new ExecutionContext());
        job.execute(execution);

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(execution.getAllFailureExceptions()).contains(failure);
        assertThat(status()).isEqualTo("SENT");
        verify(slack).trySend(MESSAGE);
    }

    @Test
    void pendingReaderSeesInnerCommitAfterOuterSnapshotWasCreated() {
        when(slack.trySend(MESSAGE)).thenReturn(true);
        new TransactionTemplate(transactionManager).executeWithoutResult(outer -> {
            jdbc.queryForObject("SELECT COUNT(*) FROM settlement_abandonment_alert", Integer.class);
            recorder.recordIfAbsent(ID, REFERENCE_DATE, MESSAGE);
            detection.detectAbandoned(REFERENCE_DATE.plusDays(4));
            outer.setRollbackOnly();
        });
        assertThat(status()).isEqualTo("SENT");
        verify(slack).trySend(MESSAGE);
    }

    @Test
    void concurrentDeliverySerializesAndDoesNotSendTheSameRecordTwice() throws Exception {
        recorder.recordIfAbsent(ID, REFERENCE_DATE, MESSAGE);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(slack.trySend(MESSAGE)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for competing worker");
            }
            return true;
        });
        var executor = Executors.newFixedThreadPool(2);
        try {
            var first = executor.submit(() -> notifier.sendPending(ID, REFERENCE_DATE));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> notifier.sendPending(ID, REFERENCE_DATE));
            release.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
            assertThat(status()).isEqualTo("SENT");
            verify(slack, times(1)).trySend(MESSAGE);
        } finally {
            release.countDown();
            executor.shutdownNow();
            executor.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void legacySentRecordIsNotReplayed() {
        jdbc.update("INSERT INTO settlement_abandonment_alert (settlement_id, reference_date, notified_at) "
                + "VALUES (?, ?, NOW())", ID, REFERENCE_DATE);
        assertThat(recorder.recordIfAbsent(ID, REFERENCE_DATE, MESSAGE)).isFalse();
        detection.detectAbandoned(REFERENCE_DATE.plusDays(4));
        assertThat(status()).isEqualTo("SENT");
        verifyNoInteractions(slack);
    }

    private String status() {
        return jdbc.queryForObject("SELECT delivery_status FROM settlement_abandonment_alert WHERE settlement_id = ?",
                String.class, ID);
    }
}
