package org.teamsai.saibackend.domain.calendar.dto.response;

import lombok.Builder;
import org.teamsai.saibackend.domain.calendar.type.PreparationProposalStatus;

import java.time.Instant;
import java.util.List;

@Builder(toBuilder = true)
public record PreparationProposalResponse(
        PreparationProposalStatus status,
        Instant proposedAt,
        int attempts,
        String message,
        List<PreparationProposalItemResponse> items,
        List<PreparationPlanningViolationResponse> violations,
        String proposalId,
        Instant expiresAt,
        PreparationFundingAssessmentResponse fundingAssessment,
        PreparationRescheduleTargetResponse rescheduleTarget,
        PreparationCoordinationFactsResponse coordinationFacts
) {
    public PreparationProposalResponse withFundingAssessment(
            PreparationFundingAssessmentResponse assessment
    ) {
        return toBuilder()
                .fundingAssessment(assessment)
                .build();
    }

    public PreparationProposalResponse withRescheduleTarget(
            PreparationRescheduleTargetResponse target
    ) {
        return toBuilder()
                .rescheduleTarget(target)
                .build();
    }

    public PreparationProposalResponse withCoordinationFacts(
            PreparationCoordinationFactsResponse facts
    ) {
        return toBuilder()
                .coordinationFacts(facts)
                .build();
    }
}