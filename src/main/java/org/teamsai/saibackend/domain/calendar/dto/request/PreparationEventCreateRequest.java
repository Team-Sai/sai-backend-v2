package org.teamsai.saibackend.domain.calendar.dto.request;

import java.time.Instant;

public record PreparationEventCreateRequest(
        Long contractId,
        Long scheduleId,
        Instant startsAt,
        Instant endsAt
) {
}