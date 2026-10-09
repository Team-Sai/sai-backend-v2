package org.teamsai.saibackend.domain.calendar.dto.response;

import java.time.Instant;
import java.util.List;

public record PreparationProposalResponse(
        String status,
        Instant proposedAt,
        int attempts,
        String message,
        List<PreparationProposalItem> items,
        List<PreparationPlanningViolation> violations,
        String proposalId,
        Instant expiresAt,
        PreparationFundingAssessment fundingAssessment,
        PreparationRescheduleTarget rescheduleTarget
) {
    public PreparationProposalResponse(
            String status,
            Instant proposedAt,
            int attempts,
            String message,
            List<PreparationProposalItem> items,
            List<PreparationPlanningViolation> violations,
            String proposalId,
            Instant expiresAt
    ) {
        this(
                status,
                proposedAt,
                attempts,
                message,
                items,
                violations,
                proposalId,
                expiresAt,
                null,
                null
        );
    }

    public PreparationProposalResponse(
            String status,
            Instant proposedAt,
            int attempts,
            String message,
            List<PreparationProposalItem> items,
            List<PreparationPlanningViolation> violations,
            String proposalId,
            Instant expiresAt,
            PreparationFundingAssessment fundingAssessment
    ) {
        this(
                status,
                proposedAt,
                attempts,
                message,
                items,
                violations,
                proposalId,
                expiresAt,
                fundingAssessment,
                null
        );
    }

    public PreparationProposalResponse withFundingAssessment(
            PreparationFundingAssessment assessment
    ) {
        return new PreparationProposalResponse(
                status,
                proposedAt,
                attempts,
                message,
                items,
                violations,
                proposalId,
                expiresAt,
                assessment,
                rescheduleTarget
        );
    }

    public PreparationProposalResponse withRescheduleTarget(
            PreparationRescheduleTarget target
    ) {
        return new PreparationProposalResponse(
                status,
                proposedAt,
                attempts,
                message,
                items,
                violations,
                proposalId,
                expiresAt,
                fundingAssessment,
                target
        );
    }
}