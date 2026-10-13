package org.teamsai.saibackend.domain.calendar.dto.response;

import java.time.Instant;
import java.util.List;

public record PreparationConfirmationResponse(
        String proposalId,
        Instant confirmedAt,
        boolean reused,
        List<PreparationEventResponse> events
) {
}