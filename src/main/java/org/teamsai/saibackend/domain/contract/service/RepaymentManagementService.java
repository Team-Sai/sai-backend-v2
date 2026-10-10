package org.teamsai.saibackend.domain.contract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.response.*;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse.AgentAnalysis;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse.PlanItem;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse.Metadata;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
public class RepaymentManagementService {

    private final RepaymentAnalysisService analysisService;
    private final RepaymentAgent repaymentAgent;
    private final RepaymentAnalysisCache analysisCache;
    private final RepaymentAnalysisCacheKey cacheKey;
    private final RepaymentGenerationCoordinator generationCoordinator;
    private final RepaymentRedisGate redisGate;
    private final Clock clock;

    public RepaymentManagementService(
            RepaymentAnalysisService analysisService,
            RepaymentAgent repaymentAgent,
            RepaymentAnalysisCache analysisCache,
            RepaymentAnalysisCacheKey cacheKey,
            RepaymentGenerationCoordinator generationCoordinator,
            RepaymentRedisGate redisGate,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.analysisService = analysisService;
        this.repaymentAgent = repaymentAgent;
        this.analysisCache = analysisCache;
        this.cacheKey = cacheKey;
        this.generationCoordinator = generationCoordinator;
        this.redisGate = redisGate;
        this.clock = clock;
    }

