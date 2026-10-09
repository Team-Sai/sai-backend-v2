package org.teamsai.saibackend.domain.calendar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.*;
import org.teamsai.saibackend.domain.calendar.exception.PreparationEventErrorCode;

import java.time.Clock;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class PreparationProposalService {

    /*
     * 최초 제안 1회 + 검증 실패 시 재조율 1회.
     * 네트워크 장애에 대한 자동 재시도 횟수가 아니다.
     */
    private static final int MAX_ATTEMPTS = 2;

    private final PreparationPlanningContextService contextService;
    private final PreparationPlanningAgent agent;
    private final PreparationRescheduleContextService rescheduleContexts;
    private final PreparationPlanningValidator validator;
    private final PreparationProposalStore proposalStore;
    private final Clock clock;

    /*
     * 현재 서버 프로세스 안에서 같은 사용자의 중복 실행을 제한한다.
     * 다중 서버의 분산 실행 제한은 별도 적용이 필요하다.
     */
    private final ConcurrentHashMap<Long, Boolean> inProgress =
            new ConcurrentHashMap<>();

    public PreparationProposalService(
            PreparationPlanningContextService contextService,
            PreparationPlanningAgent agent,
            PreparationRescheduleContextService rescheduleContexts,
            PreparationPlanningValidator validator,
            PreparationProposalStore proposalStore,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.contextService = contextService;
        this.agent = agent;
        this.rescheduleContexts = rescheduleContexts;
        this.validator = validator;
        this.proposalStore = proposalStore;
        this.clock = clock;
    }

    public PreparationProposalResponse propose(
            Long userId,
            PreparationProposalRequest request
    ) {
        return proposeInternal(userId, request, null);
    }

    public PreparationProposalResponse proposeReschedule(
            Long userId,
            Long eventId,
            PreparationProposalRequest request
    ) {
        if (eventId == null || eventId <= 0) {
            throw PreparationEventErrorCode
                    .INVALID_REQUEST
                    .toException();
        }

        return proposeInternal(userId, request, eventId);
    }

    private PreparationProposalResponse proposeInternal(
            Long userId,
            PreparationProposalRequest request,
            Long eventId
    ) {
        if (userId == null) {
            throw PreparationEventErrorCode
                    .UNAUTHENTICATED
                    .toException();
        }

        validator.validateRequest(request);

        if (inProgress.putIfAbsent(userId, Boolean.TRUE) != null) {
            return response(
                    "IN_PROGRESS",
                    0,
                    "이미 일정 제안을 생성하고 있습니다.",
                    List.of(),
                    List.of()
            );
        }

        try {
            return generate(userId, request, eventId);
        } finally {
            inProgress.remove(userId);
        }
    }

    private PreparationProposalResponse generate(
            Long userId,
            PreparationProposalRequest request,
            Long eventId
    ) {
        var plan = eventId == null
                ? null
                : rescheduleContexts.load(userId, eventId, request);

        PreparationRescheduleTarget target =
                plan == null ? null : plan.target();

        PreparationPlanningContext context =
                plan == null
                        ? contextService.loadForProposal(userId, request)
                        : plan.context();

        if (context.candidates().isEmpty()) {
            return response(
                    "NO_TARGET",
                    0,
                    "신규 확인 알림이 필요한 미상환 회차가 없습니다.",
                    List.of(),
                    List.of()
            ).withFundingAssessment(context.fundingAssessment());
        }

        if (context.candidates().size() > 20) {
            throw new IllegalArgumentException(
                    "이번 버전은 한 번에 최대 20개 회차까지 제안합니다."
            );
        }

        PreparationAgentDraft previousDraft = null;

        List<PreparationPlanningViolation> feedback =
                List.of();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            PreparationAgentDraft draft;

            log.info(
                    "event=PREPARATION_AI_START attempt={}",
                    attempt
            );

            try {
                draft = agent.generate(
                        context,
                        request,
                        clock.instant(),
                        previousDraft,
                        feedback
                );
            } catch (RuntimeException exception) {
                /*
                 * 호출 실패는 다시 호출하지 않는다.
                 * 전체 요청 내용과 개인정보는 로그에 남기지 않는다.
                 */
                log.warn(
                        "event=PREPARATION_AI_FAILURE attempt={} exceptionType={}",
                        attempt,
                        exception.getClass().getSimpleName(),
                        exception
                );

                return response(
                        "AI_UNAVAILABLE",
                        attempt,
                        "일정 제안을 생성하지 못했습니다. 잠시 후 다시 시도하세요.",
                        List.of(),
                        List.of()
                ).withFundingAssessment(context.fundingAssessment());
            }

            PreparationValidationResult validation =
                    validator.validate(
                            context,
                            request,
                            draft,
                            clock.instant()
                    );

            if (validation.valid()) {
                if (target != null
                        && validation.items().get(0).startsAt()
                        .equals(target.startsAt())) {
                    previousDraft = draft;

                    feedback = List.of(
                            new PreparationPlanningViolation(
                                    target.scheduleId(),
                                    "UNCHANGED_TIME",
                                    "기존 시각과 다른 확인 시각을 제안하세요."
                            )
                    );

                    continue;
                }

                log.info(
                        "event=PREPARATION_PROPOSAL_READY attempt={} itemCount={}",
                        attempt,
                        validation.items().size()
                );

                PreparationProposalResponse saved =
                        target == null
                                ? proposalStore.save(
                                userId,
                                request,
                                validation.items(),
                                attempt
                        )
                                : proposalStore.save(
                                userId,
                                request,
                                validation.items(),
                                attempt,
                                target
                        );

                return saved.withFundingAssessment(
                        context.fundingAssessment()
                );
            }

            log.info(
                    "event=PREPARATION_VALIDATION_REJECTED attempt={} violationCount={}",
                    attempt,
                    validation.violations().size()
            );

            previousDraft = draft;
            feedback = validation.violations();
        }

        return response(
                "REVIEW_REQUIRED",
                MAX_ATTEMPTS,
                "검증을 통과한 제안을 얻지 못했습니다. 조건을 조정하거나 다시 시도하세요.",
                List.of(),
                feedback
        ).withFundingAssessment(context.fundingAssessment());
    }

    private PreparationProposalResponse response(
            String status,
            int attempts,
            String message,
            List<PreparationProposalItem> items,
            List<PreparationPlanningViolation> violations
    ) {
        return new PreparationProposalResponse(
                status,
                clock.instant(),
                attempts,
                message,
                items,
                violations,
                null,
                null
        );
    }
}