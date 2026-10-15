package org.teamsai.saibackend.domain.integration.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.response.DashboardCalendarItemResponse;
import org.teamsai.saibackend.domain.integration.assembler.DashboardCalendarAssembler;
import org.teamsai.saibackend.domain.integration.reader.IntegrationDashboardDataReader;

import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationCalendarQueryService {
    private final IntegrationDashboardDataReader dataReader;

    public List<DashboardCalendarItemResponse> getCalendarDayDetail(Long userId, LocalDate date) {
        var data = dataReader.read(userId);
        return DashboardCalendarAssembler.toCalendarDayDetail(data.loanSchedules(), data.settlements(), date, userId);
    }
}
