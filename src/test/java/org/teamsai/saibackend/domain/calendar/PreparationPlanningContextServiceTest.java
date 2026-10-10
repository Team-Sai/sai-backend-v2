package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationFundingRequest;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationEventResponse;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.calendar.calculator.PreparationFundingAssessmentCalculator;
import org.teamsai.saibackend.domain.calendar.service.PreparationPlanningContextService;
import org.teamsai.saibackend.domain.calendar.service.PreparationRepaymentHistoryService;
import org.teamsai.saibackend.domain.calendar.service.RepaymentPreparationEventService;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PreparationPlanningContextServiceTest {

    private RepaymentAnalysisService analysisService;
    private RepaymentPreparationEventService eventService;
    private RepaymentPreparationEventRepository eventRepository;

    private PreparationPlanningContextService service;
    private PreparationRepaymentHistoryService historyService;

    private final YearMonth month = YearMonth.of(2026, 10);

    @BeforeEach
    void setUp() {
        analysisService = mock(RepaymentAnalysisService.class);
        eventService = mock(RepaymentPreparationEventService.class);
        eventRepository =
                mock(RepaymentPreparationEventRepository.class);
        historyService = mock(PreparationRepaymentHistoryService.class);

        service = new PreparationPlanningContextService(
                analysisService,
                eventService,
                eventRepository,
                new PreparationFundingAssessmentCalculator(),
                historyService
        );
    }

    @Test
    void excludesAlreadyPlannedScheduleAndReturnsExistingEvents() {
        RepaymentCandidate first = candidate(10L);
        RepaymentCandidate second = candidate(20L);

        when(analysisService.analyze(1L))
                .thenReturn(context(List.of(first, second)));

        /*
         * 10월 회차에 대한 준비 일정이 9월에 등록된 경우도
         * 신규 제안에서는 제외되어야 한다.
         */
        RepaymentPreparationEvent registered =
                new RepaymentPreparationEvent(
                        1L,
                        100L,
                        10L,
                        "계약 A 준비",
                        Instant.parse("2026-09-30T09:00:00Z"),
                        Instant.parse("2026-09-30T09:30:00Z"),
                        Instant.parse("2026-09-29T00:00:00Z")
                );

        when(eventRepository.findByUserIdAndScheduleIdIn(
                1L,
                List.of(10L, 20L)
        )).thenReturn(List.of(registered));

        PreparationEventResponse octoberEvent =
                new PreparationEventResponse(
                        5L,
                        200L,
                        30L,
                        "다른 계약 준비",
                        Instant.parse("2026-10-13T09:00:00Z"),
                        Instant.parse("2026-10-13T09:30:00Z")
                );

        when(eventService.findMonth(1L, month))
                .thenReturn(List.of(octoberEvent));

        var result = service.load(1L, month);

        assertThat(result.candidates())
                .extracting(RepaymentCandidate::scheduleId)
                .containsExactly(20L);

        assertThat(result.alreadyPlannedScheduleIds())
                .containsExactly(10L);

        assertThat(result.existingEvents())
                .containsExactly(octoberEvent);

        assertThat(result.allCandidates())
                .extracting(RepaymentCandidate::scheduleId)
                .containsExactly(10L, 20L);
    }

    @Test
    void includesAlreadyRegisteredScheduleInFundingAssessment() {
        RepaymentCandidate first = candidate(10L);
        RepaymentCandidate second = candidate(20L);

        when(analysisService.analyze(1L))
                .thenReturn(context(List.of(first, second)));

        RepaymentPreparationEvent registered =
                new RepaymentPreparationEvent(
                        1L,
                        100L,
                        10L,
                        "계약 A 확인",
                        Instant.parse("2026-10-13T09:00:00Z"),
                        Instant.parse("2026-10-13T09:01:00Z"),
                        Instant.parse("2026-10-08T00:00:00Z")
                );

        when(eventRepository.findByUserIdAndScheduleIdIn(
                1L,
                List.of(10L, 20L)
        )).thenReturn(List.of(registered));

        when(eventService.findMonth(1L, month))
                .thenReturn(List.of());

        var request = new PreparationProposalRequest(
                month,
                Set.of(DayOfWeek.MONDAY),
                LocalTime.of(18, 0),
                LocalTime.of(21, 0),
                1,
                2,
                "",
                new PreparationFundingRequest(
                        new BigDecimal("150000"),
                        new BigDecimal("150000"),
                        List.of()
                )
        );

        var result = service.loadForProposal(1L, request);

        // 신규 알림 후보는 미등록 회차만 남는다.
        assertThat(result.candidates())
                .extracting(RepaymentCandidate::scheduleId)
                .containsExactly(20L);

        // 예산에는 두 회차의 잔여액이 모두 포함된다.
        assertThat(result.fundingAssessment().totalRequiredAmount())
                .isEqualByComparingTo("200000");

        assertThat(result.fundingAssessment().budgetShortfall())
                .isEqualByComparingTo("50000");
    }

    @Test
    void skipsScheduleIdQueryWhenNoRepaymentCandidatesExist() {
        when(analysisService.analyze(1L))
                .thenReturn(context(List.of()));

        when(eventService.findMonth(1L, month))
                .thenReturn(List.of());

        var result = service.load(1L, month);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.alreadyPlannedScheduleIds()).isEmpty();

        verifyNoInteractions(eventRepository);
    }

    @Test
    void rejectsAnotherMonthInsteadOfReturningCurrentMonthData() {
        when(analysisService.analyze(1L))
                .thenReturn(context(List.of(candidate(10L))));

        assertThatThrownBy(() ->
                service.load(1L, YearMonth.of(2026, 11))
        )
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(
                        "현재 일정 제안은 이번 달에 대해서만 지원합니다."
                );

        verifyNoInteractions(eventService, eventRepository);
    }

    @Test
    void returnsNoNewCandidatesWhenAllSchedulesAreAlreadyPlanned() {
        when(analysisService.analyze(1L))
                .thenReturn(context(List.of(candidate(10L))));

        RepaymentPreparationEvent registered =
                new RepaymentPreparationEvent(
                        1L,
                        100L,
                        10L,
                        "계약 A 준비",
                        Instant.parse("2026-10-13T09:00:00Z"),
                        Instant.parse("2026-10-13T09:30:00Z"),
                        Instant.parse("2026-10-08T00:00:00Z")
                );

        when(eventRepository.findByUserIdAndScheduleIdIn(
                1L,
                List.of(10L)
        )).thenReturn(List.of(registered));

        when(eventService.findMonth(1L, month))
                .thenReturn(
                        List.of(PreparationEventResponse.from(registered))
                );

        var result = service.load(1L, month);

        assertThat(result.candidates()).isEmpty();
        assertThat(result.alreadyPlannedScheduleIds())
                .containsExactly(10L);
        assertThat(result.existingEvents()).hasSize(1);
    }

    private RepaymentCandidate candidate(Long scheduleId) {
        return new RepaymentCandidate(
                100L,
                scheduleId,
                "계약 A",
                LocalDate.of(2026, 10, 15),
                new BigDecimal("100000"),
                false
        );
    }

    private RepaymentAnalysisContext context(
            List<RepaymentCandidate> candidates
    ) {
        BigDecimal total = candidates.stream()
                .map(RepaymentCandidate::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new RepaymentAnalysisContext(
                LocalDate.of(2026, 10, 8),
                month,
                total,
                BigDecimal.ZERO,
                total,
                total,
                candidates
        );
    }
}