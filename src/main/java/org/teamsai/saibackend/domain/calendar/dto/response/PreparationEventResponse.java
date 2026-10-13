package org.teamsai.saibackend.domain.calendar.dto.response;

import org.teamsai.saibackend.domain.calendar.entity.RepaymentPreparationEvent;

import java.time.Instant;

public record PreparationEventResponse(
        Long eventId,
        Long contractId,
        Long scheduleId,
        String title,
        Instant startsAt,
        Instant endsAt
) {

    public static PreparationEventResponse from(
            RepaymentPreparationEvent event
    ) {
        return new PreparationEventResponse(
                event.getEventId(),
                event.getContractId(),
                event.getScheduleId(),
                event.getTitle(),
                event.getStartsAt(),
                event.getEndsAt()
        );
    }
}