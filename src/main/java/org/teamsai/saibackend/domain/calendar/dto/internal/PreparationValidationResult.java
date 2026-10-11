package org.teamsai.saibackend.domain.calendar.dto.internal;

import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningViolationResponse;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalItemResponse;

import java.util.List;

public record PreparationValidationResult(
        List<PreparationProposalItemResponse> items,
        List<PreparationPlanningViolationResponse> violations
) {

    public boolean valid() {
        return violations.isEmpty();
    }
}