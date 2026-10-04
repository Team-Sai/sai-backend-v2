package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft.ActionExplanation;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.contract.service.RepaymentAgent;
import org.teamsai.saibackend.domain.contract.service.RepaymentManagementService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RepaymentManagementServiceTest {

    private RepaymentAnalysisService analysisService;
    private RepaymentAgent agent;
    private RepaymentManagementService service;

    @BeforeEach
    void setUp() {
        analysisService = mock(RepaymentAnalysisService.class);
        agent = mock(RepaymentAgent.class);

        service = new RepaymentManagementService(analysisService, agent);
    }

    @Test
    void usesValidatedAiReasonsButKeepsServerAmountsDatesAndOrder() {
        RepaymentAnalysisContext context = context();

        // AI가 역순으로 반환해도 표시 순서는 서버 후보 순서를 유지한다.
        RepaymentAgentDraft draft = new RepaymentAgentDraft(
                "기한이 지난 회차가 있습니다.",
                List.of(
                        new ActionExplanation(102L, "다가오는 납기를 준비하세요."),
                        new ActionExplanation(101L, "지난 납기를 우선 확인하세요.")
                ),
                "지난 회차를 확인하고 나머지 납기를 준비하세요."
        );

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context)).thenReturn(draft);

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.context()).isSameAs(context);
        assertThat(result.agentAnalysis().source()).isEqualTo("AI");
        assertThat(result.agentAnalysis().status()).isEqualTo("PAST_DUE");

        assertThat(result.agentAnalysis().plans())
                .extracting(RepaymentManagementResponse.PlanItem::scheduleId)
                .containsExactly(101L, 102L);

        assertThat(result.agentAnalysis().plans().get(0).amount())
                .isEqualByComparingTo("80000");

        assertThat(result.agentAnalysis().plans().get(0).dueDate())
                .isEqualTo(LocalDate.of(2026, 9, 25));

        assertThat(result.agentAnalysis().plans().get(0).reason())
                .isEqualTo("지난 납기를 우선 확인하세요。".replace("。", "."));

        assertThat(result.agentAnalysis().plans().get(1).amount())
                .isEqualByComparingTo("420000");
    }

    @Test
    void fallsBackWhenAiCallFails() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context))
                .thenThrow(new IllegalStateException("AI unavailable"));

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("RULE_BASED");
        assertThat(result.agentAnalysis().status()).isEqualTo("PAST_DUE");
        assertThat(result.agentAnalysis().plans()).hasSize(2);
        assertThat(result.context().totalRequiredAmount())
                .isEqualByComparingTo("500000");
    }

    @Test
    void rejectsUnknownScheduleId() {
        assertRejected(List.of(
                new ActionExplanation(101L, "연체 회차를 확인하세요."),
                new ActionExplanation(999L, "잘못된 회차입니다.")
        ));
    }

    @Test
    void rejectsDuplicateScheduleId() {
        assertRejected(List.of(
                new ActionExplanation(101L, "첫 번째 설명"),
                new ActionExplanation(101L, "중복된 설명")
        ));
    }

    @Test
    void rejectsMissingCandidate() {
        assertRejected(List.of(
                new ActionExplanation(101L, "하나만 반환한 설명")
        ));
    }

    @Test
    void rejectsBlankReason() {
        assertRejected(List.of(
                new ActionExplanation(101L, " "),
                new ActionExplanation(102L, "다가오는 회차입니다.")
        ));
    }

    @Test
    void skipsAiWhenNoCandidatesExist() {
        RepaymentAnalysisContext empty = new RepaymentAnalysisContext(
                LocalDate.of(2026, 10, 4),
                YearMonth.of(2026, 10),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.valueOf(300000),
                List.of()
        );

        when(analysisService.analyze(1L)).thenReturn(empty);

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("RULE_BASED");
        assertThat(result.agentAnalysis().status())
                .isEqualTo("NO_PAYMENT_THIS_MONTH");
        assertThat(result.agentAnalysis().plans()).isEmpty();

        verifyNoInteractions(agent);
    }

    private void assertRejected(List<ActionExplanation> actions) {
        RepaymentAnalysisContext context = context();

        RepaymentAgentDraft draft = new RepaymentAgentDraft(
                "상환 안내",
                actions,
                "납기순으로 준비하세요."
        );

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context)).thenReturn(draft);

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("RULE_BASED");

        assertThat(result.agentAnalysis().plans())
                .extracting(RepaymentManagementResponse.PlanItem::scheduleId)
                .containsExactly(101L, 102L);
    }

    private RepaymentAnalysisContext context() {
        return new RepaymentAnalysisContext(
                LocalDate.of(2026, 10, 4),
                YearMonth.of(2026, 10),
                BigDecimal.valueOf(420000),
                BigDecimal.valueOf(80000),
                BigDecimal.valueOf(500000),
                BigDecimal.valueOf(3420000),
                List.of(
                        new RepaymentCandidate(
                                10L,
                                101L,
                                "이전 달 미상환",
                                LocalDate.of(2026, 9, 25),
                                BigDecimal.valueOf(80000),
                                true
                        ),
                        new RepaymentCandidate(
                                11L,
                                102L,
                                "이번 달 상환",
                                LocalDate.of(2026, 10, 15),
                                BigDecimal.valueOf(420000),
                                false
                        )
                )
        );
    }
}