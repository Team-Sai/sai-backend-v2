package org.teamsai.saibackend.domain.calendar.dto.internal;

import java.util.List;

public record PreparationAgentDraft(
        List<Slot> slots
) {

    public record Slot(
            Long scheduleId,
            String startsAt,
            String reason
    ) {
    }
}