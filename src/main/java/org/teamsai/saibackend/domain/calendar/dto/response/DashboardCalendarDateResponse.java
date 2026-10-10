package org.teamsai.saibackend.domain.calendar.dto.response;

import java.time.LocalDate;
import java.util.List;

public record DashboardCalendarDateResponse(
        LocalDate date,
        List<DashboardCalendarItemResponse> items
) {
}