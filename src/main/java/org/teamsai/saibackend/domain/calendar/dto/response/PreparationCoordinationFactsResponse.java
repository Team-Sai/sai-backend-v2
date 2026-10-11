package org.teamsai.saibackend.domain.calendar.dto.response;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;

public record PreparationCoordinationFactsResponse(
        int outstandingScheduleCount,
        int existingPreparationEventCount,
        int targetScheduleCount,
        int proposedScheduleCount,
        int sharedTimeGroupCount,
        int groupedScheduleCount,
        List<DayOfWeek> allowedDays,
        LocalTime windowStart,
        LocalTime windowEnd,
        int leadDays,
        String requestedPreferences,
        boolean reschedule
) {
}