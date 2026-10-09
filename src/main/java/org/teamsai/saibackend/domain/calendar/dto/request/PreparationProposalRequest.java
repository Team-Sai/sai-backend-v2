package org.teamsai.saibackend.domain.calendar.dto.request;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Set;

public record PreparationProposalRequest(
        YearMonth yearMonth,
        Set<DayOfWeek> allowedDays,
        LocalTime windowStart,
        LocalTime windowEnd,
        Integer durationMinutes,
        Integer leadDays,
        String preferences,
        PreparationFundingRequest funding
) {
}