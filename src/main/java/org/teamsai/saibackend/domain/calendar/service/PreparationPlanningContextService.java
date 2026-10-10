package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.calculator.PreparationFundingAssessmentCalculator;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.internal.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationEventResponse;
import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;
import org.teamsai.saibackend.domain.calendar.exception.PreparationEventErrorCode;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class PreparationPlanningContextService {

    private final RepaymentAnalysisService analysisService;
    private final RepaymentPreparationEventService eventService;
    private final RepaymentPreparationEventRepository eventRepository;
    private final PreparationFundingAssessmentCalculator fundingAssessmentService;
    private final PreparationRepaymentHistoryService historyService;

    public PreparationPlanningContextService(
            RepaymentAnalysisService analysisService,
            RepaymentPreparationEventService eventService,
            RepaymentPreparationEventRepository eventRepository,
            PreparationFundingAssessmentCalculator fundingAssessmentService,
            PreparationRepaymentHistoryService historyService
    ) {
        this.analysisService = analysisService;
        this.eventService = eventService;
        this.eventRepository = eventRepository;
        this.fundingAssessmentService = fundingAssessmentService;
        this.historyService = historyService;
    }

    public PreparationPlanningContext load(
            Long userId,
            YearMonth requestedMonth
    ) {
        if (userId == null) {
            throw PreparationEventErrorCode.UNAUTHENTICATED.toException();
        }

        if (requestedMonth == null) {
            throw PreparationEventErrorCode.INVALID_REQUEST.toException();
        }

        RepaymentAnalysisContext analysis =
                analysisService.analyze(userId);

        /*
         * 현재 상환 분석은 이번 달과 이전 달 미상환만 제공한다.
         * 다른 달을 요청했는데 이번 달 데이터를 반환하지 않도록 제한한다.
         */
        if (!requestedMonth.equals(analysis.targetMonth())) {
            throw new IllegalArgumentException(
                    "현재 일정 제안은 이번 달에 대해서만 지원합니다."
            );
        }

        List<RepaymentCandidate> repaymentCandidates =
                analysis.candidates();

        List<Long> scheduleIds = repaymentCandidates.stream()
                .map(RepaymentCandidate::scheduleId)
                .toList();

        List<RepaymentPreparationEvent> alreadyRegistered =
                scheduleIds.isEmpty()
                        ? List.of()
                        : eventRepository.findByUserIdAndScheduleIdIn(
                        userId,
                        scheduleIds
                );

        Set<Long> registeredScheduleIds = alreadyRegistered.stream()
                .map(RepaymentPreparationEvent::getScheduleId)
                .collect(Collectors.toSet());

        List<RepaymentCandidate> planningCandidates =
                repaymentCandidates.stream()
                        .filter(candidate ->
                                !registeredScheduleIds.contains(
                                        candidate.scheduleId()
                                )
                        )
                        .toList();

        List<Long> alreadyPlannedScheduleIds =
                registeredScheduleIds.stream()
                        .sorted()
                        .toList();

        List<PreparationEventResponse> existingEvents =
                eventService.findMonth(userId, requestedMonth);

        return new PreparationPlanningContext(
                analysis.analysisDate(),
                analysis.targetMonth(),
                planningCandidates,
                existingEvents,
                alreadyPlannedScheduleIds,
                repaymentCandidates,
                null
        );
    }

    public PreparationPlanningContext loadForProposal(
            Long userId,
            PreparationProposalRequest request
    ) {
        PreparationPlanningContext base =
                load(userId, request.yearMonth());

        var assessment = fundingAssessmentService.assess(
                base.allCandidates(),
                request.funding(),
                base.analysisDate(),
                base.targetMonth()
        );

        return new PreparationPlanningContext(
                base.analysisDate(),
                base.targetMonth(),
                base.candidates(),
                base.existingEvents(),
                base.alreadyPlannedScheduleIds(),
                base.allCandidates(),
                assessment,
                historyService.read(userId)
        );
    }
}