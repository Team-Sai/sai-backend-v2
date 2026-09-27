package org.teamsai.saibackend.domain.batch.settlement;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.ReminderStage;
import org.teamsai.saibackend.domain.payment.dto.PaymentObligationView;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAbandonmentAlertRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementAbandonmentRecorder;
import org.teamsai.saibackend.domain.settlement.support.SettlementReminderSender;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = {NotificationService.class, SettlementAbandonmentRecorder.class,
        SettlementBatchTransactionIntegrationTest.Config.class})
@ActiveProfiles("dev")
class SettlementBatchTransactionIntegrationTest {
    private static final long USER_ID = 889001L;
    private static final long FIRST_ALERT = 889101L;
    private static final long SECOND_ALERT = 889102L;
    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 9, 1);

    @TestConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = "org.teamsai.saibackend.domain")
    @EnableJpaRepositories(basePackageClasses = NotificationRepository.class)
    static class Config {}

    @Autowired NotificationService notificationService;
    @Autowired SettlementAbandonmentRecorder recorder;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    // Simulate a stale existence check followed by a real database primary-key conflict.
    @MockitoBean SettlementAbandonmentAlertRepository alertRepository;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM notification WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM users WHERE user_id = ?", USER_ID);
        jdbc.update("DELETE FROM settlement_abandonment_alert WHERE settlement_id IN (?, ?)",
                FIRST_ALERT, SECOND_ALERT);
    }

    @Test
    void notificationFailureDoesNotRollBackOtherNotificationsOrOuterTransaction() {
        String unique = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO users (user_id, user_token, email, password, name, birth_date) "
                + "VALUES (?, ?, ?, 'test', 'batch-test', '2000-01-01')",
                USER_ID, unique, unique + "@example.com");
        var participants = mock(SettlementParticipantRepository.class);
        var obligations = mock(PaymentObligationQueryService.class);
        var settlement = mock(Settlement.class);
        when(settlement.getSettlementId()).thenReturn(FIRST_ALERT);
        when(settlement.getTitle()).thenReturn("transaction isolation");
        var participant = mock(SettlementParticipant.class);
        var user = mock(User.class);
        when(participant.getParticipantId()).thenReturn(1L);
        when(participant.getUser()).thenReturn(user);
        when(user.getUserId()).thenReturn(USER_ID);
        when(participants.findBySettlementIdAndStatus(FIRST_ALERT, SettlementParticipantStatus.ACTIVE))
                .thenReturn(List.of(participant));
        // A null reference ID violates the real notification table constraint.
        var targets = List.of(obligation(1L), obligation(null), obligation(3L));
        when(obligations.findLatestActiveByParticipantIds(List.of(1L)))
                .thenReturn(targets);
        var sender = new SettlementReminderSender(participants, obligations, notificationService);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            var result = sender.sendForSettlement(settlement, ReminderStage.DDAY);
            assertThat(result.processedCount()).isEqualTo(2);
            assertThat(result.failedCount()).isEqualTo(1);
            assertThat(status.isRollbackOnly()).isFalse();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE user_id = ?",
                Integer.class, USER_ID)).isEqualTo(2);
    }

    @Test
    void duplicateAlertRollsBackOnlyItsOwnTransactionAndNextAlertCommits() {
        assertThat(recorder.recordIfAbsent(FIRST_ALERT, REFERENCE_DATE, "pending test alert")).isTrue();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            assertThatThrownBy(() -> recorder.recordIfAbsent(FIRST_ALERT, REFERENCE_DATE, "pending test alert"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(status.isRollbackOnly()).isFalse();
            assertThat(recorder.recordIfAbsent(SECOND_ALERT, REFERENCE_DATE, "pending test alert")).isTrue();
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_abandonment_alert WHERE settlement_id IN (?, ?)",
                Integer.class, FIRST_ALERT, SECOND_ALERT)).isEqualTo(2);
    }

    private PaymentObligationView obligation(Long id) {
        var obligation = mock(PaymentObligationView.class);
        when(obligation.participantId()).thenReturn(1L);
        when(obligation.paymentObligationId()).thenReturn(id);
        when(obligation.paymentStatus()).thenReturn(PaymentStatus.UNPAID);
        return obligation;
    }
}
