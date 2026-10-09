package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.service.*;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PreparationAgentRequestedVerificationTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final PreparationProposalRequest request = new PreparationProposalRequest(
            YearMonth.of(2026,10), Set.of(DayOfWeek.TUESDAY), LocalTime.of(18,0),
            LocalTime.of(21,0), 1, 2, "같은 시각에 모아줘", null);
    private final PreparationPlanningContext context = new PreparationPlanningContext(
            LocalDate.of(2026,10,8), YearMonth.of(2026,10), List.of(
            new RepaymentCandidate(100L,10L,"A",LocalDate.of(2026,10,15),new BigDecimal("40000"),false),
            new RepaymentCandidate(101L,11L,"B",LocalDate.of(2026,10,22),new BigDecimal("60000"),false)),
            List.of(), List.of());

    @Test
    void missingBudgetStillAllowsBothReadsAndSameTimeDraft() {
        var tools = new PreparationPlanningTools(context, request, new PreparationPlanningValidator(), clock);
        assertThat(tools.readRepaymentFacts().candidates()).hasSize(2);
        assertThat(tools.readFundingAndHistory().fundingAssessment()).isNull();
        var draft = new PreparationAgentDraft(List.of(
                new PreparationAgentDraft.Slot(10L,"2026-10-13T09:00:00Z","함께 확인"),
                new PreparationAgentDraft.Slot(11L,"2026-10-13T09:00:00Z","함께 확인")));
        var result = tools.validateReminderDraft(draft);
        assertThat(result.valid()).isTrue();
        assertThat(result.items()).hasSize(2);
        assertThat(tools.wasFinalDraftChecked(draft)).isTrue();
    }

    @Test
    void uncheckedFinalDraftBecomesExistingFailureWithoutSavingProposal() {
        verifyFailure(false);
    }

    @Test
    void toolLimitBecomesExistingFailureWithoutSavingProposal() {
        verifyFailure(true);
    }

    private void verifyFailure(boolean exceedLimit) {
        var contexts = mock(PreparationPlanningContextService.class);
        var agent = mock(PreparationPlanningAgent.class);
        var store = mock(PreparationProposalStore.class);
        when(contexts.loadForProposal(1L,request)).thenReturn(context);
        when(agent.generate(any(),any(),any(),any(),anyList())).thenAnswer(invocation -> {
            var tools = new PreparationPlanningTools(context,request,new PreparationPlanningValidator(),clock);
            if (exceedLimit) {
                for (int i=0;i<9;i++) tools.readRepaymentFacts();
            } else if (!tools.wasFinalDraftChecked(new PreparationAgentDraft(List.of()))) {
                throw new IllegalStateException("Final preparation draft was not checked through tools");
            }
            throw new AssertionError("Expected guard failure");
        });
        var service = new PreparationProposalService(
                contexts,
                agent,
                mock(PreparationRescheduleContextService.class),
                new PreparationPlanningValidator(),
                store,
                clock
        );
        var response = service.propose(1L,request);
        assertThat(response.status()).isEqualTo("AI_UNAVAILABLE");
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.items()).isEmpty();
        assertThat(response.proposalId()).isNull();
        verifyNoInteractions(store);
        verify(agent,times(1)).generate(any(),any(),any(),any(),anyList());
    }
}
