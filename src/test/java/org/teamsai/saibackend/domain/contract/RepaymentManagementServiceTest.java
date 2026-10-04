package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft.ActionExplanation;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse;
import org.teamsai.saibackend.domain.contract.service.*;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RepaymentManagementServiceTest {

    private RepaymentAnalysisService analysisService;
    private RepaymentAgent agent;
    private RepaymentManagementService service;
    private RepaymentAnalysisCache cache;
    private RepaymentAnalysisCacheKey cacheKey;

    @BeforeEach
    void setUp() {
        analysisService = mock(RepaymentAnalysisService.class);
        agent = mock(RepaymentAgent.class);
        cache = mock(RepaymentAnalysisCache.class);

        cacheKey = new RepaymentAnalysisCacheKey(
                "google-genai",
                "test-model"
        );

        // 기존 테스트들은 캐시 미스 상황에서 그대로 실행된다.
        lenient().when(cache.get(anyString()))
                .thenReturn(Optional.empty());

        RepaymentGenerationCoordinator coordinator =
                new RepaymentGenerationCoordinator(
                        Runnable::run,
                        Clock.fixed(
                                Instant.parse("2026-10-05T00:00:00Z"),
                                ZoneOffset.UTC
                        ),
                        Duration.ofSeconds(30)
                );

        service = new RepaymentManagementService(
                analysisService,
                agent,
                cache,
                cacheKey,
                coordinator
        );
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

    @Test
    void reusesAiDraftForSameContext() {
        RepaymentAnalysisContext context = context();
        RepaymentAgentDraft draft = validDraft();

        ConcurrentHashMap<String, RepaymentAgentDraft> storage =
                new ConcurrentHashMap<>();

        when(cache.get(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(
                        storage.get(invocation.getArgument(0, String.class))
                )
        );

        doAnswer(invocation -> {
            storage.put(
                    invocation.getArgument(0, String.class),
                    invocation.getArgument(1, RepaymentAgentDraft.class)
            );
            return null;
        }).when(cache).put(anyString(), any(RepaymentAgentDraft.class));

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context)).thenReturn(draft);

        RepaymentManagementResponse first = service.getManagement(1L);
        RepaymentManagementResponse second = service.getManagement(1L);

        assertThat(first.agentAnalysis().source()).isEqualTo("AI");
        assertThat(second.agentAnalysis().source()).isEqualTo("AI");

        // 금융 데이터는 매번 조회한다.
        verify(analysisService, times(2)).analyze(1L);

        // 동일 데이터의 AI 생성은 한 번이다.
        verify(agent, times(1)).generate(context);
        verify(cache, times(1))
                .put(anyString(), any(RepaymentAgentDraft.class));
    }

    @Test
    void reanalyzesAfterPartialPayment() {
        RepaymentAnalysisContext before = context();

        RepaymentAnalysisContext after = new RepaymentAnalysisContext(
                before.analysisDate(),
                before.targetMonth(),
                before.payableThisMonthAmount(),
                BigDecimal.valueOf(30000),
                BigDecimal.valueOf(450000),
                BigDecimal.valueOf(3370000),
                List.of(
                        new RepaymentCandidate(
                                10L,
                                101L,
                                "이전 달 미상환",
                                LocalDate.of(2026, 9, 25),
                                BigDecimal.valueOf(30000),
                                true
                        ),
                        before.candidates().get(1)
                )
        );

        String beforeKey = cacheKey.create(1L, before);
        String afterKey = cacheKey.create(1L, after);

        when(analysisService.analyze(1L)).thenReturn(before, after);
        when(cache.get(beforeKey))
                .thenReturn(Optional.of(validDraft()));
        when(cache.get(afterKey)).thenReturn(Optional.empty());
        when(agent.generate(after)).thenReturn(validDraft());

        service.getManagement(1L);
        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(afterKey).isNotEqualTo(beforeKey);

        assertThat(result.context().totalRequiredAmount())
                .isEqualByComparingTo("450000");

        assertThat(result.agentAnalysis().plans().get(0).amount())
                .isEqualByComparingTo("30000");

        verify(agent, never()).generate(before);
        verify(agent).generate(after);
    }

    @Test
    void reanalyzesWhenAnalysisDateChanges() {
        RepaymentAnalysisContext before = context();

        RepaymentAnalysisContext nextDay = new RepaymentAnalysisContext(
                before.analysisDate().plusDays(1),
                before.targetMonth(),
                before.payableThisMonthAmount(),
                before.overdueAmount(),
                before.totalRequiredAmount(),
                before.totalRemainingAmount(),
                before.candidates()
        );

        String beforeKey = cacheKey.create(1L, before);
        String nextKey = cacheKey.create(1L, nextDay);

        when(analysisService.analyze(1L)).thenReturn(before, nextDay);
        when(cache.get(beforeKey))
                .thenReturn(Optional.of(validDraft()));
        when(cache.get(nextKey)).thenReturn(Optional.empty());
        when(agent.generate(nextDay)).thenReturn(validDraft());

        service.getManagement(1L);
        service.getManagement(1L);

        assertThat(nextKey).isNotEqualTo(beforeKey);
        verify(agent).generate(nextDay);
        verify(agent, never()).generate(before);
    }

    @Test
    void rejectsInvalidCachedDraftAndGeneratesAgain() {
        RepaymentAnalysisContext context = context();
        String key = cacheKey.create(1L, context);

        RepaymentAgentDraft invalid = new RepaymentAgentDraft(
                "잘못된 캐시",
                List.of(
                        new ActionExplanation(999L, "없는 회차"),
                        new ActionExplanation(102L, "다가오는 회차")
                ),
                "캐시 검증 테스트"
        );

        when(analysisService.analyze(1L)).thenReturn(context);
        when(cache.get(key))
                .thenReturn(
                        Optional.of(invalid), // 첫 조회: 잘못된 캐시 존재
                        Optional.empty()     // 삭제 후 재조회: 캐시 없음
                );
        when(agent.generate(context)).thenReturn(validDraft());

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("AI");
        verify(cache).evict(key);
        verify(agent).generate(context);
        verify(cache).put(key, validDraft());
    }

    @Test
    void doesNotCacheFallback() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context))
                .thenThrow(new IllegalStateException("AI unavailable"));

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("RULE_BASED");

        verify(cache, never())
                .put(anyString(), any(RepaymentAgentDraft.class));
    }

    @Test
    void separatesCacheKeysByUserAndModel() {
        RepaymentAnalysisContext context = context();

        assertThat(cacheKey.create(1L, context))
                .isNotEqualTo(cacheKey.create(2L, context));

        RepaymentAnalysisCacheKey otherModel =
                new RepaymentAnalysisCacheKey(
                        "google-genai", "another-model");

        assertThat(cacheKey.create(1L, context))
                .isNotEqualTo(otherModel.create(1L, context));
    }

    @Test
    void skipsAiDuringCooldownButStillReadsLatestContext() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context))
                .thenThrow(new IllegalStateException("AI unavailable"));

        RepaymentManagementResponse first = service.getManagement(1L);
        RepaymentManagementResponse second = service.getManagement(1L);

        assertThat(first.agentAnalysis().source()).isEqualTo("RULE_BASED");
        assertThat(second.agentAnalysis().source()).isEqualTo("RULE_BASED");

        verify(analysisService, times(2)).analyze(1L);
        verify(agent, times(1)).generate(context);

        verify(cache, never())
                .put(anyString(), any(RepaymentAgentDraft.class));
    }

    private RepaymentAgentDraft validDraft() {
        return new RepaymentAgentDraft(
                "납기일이 지난 회차가 있습니다.",
                List.of(
                        new ActionExplanation(
                                101L, "지난 납기를 우선 확인하세요."),
                        new ActionExplanation(
                                102L, "다가오는 납기를 준비하세요.")
                ),
                "지난 회차를 확인하고 나머지 납기를 준비하세요."
        );
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