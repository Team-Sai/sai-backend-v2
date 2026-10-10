package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft.ActionExplanation;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;
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
    private RepaymentRedisGate redisGate;
    private RepaymentRedisGate.Lease lease;
    private Clock clock;

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

        clock = Clock.fixed(
                Instant.parse("2026-10-05T00:00:00Z"),
                ZoneOffset.UTC
        );

        redisGate = mock(RepaymentRedisGate.class);
        lease = mock(RepaymentRedisGate.Lease.class);

        lenient().when(redisGate.cooldownSeconds())
                .thenReturn(30);
        
        lenient().when(redisGate.tryAcquire(anyString()))
                .thenReturn(Optional.of(lease));

        lenient().when(cache.putIfOwned(
                anyString(),
                any(CachedRepaymentAnalysis.class),
                any(RepaymentRedisGate.Lease.class)
        )).thenReturn(true);

        RepaymentGenerationCoordinator coordinator =
                new RepaymentGenerationCoordinator(
                        Runnable::run,
                        clock,
                        Duration.ofSeconds(30)
                );

        service = new RepaymentManagementService(
                analysisService,
                agent,
                cache,
                cacheKey,
                coordinator,
                redisGate,
                clock
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

        ConcurrentHashMap<String, CachedRepaymentAnalysis> storage =
                new ConcurrentHashMap<>();

        when(cache.get(anyString())).thenAnswer(invocation ->
                Optional.ofNullable(
                        storage.get(
                                invocation.getArgument(0, String.class)
                        )
                )
        );

        when(cache.putIfOwned(
                anyString(),
                any(CachedRepaymentAnalysis.class),
                any(RepaymentRedisGate.Lease.class)
        )).thenAnswer(invocation -> {
            storage.put(
                    invocation.getArgument(0, String.class),
                    invocation.getArgument(
                            1, CachedRepaymentAnalysis.class)
            );

            return true;
        });

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context)).thenReturn(validDraft());

        RepaymentManagementResponse first =
                service.getManagement(1L);

        RepaymentManagementResponse second =
                service.getManagement(1L);

        assertThat(first.metadata().delivery())
                .isEqualTo("GENERATED");
        assertThat(first.metadata().reused()).isFalse();

        assertThat(second.metadata().delivery())
                .isEqualTo("CACHE");
        assertThat(second.metadata().reused()).isTrue();

        assertThat(second.metadata().analyzedAt())
                .isEqualTo(first.metadata().analyzedAt());

        assertThat(first.metadata().fallbackReason()).isNull();
        assertThat(first.metadata().retryAfterSeconds()).isNull();

        assertThat(second.metadata().fallbackReason()).isNull();
        assertThat(second.metadata().retryAfterSeconds()).isNull();

        verify(analysisService, times(2)).analyze(1L);
        verify(agent, times(1)).generate(context);
        verify(redisGate, times(1)).tryAcquire(anyString());
        verify(lease).close();
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
                .thenReturn(Optional.of(cached(validDraft())));
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
                .thenReturn(Optional.of(cached(validDraft())));
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
        when(cache.get(key)).thenReturn(
                Optional.of(cached(invalid)),
                Optional.empty()
        );
        when(agent.generate(context)).thenReturn(validDraft());

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("AI");
        verify(cache).evict(key);
        verify(agent).generate(context);
        verify(cache).putIfOwned(
                eq(key),
                eq(cached(validDraft())),
                same(lease)
        );
    }

    @Test
    void doesNotCacheFallback() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context))
                .thenThrow(new IllegalStateException("AI unavailable"));

        RepaymentManagementResponse result = service.getManagement(1L);

        assertThat(result.agentAnalysis().source()).isEqualTo("RULE_BASED");

        verify(cache, never()).putIfOwned(
                anyString(),
                any(CachedRepaymentAnalysis.class),
                any(RepaymentRedisGate.Lease.class)
        );
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
        assertThat(first.metadata().fallbackReason())
                .isEqualTo("AI_UNAVAILABLE");

        assertThat(second.metadata().fallbackReason())
                .isEqualTo("COOLDOWN");

        verify(analysisService, times(2)).analyze(1L);
        verify(agent, times(1)).generate(context);

        verify(cache, never()).putIfOwned(
                anyString(),
                any(CachedRepaymentAnalysis.class),
                any(RepaymentRedisGate.Lease.class)
        );
    }

    @Test
    void doesNotCallAiWhenAnotherServerOwnsLock() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(redisGate.tryAcquire(anyString()))
                .thenReturn(Optional.empty());

        RepaymentManagementResponse result =
                service.getManagement(1L);

        assertThat(result.agentAnalysis().source())
                .isEqualTo("RULE_BASED");
        assertThat(result.metadata().delivery())
                .isEqualTo("FALLBACK");
        assertThat(result.metadata().fallbackReason())
                .isEqualTo("ANALYSIS_IN_PROGRESS");

        assertThat(result.metadata().retryAfterSeconds())
                .isEqualTo(5);
        assertThat(result.metadata().analyzedAt()).isNull();
        assertThat(result.metadata().reused()).isFalse();

        verifyNoInteractions(agent);
        verify(redisGate, never()).markFailure(anyString());
    }

    @Test
    void rechecksCacheAfterAcquiringLock() {
        RepaymentAnalysisContext context = context();
        String key = cacheKey.create(1L, context);

        CachedRepaymentAnalysis existing =
                new CachedRepaymentAnalysis(
                        validDraft(),
                        Instant.parse("2026-10-04T23:00:00Z")
                );

        when(analysisService.analyze(1L)).thenReturn(context);

        when(cache.get(key)).thenReturn(
                Optional.empty(),       // 요청 시작
                Optional.empty(),       // 로컬 supplier 시작
                Optional.of(existing)   // 락 획득 직후
        );

        RepaymentManagementResponse result =
                service.getManagement(1L);

        assertThat(result.metadata().delivery())
                .isEqualTo("CACHE");
        assertThat(result.metadata().reused()).isTrue();
        assertThat(result.metadata().analyzedAt())
                .isEqualTo(existing.analyzedAt());
        assertThat(result.metadata().checkedAt())
                .isEqualTo(clock.instant());

        verifyNoInteractions(agent);
        verify(lease).close();
    }

    @Test
    void skipsAiDuringDistributedCooldown() {
        when(analysisService.analyze(1L))
                .thenReturn(context());

        when(redisGate.isCoolingDown(anyString()))
                .thenReturn(true);

        RepaymentManagementResponse result =
                service.getManagement(1L);

        assertThat(result.agentAnalysis().source())
                .isEqualTo("RULE_BASED");
        assertThat(result.metadata().fallbackReason())
                .isEqualTo("COOLDOWN");

        assertThat(result.metadata().retryAfterSeconds())
                .isEqualTo(30);

        verifyNoInteractions(agent);
        verify(redisGate, never()).tryAcquire(anyString());
    }

    @Test
    void doesNotCallAiWhenRedisCoordinationIsUnavailable() {
        when(analysisService.analyze(1L))
                .thenReturn(context());

        when(redisGate.isCoolingDown(anyString()))
                .thenThrow(
                        new RepaymentGenerationDeferredException(
                                "REDIS_UNAVAILABLE")
                );

        RepaymentManagementResponse result =
                service.getManagement(1L);

        assertThat(result.agentAnalysis().source())
                .isEqualTo("RULE_BASED");
        assertThat(result.metadata().fallbackReason())
                .isEqualTo("REDIS_UNAVAILABLE");

        assertThat(result.metadata().retryAfterSeconds()).isNull();

        verifyNoInteractions(agent);
    }

    @Test
    void discardsResultWhenOwnershipCheckedSaveFails() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context)).thenReturn(validDraft());

        when(cache.putIfOwned(
                anyString(),
                any(CachedRepaymentAnalysis.class),
                any(RepaymentRedisGate.Lease.class)
        )).thenReturn(false);

        RepaymentManagementResponse result =
                service.getManagement(1L);

        assertThat(result.agentAnalysis().source())
                .isEqualTo("RULE_BASED");
        assertThat(result.metadata().fallbackReason())
                .isEqualTo("RESULT_NOT_SAVED");
        assertThat(result.metadata().analyzedAt()).isNull();

        verify(agent).generate(context);
        verify(lease).close();
    }

    @Test
    void recordsDistributedCooldownAndReleasesLockOnAiFailure() {
        RepaymentAnalysisContext context = context();

        when(analysisService.analyze(1L)).thenReturn(context);
        when(agent.generate(context))
                .thenThrow(new IllegalStateException("AI unavailable"));

        RepaymentManagementResponse result =
                service.getManagement(1L);

        assertThat(result.agentAnalysis().source())
                .isEqualTo("RULE_BASED");
        assertThat(result.metadata().fallbackReason())
                .isEqualTo("AI_UNAVAILABLE");

        verify(redisGate).markFailure(
                cacheKey.failureScope(1L)
        );
        verify(lease).close();
    }

    private CachedRepaymentAnalysis cached(
            RepaymentAgentDraft draft
    ) {
        return new CachedRepaymentAnalysis(
                draft,
                clock.instant()
        );
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