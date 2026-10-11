package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.calculator.PreparationCoordinationFactsCalculator;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationAgentDraft;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalItemResponse;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalResponse;
import org.teamsai.saibackend.domain.calendar.service.PreparationPlanningAgent;
import org.teamsai.saibackend.domain.calendar.service.PreparationPlanningContextService;
import org.teamsai.saibackend.domain.calendar.service.PreparationProposalService;
import org.teamsai.saibackend.domain.calendar.service.PreparationProposalStore;
import org.teamsai.saibackend.domain.calendar.service.PreparationRescheduleContextService;
import org.teamsai.saibackend.domain.calendar.support.PreparationPlanningValidator;
import org.teamsai.saibackend.domain.calendar.type.PreparationProposalStatus;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PreparationProposalServiceTest {

    private static final Long USER_ID = 1L;

    private static final String PROPOSAL_ID =
            "00000000-0000-0000-0000-000000000001";

    private PreparationPlanningContextService contextService;
    private PreparationPlanningAgent agent;
    private PreparationProposalStore proposalStore;
    private PreparationProposalService service;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-08T00:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @BeforeEach
    void setUp() {
        contextService = mock(PreparationPlanningContextService.class);
        agent = mock(PreparationPlanningAgent.class);
        proposalStore = mock(PreparationProposalStore.class);

        service = new PreparationProposalService(
                contextService,
                agent,
                mock(PreparationRescheduleContextService.class),
                new PreparationPlanningValidator(),
                proposalStore,
                new PreparationCoordinationFactsCalculator(),
                clock
        );
    }

    @Test
    void skipsAiWhenNoNewCandidatesExist() {
        when(contextService.loadForProposal(
                USER_ID,
                request()
        )).thenReturn(context(List.of()));

        PreparationProposalResponse response =
                service.propose(USER_ID, request());

        assertThat(response.status())
                .isEqualTo(PreparationProposalStatus.NO_TARGET);
        assertThat(response.attempts()).isZero();
        assertThat(response.items()).isEmpty();
        assertThat(response.proposalId()).isNull();
        assertThat(response.coordinationFacts()).isNotNull();

        verifyNoInteractions(agent, proposalStore);
    }

    @Test
    void returnsValidatedProposal() {
        stubContext();
        stubProposalSave();

        when(agent.generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        )).thenReturn(draft("2026-10-13T09:00:00Z"));

        PreparationProposalResponse response =
                service.propose(USER_ID, request());

        assertThat(response.status())
                .isEqualTo(PreparationProposalStatus.READY);
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);
        assertThat(response.violations()).isEmpty();

        PreparationProposalItemResponse item = response.items().get(0);

        assertThat(item.contractId()).isEqualTo(100L);
        assertThat(item.scheduleId()).isEqualTo(10L);
        assertThat(item.remainingAmount())
                .isEqualByComparingTo("120000");
        assertThat(item.startsAt())
                .isEqualTo(Instant.parse("2026-10-13T09:00:00Z"));
        assertThat(item.endsAt())
                .isEqualTo(Instant.parse("2026-10-13T09:30:00Z"));

        assertThat(response.proposalId()).isEqualTo(PROPOSAL_ID);
        assertThat(response.expiresAt())
                .isEqualTo(clock.instant().plusSeconds(900));
        assertThat(response.coordinationFacts()).isNotNull();

        verify(proposalStore).save(
                eq(USER_ID),
                eq(request()),
                argThat(items ->
                        items.size() == 1
                                && items.get(0).scheduleId().equals(10L)
                ),
                eq(1)
        );
    }

    @Test
    void sendsValidationFeedbackAndRegenerates() {
        stubContext();
        stubProposalSave();

        // 납기 10월 15일의 2일 전은 10월 13일이다.
        PreparationAgentDraft invalid =
                draft("2026-10-14T09:00:00Z");

        PreparationAgentDraft valid =
                draft("2026-10-13T09:00:00Z");

        when(agent.generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        )).thenReturn(invalid, valid);

        PreparationProposalResponse response =
                service.propose(USER_ID, request());

        assertThat(response.status())
                .isEqualTo(PreparationProposalStatus.READY);
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(response.items()).hasSize(1);

        verify(agent).generate(
                any(),
                any(),
                any(),
                eq(invalid),
                argThat(feedback ->
                        feedback.stream().anyMatch(violation ->
                                violation.code().equals(
                                        "AFTER_PREPARATION_DEADLINE"
                                )
                        )
                )
        );

        verify(agent, times(2)).generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        );

        verify(proposalStore).save(
                eq(USER_ID),
                eq(request()),
                anyList(),
                eq(2)
        );
    }

    @Test
    void stopsAfterTwoInvalidProposals() {
        stubContext();

        when(agent.generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        )).thenReturn(draft("2026-10-14T09:00:00Z"));

        PreparationProposalResponse response =
                service.propose(USER_ID, request());

        assertThat(response.status())
                .isEqualTo(PreparationProposalStatus.REVIEW_REQUIRED);
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(response.items()).isEmpty();
        assertThat(response.proposalId()).isNull();
        assertThat(response.violations())
                .anyMatch(violation ->
                        violation.code().equals(
                                "AFTER_PREPARATION_DEADLINE"
                        )
                );

        verify(agent, times(2)).generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        );

        verifyNoInteractions(proposalStore);
    }

    @Test
    void doesNotRetryAiCallFailure() {
        stubContext();

        when(agent.generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        )).thenThrow(new IllegalStateException("AI unavailable"));

        PreparationProposalResponse response =
                service.propose(USER_ID, request());

        assertThat(response.status())
                .isEqualTo(PreparationProposalStatus.AI_UNAVAILABLE);
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.items()).isEmpty();
        assertThat(response.proposalId()).isNull();

        verify(agent, times(1)).generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        );

        verifyNoInteractions(proposalStore);
    }

    @Test
    void releasesInProgressStateAfterAiFailure() {
        stubContext();
        stubProposalSave();

        when(agent.generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        ))
                .thenThrow(new IllegalStateException("AI unavailable"))
                .thenReturn(draft("2026-10-13T09:00:00Z"));

        PreparationProposalResponse failed =
                service.propose(USER_ID, request());

        PreparationProposalResponse succeeded =
                service.propose(USER_ID, request());

        assertThat(failed.status())
                .isEqualTo(PreparationProposalStatus.AI_UNAVAILABLE);
        assertThat(succeeded.status())
                .isEqualTo(PreparationProposalStatus.READY);
        assertThat(succeeded.attempts()).isEqualTo(1);

        verify(agent, times(2)).generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        );

        verify(proposalStore, times(1)).save(
                eq(USER_ID),
                eq(request()),
                anyList(),
                eq(1)
        );
    }

    private void stubProposalSave() {
        when(proposalStore.save(
                anyLong(),
                any(),
                anyList(),
                anyInt()
        )).thenAnswer(invocation -> {
            List<PreparationProposalItemResponse> items =
                    invocation.getArgument(2);

            int attempts = invocation.getArgument(3);

            return PreparationProposalResponse.builder()
                    .status(PreparationProposalStatus.READY)
                    .proposedAt(clock.instant())
                    .attempts(attempts)
                    .message("검증된 제안입니다.")
                    .items(List.copyOf(items))
                    .violations(List.of())
                    .proposalId(PROPOSAL_ID)
                    .expiresAt(clock.instant().plusSeconds(900))
                    .build();
        });
    }

    private void stubContext() {
        RepaymentCandidate candidate = new RepaymentCandidate(
                100L,
                10L,
                "계약 A",
                LocalDate.of(2026, 10, 15),
                new BigDecimal("120000"),
                false
        );

        when(contextService.loadForProposal(
                USER_ID,
                request()
        )).thenReturn(context(List.of(candidate)));
    }

    private PreparationPlanningContext context(
            List<RepaymentCandidate> candidates
    ) {
        return new PreparationPlanningContext(
                LocalDate.of(2026, 10, 8),
                YearMonth.of(2026, 10),
                candidates,
                List.of(),
                List.of()
        );
    }

    private PreparationProposalRequest request() {
        return new PreparationProposalRequest(
                YearMonth.of(2026, 10),
                Set.of(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY
                ),
                LocalTime.of(18, 0),
                LocalTime.of(21, 0),
                30,
                2,
                "가능하면 같은 날에 모아줘.",
                null
        );
    }

    private PreparationAgentDraft draft(String startsAt) {
        return new PreparationAgentDraft(
                List.of(
                        new PreparationAgentDraft.Slot(
                                10L,
                                startsAt,
                                "납기 전에 가능한 시간으로 배치했습니다."
                        )
                )
        );
    }
}