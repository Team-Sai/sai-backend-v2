package org.teamsai.saibackend.domain.calendar.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PreparationRepaymentHistory(
        LocalDateTime recordedFromInclusive,
        LocalDateTime recordedToExclusive,
        long confirmedRecordCount,
        BigDecimal confirmedRecordedAmount,
        long recordedScheduleCount
) {
}