    public RepaymentManagementResponse getManagement(Long userId) {
        RepaymentAnalysisContext context =
                analysisService.analyze(userId);

        Instant checkedAt = clock.instant();

        log.info(
                "event=REPAYMENT_REQUEST userId={} candidates={}",
                userId,
                context.candidates().size()
        );

        if (context.candidates().isEmpty()) {
            return fallback(
                    context,
                    new Metadata(
                            null,
                            checkedAt,
                            false,
                            "EMPTY",
                            null,
                            null
                    )
            );
        }

        String key = cacheKey.create(userId, context);
        String failureScope = cacheKey.failureScope(userId);

        Optional<CachedRepaymentAnalysis> cached =
                getValidatedCachedDraft(key, context);

        if (cached.isPresent()) {
            log.info(
                    "event=REPAYMENT_CACHE_HIT key={} stage=request",
                    key
            );

            return aiResponse(
                    context,
                    cached.get(),
                    checkedAt,
                    "CACHE"
            );
        }

        log.info("event=REPAYMENT_CACHE_MISS key={}", key);

        // 이 요청의 supplier가 실행되지 않으면 다른 로컬 요청의 작업을 공유한 것.
        AtomicReference<String> delivery =
                new AtomicReference<>("SHARED");

        var pending = generationCoordinator.submit(
                key,
                failureScope,
                () -> {
                    Optional<CachedRepaymentAnalysis> secondCheck =
                            getValidatedCachedDraft(key, context);

                    if (secondCheck.isPresent()) {
                        delivery.set("CACHE");

                        log.info(
                                "event=REPAYMENT_CACHE_HIT key={} stage=before_lock",
                                key
                        );

                        return secondCheck.get();
                    }

                    if (redisGate.isCoolingDown(failureScope)) {
                        throw new RepaymentGenerationDeferredException(
                                "COOLDOWN",
                                redisGate.cooldownSeconds()
                        );
                    }

                    Optional<RepaymentRedisGate.Lease> acquired =
                            redisGate.tryAcquire(key);

                    if (acquired.isEmpty()) {
                        // 다른 서버가 저장을 막 끝냈을 가능성을 한 번 확인.
                        Optional<CachedRepaymentAnalysis> completed =
                                getValidatedCachedDraft(key, context);

                        if (completed.isPresent()) {
                            delivery.set("CACHE");

                            log.info(
                                    "event=REPAYMENT_CACHE_HIT key={} stage=lock_busy",
                                    key
                            );

                            return completed.get();
                        }

                        throw new RepaymentGenerationDeferredException(
                                "ANALYSIS_IN_PROGRESS",
                                5
                        );
                    }

                    try (RepaymentRedisGate.Lease lease = acquired.get()) {
                        // 락 획득 전 다른 서버가 생성·저장·해제를 끝냈을 수 있다.
                        Optional<CachedRepaymentAnalysis> afterLock =
                                getValidatedCachedDraft(key, context);

                        if (afterLock.isPresent()) {
                            delivery.set("CACHE");

                            log.info(
                                    "event=REPAYMENT_CACHE_HIT key={} stage=after_lock",
                                    key
                            );

                            return afterLock.get();
                        }

                        if (redisGate.isCoolingDown(failureScope)) {
                            throw new RepaymentGenerationDeferredException(
                                    "COOLDOWN",
                                    redisGate.cooldownSeconds()
                            );
                        }

                        long started = System.nanoTime();

                        log.info(
                                "event=REPAYMENT_AI_START key={}",
                                key
                        );

                        RepaymentAgentDraft draft;

                        try {
                            draft = repaymentAgent.generate(context);
                            validateAndGetReasons(context, draft);
                        } catch (RuntimeException exception) {
                            redisGate.markFailure(failureScope);

                            log.warn(
                                    "event=REPAYMENT_AI_FAILURE key={} durationMs={} exceptionType={}",
                                    key,
                                    TimeUnit.NANOSECONDS.toMillis(
                                            System.nanoTime() - started),
                                    exception.getClass().getSimpleName()
                            );

                            throw exception;
                        }

                        CachedRepaymentAnalysis result =
                                new CachedRepaymentAnalysis(
                                        draft,
                                        clock.instant()
                                );

                        if (!analysisCache.putIfOwned(key, result, lease)) {
                            throw new RepaymentGenerationDeferredException(
                                    "RESULT_NOT_SAVED"
                            );
                        }

                        delivery.set("GENERATED");

                        log.info(
                                "event=REPAYMENT_AI_SUCCESS key={} durationMs={} analyzedAt={}",
                                key,
                                TimeUnit.NANOSECONDS.toMillis(
                                        System.nanoTime() - started),
                                result.analyzedAt()
                        );

                        return result;
                    }
                }
        );

        String fallbackReason = "AI_UNAVAILABLE";
        Integer retryAfterSeconds = null;

        try {
            Optional<CachedRepaymentAnalysis> generated =
                    pending.get(25, TimeUnit.SECONDS);

            if (generated.isPresent()) {
                return aiResponse(
                        context,
                        generated.get(),
                        checkedAt,
                        delivery.get()
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fallbackReason = "INTERRUPTED";
        } catch (TimeoutException exception) {
            // 작업은 취소하지 않는다.
            // 작업이 완료되면 이후 재조회에서 캐시를 사용할 수 있다.
            fallbackReason = "WAIT_TIMEOUT";
            retryAfterSeconds = 5;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();

            if (cause instanceof
                    RepaymentGenerationDeferredException deferred) {
                fallbackReason = deferred.reason();
                retryAfterSeconds = deferred.retryAfterSeconds();
            } else {
                fallbackReason = "JOB_FAILURE";
            }
        }

        log.info(
                "event=REPAYMENT_FALLBACK key={} reason={} retryAfterSeconds={}",
                key,
                fallbackReason,
                retryAfterSeconds
        );

        return fallback(
                context,
                new Metadata(
                        null,
                        checkedAt,
                        false,
                        "FALLBACK",
                        fallbackReason,
                        retryAfterSeconds
                )
        );
    }

    private Optional<CachedRepaymentAnalysis> getValidatedCachedDraft(
            String key,
            RepaymentAnalysisContext context
    ) {
        Optional<CachedRepaymentAnalysis> cached =
                analysisCache.get(key);

        if (cached.isEmpty()) {
            return Optional.empty();
        }

        try {
            CachedRepaymentAnalysis value = cached.get();

            if (value.analyzedAt() == null) {
                throw new IllegalArgumentException(
                        "Missing analysis timestamp");
            }

            validateAndGetReasons(context, value.draft());

            return cached;
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_CACHE_VALIDATION_FAILURE key={}",
                    key
            );

            analysisCache.evict(key);
            return Optional.empty();
        }
    }

    private RepaymentManagementResponse aiResponse(
            RepaymentAnalysisContext context,
            CachedRepaymentAnalysis cached,
            Instant checkedAt,
            String delivery
    ) {
        return buildAiResponse(
                context,
                cached.draft(),
                validateAndGetReasons(context, cached.draft()),
                new Metadata(
                        cached.analyzedAt(),
                        checkedAt,
                        !"GENERATED".equals(delivery),
                        delivery,
                        null,
                        null
                )
        );
    }

    private RepaymentManagementResponse buildAiResponse(
            RepaymentAnalysisContext context,
            RepaymentAgentDraft draft,
            Map<Long, String> reasons,
            Metadata metadata
    ) {
        AgentAnalysis analysis = new AgentAnalysis(
                "AI",
                determineStatus(context),
                draft.summary(),
                createPlans(context, reasons),
                draft.recommendation()
        );

        return new RepaymentManagementResponse(
                context,
                analysis,
                metadata
        );
    }

    private Map<Long, String> validateAndGetReasons(
            RepaymentAnalysisContext context,
            RepaymentAgentDraft draft
    ) {
        if (draft == null
                || !validText(draft.summary(), 500)
                || !validText(draft.recommendation(), 1000)
                || draft.actions() == null) {
            throw new IllegalArgumentException("Invalid AI response");
        }

        Map<Long, RepaymentCandidate> expected = new HashMap<>();

        for (RepaymentCandidate candidate : context.candidates()) {
            if (candidate.scheduleId() == null
                    || expected.put(candidate.scheduleId(), candidate) != null) {
                throw new IllegalArgumentException("Invalid candidate IDs");
            }
        }

        if (draft.actions().size() != expected.size()) {
            throw new IllegalArgumentException("Missing or extra actions");
        }

        Map<Long, String> reasons = new HashMap<>();

        for (RepaymentAgentDraft.ActionExplanation action : draft.actions()) {
            if (action == null
                    || action.scheduleId() == null
                    || !expected.containsKey(action.scheduleId())
                    || !validText(action.reason(), 500)) {
                throw new IllegalArgumentException("Invalid action");
            }

            if (reasons.put(action.scheduleId(), action.reason()) != null) {
                throw new IllegalArgumentException("Duplicate action");
            }
        }

        return reasons;
    }

    private boolean validText(String text, int maxLength) {
        return text != null
                && !text.isBlank()
                && text.length() <= maxLength;
    }

    private List<PlanItem> createPlans(
            RepaymentAnalysisContext context,
            Map<Long, String> reasons
    ) {
        List<PlanItem> plans = new ArrayList<>();

        for (int index = 0; index < context.candidates().size(); index++) {
            RepaymentCandidate candidate = context.candidates().get(index);

            plans.add(new PlanItem(
                    index + 1,
                    candidate.contractId(),
                    candidate.scheduleId(),
                    candidate.contractName(),
                    candidate.dueDate(),
                    candidate.remainingAmount(),
                    candidate.pastDue(),
                    reasons.get(candidate.scheduleId())
            ));
        }

        return List.copyOf(plans);
    }

    private String determineStatus(RepaymentAnalysisContext context) {
        if (context.candidates().isEmpty()) {
            return "NO_PAYMENT_THIS_MONTH";
        }

        if (context.candidates().stream()
                .anyMatch(RepaymentCandidate::pastDue)) {
            return "PAST_DUE";
        }

        return "UPCOMING";
    }

    private RepaymentManagementResponse fallback(
            RepaymentAnalysisContext context,
            Metadata metadata
    ) {
        Map<Long, String> reasons = new HashMap<>();

        for (RepaymentCandidate candidate : context.candidates()) {
            reasons.put(
                    candidate.scheduleId(),
                    candidate.pastDue()
                            ? "납기일이 지난 미상환 회차입니다. 우선 확인하세요."
                            : "계약에 정해진 납기일까지 상환을 준비하세요."
            );
        }

        String status = determineStatus(context);

        String summary = switch (status) {
            case "PAST_DUE" -> "납기일이 지난 미상환 회차가 있습니다.";
            case "UPCOMING" -> "이번 달 남은 상환 일정이 있습니다.";
            default -> "이번 달까지 처리할 미상환 회차가 없습니다.";
        };

        String recommendation = context.candidates().isEmpty()
                ? "향후 상환 일정은 계약별 상환 스케줄에서 확인하세요."
                : "기한이 지난 회차를 먼저 확인하고 나머지는 납기순으로 준비하세요.";

        return new RepaymentManagementResponse(
                context,
                new AgentAnalysis(
                        "RULE_BASED",
                        status,
                        summary,
                        createPlans(context, reasons),
                        recommendation
                ),
                metadata
        );
    }
}