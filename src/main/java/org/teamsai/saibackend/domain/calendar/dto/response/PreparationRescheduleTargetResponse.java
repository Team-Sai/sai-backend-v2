package org.teamsai.saibackend.domain.calendar.dto.response;

import java.time.Instant;

public record PreparationRescheduleTargetResponse(
        Long eventId,
        Long scheduleId,
        Long revision,
        Instant startsAt,
        Instant endsAt
) {
}