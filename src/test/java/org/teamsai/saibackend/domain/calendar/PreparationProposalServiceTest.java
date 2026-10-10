package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.service.*;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreparationProposalServiceTest {

    private PreparationPlanningContextService contextService;
    private PreparationPlanningAgent agent;

    private PreparationProposalService service;
    private PreparationProposalStore proposalStore;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-10-08T00:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    @BeforeEach
    void setUp() {
        contextService =
                mock(PreparationPlanningContextService.class);

        agent = mock(PreparationPlanningAgent.class);

        proposalStore = mock(PreparationProposalStore.class);

        service = new PreparationProposalService(
                contextService,
                agent,
                mock(PreparationRescheduleContextService.class),
                new PreparationPlanningValidator(),
                proposalStore,
                new PreparationCoordinationFactsService(),
                clock
        );

        when(proposalStore.save(
                anyLong(),
                any(),
                anyList(),
                anyInt()
        )).thenAnswer(invocation -> {
            List<PreparationProposalItem> items =
                    invocation.getArgument(2);

            int attempts = invocation.getArgument(3);

            return new PreparationProposalResponse(
                    "READY",
                    clock.instant(),
                    attempts,
                    "검증된 제안입니다.",
                    items,
                    List.of(),
                    "00000000-0000-0000-0000-000000000001",
                    clock.instant().plusSeconds(900)
            );
        });
    }

    @Test
    void skipsAiWhenNoNewCandidatesExist() {
        when(contextService.loadForProposal(
                1L,
                request()
        )).thenReturn(context(List.of()));

        var response = service.propose(1L, request());

        assertThat(response.status()).isEqualTo("NO_TARGET");
        assertThat(response.attempts()).isZero();

        verifyNoInteractions(agent);
    }

    @Test
    void returnsValidatedProposal() {
        stubContext();

        when(agent.generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        )).thenReturn(draft("2026-10-13T09:00:00Z"));

        var response = service.propose(1L, request());

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.items()).hasSize(1);

        var item = response.items().get(0);

        assertThat(item.contractId()).isEqualTo(100L);
        assertThat(item.remainingAmount())
                .isEqualByComparingTo("120000");

        assertThat(item.endsAt())
                .isEqualTo(
                        Instant.parse("2026-10-13T09:30:00Z")
                );
        assertThat(response.proposalId()).isNotBlank();
        assertThat(response.expiresAt()).isAfter(response.proposedAt());
    }

    @Test
    void sendsValidationFeedbackAndRegenerates() {
        stubContext();

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

        var response = service.propose(1L, request());

        assertThat(response.status()).isEqualTo("READY");
        assertThat(response.attempts()).isEqualTo(2);

        verify(agent).generate(
                any(),
                any(),
                any(),
                eq(invalid),
                argThat(feedback ->
                        feedback.stream().anyMatch(
                                violation ->
                                        violation.code().equals(
                                                "AFTER_PREPARATION_DEADLINE"
                                        )
                        )
                )
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
        )).thenReturn(
                draft("2026-10-14T09:00:00Z")
        );

        var response = service.propose(1L, request());

        assertThat(response.status())
                .isEqualTo("REVIEW_REQUIRED");

        assertThat(response.items()).isEmpty();
        assertThat(response.violations()).isNotEmpty();

        verify(agent, times(2)).generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        );
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
        )).thenThrow(
                new IllegalStateException("AI unavailable")
        );

        var response = service.propose(1L, request());

        assertThat(response.status())
                .isEqualTo("AI_UNAVAILABLE");

        assertThat(response.items()).isEmpty();

        verify(agent, times(1)).generate(
                any(),
                any(),
                any(),
                any(),
                anyList()
        );
    }

    private void stubContext() {
        RepaymentCandidate candidate =
                new RepaymentCandidate(
                        100L,
                        10L,
                        "계약 A",
                        LocalDate.of(2026, 10, 15),
                        new BigDecimal("120000"),
                        false
                );

        when(contextService.loadForProposal(
                1L,
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