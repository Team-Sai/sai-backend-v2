package org.teamsai.saibackend.domain.calendar.dto.response;

import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record PreparationPlanningContext(
        LocalDate analysisDate,
        YearMonth targetMonth,

        // 신규 확인 알림을 제안할 회차
        List<RepaymentCandidate> candidates,

        List<PreparationEventResponse> existingEvents,
        List<Long> alreadyPlannedScheduleIds,

        // 알림 등록 여부와 관계없는 전체 관리 대상 미상환 회차
        List<RepaymentCandidate> allCandidates,

        PreparationFundingAssessment fundingAssessment,
        PreparationRepaymentHistory repaymentHistory
) {
    public PreparationPlanningContext(
            LocalDate analysisDate,
            YearMonth targetMonth,
            List<RepaymentCandidate> candidates,
            List<PreparationEventResponse> existingEvents,
            List<Long> alreadyPlannedScheduleIds
    ) {
        this(
                analysisDate,
                targetMonth,
                candidates,
                existingEvents,
                alreadyPlannedScheduleIds,
                candidates,
                null,
                null
        );
    }

    public PreparationPlanningContext(
            LocalDate analysisDate,
            YearMonth targetMonth,
            List<RepaymentCandidate> candidates,
            List<PreparationEventResponse> existingEvents,
            List<Long> alreadyPlannedScheduleIds,
            List<RepaymentCandidate> allCandidates,
            PreparationFundingAssessment fundingAssessment
    ) {
        this(
                analysisDate,
                targetMonth,
                candidates,
                existingEvents,
                alreadyPlannedScheduleIds,
                allCandidates,
                fundingAssessment,
                null
        );
    }
}