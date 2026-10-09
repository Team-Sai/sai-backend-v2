package org.teamsai.saibackend.domain.calendar.dto.response;

public record PreparationPlanningViolation(
        Long scheduleId,
        String code,
        String message
) {
}