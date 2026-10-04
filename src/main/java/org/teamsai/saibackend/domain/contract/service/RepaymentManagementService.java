package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse.AgentAnalysis;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentManagementResponse.PlanItem;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Service
@RequiredArgsConstructor
public class RepaymentManagementService {

    private final RepaymentAnalysisService analysisService;
    private final RepaymentAgent repaymentAgent;
    private final RepaymentAnalysisCache analysisCache;
    private final RepaymentAnalysisCacheKey cacheKey;
    private final RepaymentGenerationCoordinator generationCoordinator;

    public RepaymentManagementResponse getManagement(Long userId) {
        RepaymentAnalysisContext context = analysisService.analyze(userId);

        if (context.candidates().isEmpty()) {
            return fallback(context);
        }

        String key = cacheKey.create(userId, context);

        Optional<RepaymentAgentDraft> cached =
                getValidatedCachedDraft(key, context);

        if (cached.isPresent()) {
            log.debug("상환 분석 캐시 HIT");

            RepaymentAgentDraft draft = cached.get();

            return buildAiResponse(
                    context,
                    draft,
                    validateAndGetReasons(context, draft)
            );
        }

        var pending = generationCoordinator.submit(
                key,
                cacheKey.failureScope(userId),
                () -> {
                    // 최초 캐시 조회 이후 다른 요청이 저장했을 수 있다.
                    Optional<RepaymentAgentDraft> secondCheck =
                            getValidatedCachedDraft(key, context);

                    if (secondCheck.isPresent()) {
                        return secondCheck.get();
                    }

                    log.debug("상환 분석 캐시 MISS · AI 생성");

                    RepaymentAgentDraft draft;

                    try {
                        draft = repaymentAgent.generate(context);
                        validateAndGetReasons(context, draft);
                    } catch (RuntimeException exception) {
                        log.warn("상환 AI 분석 실패", exception);
                        throw exception;
                    }

                    analysisCache.put(key, draft);
                    return draft;
                }
        );

        Optional<RepaymentAgentDraft> generated;

        try {
            // HTTP 요청은 최대 25초까지만 결과를 기다린다.
            generated = pending.get(25, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return fallback(context);
        } catch (TimeoutException exception) {
            log.warn("상환 AI 결과 대기 시간 초과");
            return fallback(context);
        } catch (ExecutionException exception) {
            log.warn("상환 AI 작업 결과 조회 실패");
            return fallback(context);
        }

        if (generated.isEmpty()) {
            return fallback(context);
        }

        RepaymentAgentDraft draft = generated.get();

        return buildAiResponse(
                context,
                draft,
                validateAndGetReasons(context, draft)
        );
    }

    private Optional<RepaymentAgentDraft> getValidatedCachedDraft(
            String key,
            RepaymentAnalysisContext context
    ) {
        Optional<RepaymentAgentDraft> cached = analysisCache.get(key);

        if (cached.isEmpty()) {
            return Optional.empty();
        }

        try {
            validateAndGetReasons(context, cached.get());
            return cached;
        } catch (RuntimeException exception) {
            log.warn("상환 분석 캐시 검증 실패");
            analysisCache.evict(key);
            return Optional.empty();
        }
    }

    private RepaymentManagementResponse buildAiResponse(
            RepaymentAnalysisContext context,
            RepaymentAgentDraft draft,
            Map<Long, String> reasons
    ) {
        AgentAnalysis analysis = new AgentAnalysis(
                "AI",
                determineStatus(context),
                draft.summary(),
                createPlans(context, reasons),
                draft.recommendation()
        );

        return new RepaymentManagementResponse(context, analysis);
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
            RepaymentAnalysisContext context
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
                )
        );
    }
}