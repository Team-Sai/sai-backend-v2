package org.teamsai.saibackend.domain.calendar;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.calendar.service.PreparationReminderService;
import org.teamsai.saibackend.domain.contract.dto.response.*;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PreparationReminderServiceTest {
    private final Instant time = Instant.parse("2026-10-13T09:00:00Z");
    private final Clock clock = Clock.fixed(time.plusSeconds(30), ZoneId.of("Asia/Seoul"));
    private RepaymentPreparationEventRepository events;
    private RepaymentScheduleRepository schedules;
    private RepaymentAnalysisService analysis;
    private NotificationService notifications;
    private EntityManager entityManager;
    private PreparationReminderService service;

    @BeforeEach
    void setUp() {
        events = mock(RepaymentPreparationEventRepository.class);
        schedules = mock(RepaymentScheduleRepository.class);
        analysis = mock(RepaymentAnalysisService.class);
        notifications = mock(NotificationService.class);
        entityManager = mock(EntityManager.class);
        when(entityManager.find(User.class,1L,LockModeType.PESSIMISTIC_WRITE))
                .thenReturn(mock(User.class));
        when(schedules.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(mock(RepaymentSchedule.class)));
        service = new PreparationReminderService(events,schedules,analysis,notifications,entityManager,clock);
    }

    @Test
    void groupsSameTimeAndUsesLatestRemainingAmounts() {
        var a = event(1L,10L);
        var b = event(2L,11L);
        when(events.findDueRemindersByUserId(1L,clock.instant())).thenReturn(List.of(a,b));
        stubAnalysis(List.of(candidate(10L,"20000"),candidate(11L,"60000")));
        assertThat(service.processUser(1L,clock.instant())).isEqualTo(1);
        verify(notifications).createIfAbsent(eq(1L),
                eq(NotificationType.REPAYMENT_PREPARATION_REMINDER),anyString(),
                argThat(content -> content.contains("2건") && content.contains("80000원")),
                eq(1L),eq(20261013L));
        assertThat(a.getReminderProcessedAt()).isEqualTo(clock.instant());
        assertThat(b.getReminderProcessedAt()).isEqualTo(clock.instant());
    }

    @Test
    void excludesCompletedScheduleFromGroup() {
        var a = event(1L,10L);
        var b = event(2L,11L);
        when(events.findDueRemindersByUserId(1L,clock.instant())).thenReturn(List.of(a,b));
        stubAnalysis(List.of(candidate(11L,"60000")));
        service.processUser(1L,clock.instant());
        verify(notifications).createIfAbsent(anyLong(),any(),anyString(),
                argThat(content -> content.contains("1건") && content.contains("60000원")),
                eq(1L),eq(20261013L));
        assertThat(a.getReminderProcessedAt()).isNotNull();
    }

    @Test
    void skipsNotificationWhenEveryScheduleIsCompleted() {
        var a = event(1L,10L);
        when(events.findDueRemindersByUserId(1L,clock.instant())).thenReturn(List.of(a));
        stubAnalysis(List.of());
        assertThat(service.processUser(1L,clock.instant())).isZero();
        verifyNoInteractions(notifications);
        assertThat(a.getReminderProcessedAt()).isNotNull();
    }

    @Test
    void rereadsPendingEventsAfterLockAndDoesNotNotifyAgain() {
        when(events.findDueRemindersByUserId(1L,clock.instant())).thenReturn(List.of());
        assertThat(service.processUser(1L,clock.instant())).isZero();
        var order = inOrder(entityManager,events);
        order.verify(entityManager).find(User.class,1L,LockModeType.PESSIMISTIC_WRITE);
        order.verify(events).findDueRemindersByUserId(1L,clock.instant());
        verifyNoInteractions(notifications,analysis,schedules);
    }

    @Test
    void notificationFailureDoesNotMarkGroupProcessed() {
        var a = event(1L,10L);
        when(events.findDueRemindersByUserId(1L,clock.instant())).thenReturn(List.of(a));
        stubAnalysis(List.of(candidate(10L,"40000")));
        doThrow(new IllegalStateException("DB failure")).when(notifications)
                .createIfAbsent(anyLong(),any(),anyString(),anyString(),anyLong(),anyLong());
        assertThatThrownBy(() -> service.processUser(1L,clock.instant()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(a.getReminderProcessedAt()).isNull();
    }

    private RepaymentPreparationEvent event(Long eventId, Long scheduleId) {
        var event = new RepaymentPreparationEvent(1L,100L,scheduleId,"확인",
                time,time.plusSeconds(60),time.minusSeconds(3600));
        ReflectionTestUtils.setField(event,"eventId",eventId);
        return event;
    }
    private RepaymentCandidate candidate(Long scheduleId,String amount) {
        return new RepaymentCandidate(100L,scheduleId,"계약",LocalDate.of(2026,10,15),
                new BigDecimal(amount),false);
    }
    private void stubAnalysis(List<RepaymentCandidate> candidates) {
        when(analysis.analyze(1L)).thenReturn(new RepaymentAnalysisContext(
                LocalDate.of(2026,10,13),YearMonth.of(2026,10),
                BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,candidates));
    }
}