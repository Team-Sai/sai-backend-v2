package org.teamsai.saibackend.domain.integration.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.dto.response.DashboardCalendarDateResponse;
import org.teamsai.saibackend.domain.calendar.dto.response.DashboardCalendarItemResponse;
import org.teamsai.saibackend.domain.integration.assembler.DashboardCalendarAssembler;
import org.teamsai.saibackend.domain.integration.reader.IntegrationDashboardDataReader;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationCalendarQueryService {
    private final IntegrationDashboardDataReader dataReader;

    public List<DashboardCalendarItemResponse> getCalendarDayDetail(Long userId, LocalDate date) {
        var data = dataReader.read(userId);
        return DashboardCalendarAssembler.toCalendarDayDetail(data.loanSchedules(), data.settlements(), date, userId);
    }

    public List<DashboardCalendarDateResponse> getCalendarMonthDetail(
            Long userId,
            YearMonth yearMonth
    ) {
        var data = dataReader.read(userId);

        return IntStream.rangeClosed(1, yearMonth.lengthOfMonth())
                .mapToObj(day -> {
                    LocalDate date = yearMonth.atDay(day);

                    var items = DashboardCalendarAssembler.toCalendarDayDetail(
                            data.loanSchedules(),
                            data.settlements(),
                            date,
                            userId
                    );

                    return new DashboardCalendarDateResponse(date, items);
                })
                .filter(day -> !day.items().isEmpty())
                .toList();
    }
}
