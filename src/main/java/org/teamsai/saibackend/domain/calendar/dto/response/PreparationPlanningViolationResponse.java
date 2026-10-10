package org.teamsai.saibackend.domain.calendar.dto.response;

public record PreparationPlanningViolationResponse(
        Long scheduleId,
        String code,
        String message
) {
}