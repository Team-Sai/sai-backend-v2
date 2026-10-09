package org.teamsai.saibackend.domain.calendar.dto.response;

import java.util.List;

public record PreparationValidationResult(
        List<PreparationProposalItem> items,
        List<PreparationPlanningViolation> violations
) {

    public boolean valid() {
        return violations.isEmpty();
    }
}