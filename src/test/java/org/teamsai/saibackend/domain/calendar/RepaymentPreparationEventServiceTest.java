package org.teamsai.saibackend.domain.calendar;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationEventCreateRequest;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.calendar.service.RepaymentPreparationEventService;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepaymentPreparationEventServiceTest {

    private RepaymentPreparationEventRepository events;
    private RepaymentScheduleRepository schedules;
    private RepaymentAnalysisService analysis;
    private EntityManager entityManager;

    private RepaymentPreparationEventService service;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-08T00:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @BeforeEach
    void setUp() {
        events = mock(RepaymentPreparationEventRepository.class);
        schedules = mock(RepaymentScheduleRepository.class);
        analysis = mock(RepaymentAnalysisService.class);
        entityManager = mock(EntityManager.class);

        service = new RepaymentPreparationEventService(
                events,
                schedules,
                analysis,
                entityManager,
                clock
        );

        User user = mock(User.class);

        when(entityManager.find(
                User.class,
                1L,
                LockModeType.PESSIMISTIC_WRITE
        )).thenReturn(user);

        when(events.findByUserIdAndScheduleId(1L, 10L))
                .thenReturn(Optional.empty());

        when(events.findOverlapping(
                eq(1L),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(List.of());
    }

    @Test
    void createsPreparationEventForUnpaidCandidate() {
        stubCandidate();

        when(events.saveAndFlush(
                any(RepaymentPreparationEvent.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(1L, request());

        assertThat(result.contractId()).isEqualTo(100L);
        assertThat(result.scheduleId()).isEqualTo(10L);
        assertThat(result.title()).isEqualTo("계약 A 준비");
        assertThat(result.startsAt())
                .isEqualTo(request().startsAt());

        verify(events).saveAndFlush(
                any(RepaymentPreparationEvent.class)
        );
    }

    @Test
    void returnsExistingEventForSameRequest() {
        var request = request();

        var existing = new RepaymentPreparationEvent(
                1L,
                100L,
                10L,
                "계약 A 준비",
                request.startsAt(),
                request.endsAt(),
                clock.instant()
        );

        when(events.findByUserIdAndScheduleId(1L, 10L))
                .thenReturn(Optional.of(existing));

        var result = service.create(1L, request);

        assertThat(result.startsAt())
                .isEqualTo(existing.getStartsAt());

        verify(events, never()).saveAndFlush(any());
        verifyNoInteractions(analysis, schedules);
    }

    @Test
    void allowsOverlappingPreparationEventForDifferentSchedule() {
        stubCandidate();

        var overlapping = new RepaymentPreparationEvent(
                1L,
                200L,
                20L,
                "다른 계약 준비",
                request().startsAt(),
                request().endsAt(),
                clock.instant()
        );

        when(events.findOverlapping(
                eq(1L),
                any(Instant.class),
                any(Instant.class)
        )).thenReturn(List.of(overlapping));

        when(events.saveAndFlush(any(RepaymentPreparationEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.create(1L, request());

        assertThat(result.scheduleId()).isEqualTo(10L);
        assertThat(result.startsAt()).isEqualTo(overlapping.getStartsAt());
        verify(events).saveAndFlush(any(RepaymentPreparationEvent.class));
    }

    @Test
    void rejectsEventAfterDueDate() {
        stubCandidate();

        var afterDue = new PreparationEventCreateRequest(
                100L,
                10L,
                Instant.parse("2026-10-16T09:00:00Z"),
                Instant.parse("2026-10-16T09:30:00Z")
        );

        assertThatThrownBy(() ->
                service.create(1L, afterDue)
        )
                .isInstanceOf(DomainException.class)
                .hasMessage(
                        "준비 일정은 계약상 납기일까지 설정하세요."
                );

        verify(events, never()).saveAndFlush(any());
    }

    private PreparationEventCreateRequest request() {
        return new PreparationEventCreateRequest(
                100L,
                10L,
                Instant.parse("2026-10-13T09:00:00Z"),
                Instant.parse("2026-10-13T09:30:00Z")
        );
    }

    private void stubCandidate() {
        RepaymentSchedule schedule =
                mock(RepaymentSchedule.class);

        when(schedule.getContractId()).thenReturn(100L);
        when(schedule.getStatus())
                .thenReturn(RepaymentScheduleStatus.PENDING);

        when(schedules.findByIdForUpdate(10L))
                .thenReturn(Optional.of(schedule));

        RepaymentCandidate candidate = new RepaymentCandidate(
                100L,
                10L,
                "계약 A",
                LocalDate.of(2026, 10, 15),
                new BigDecimal("120000"),
                false
        );

        RepaymentAnalysisContext context =
                new RepaymentAnalysisContext(
                        LocalDate.of(2026, 10, 8),
                        YearMonth.of(2026, 10),
                        new BigDecimal("120000"),
                        BigDecimal.ZERO,
                        new BigDecimal("120000"),
                        new BigDecimal("120000"),
                        List.of(candidate)
                );

        when(analysis.analyze(1L)).thenReturn(context);
    }
}