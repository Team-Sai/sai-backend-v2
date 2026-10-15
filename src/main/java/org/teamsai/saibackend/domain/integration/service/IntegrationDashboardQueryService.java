package org.teamsai.saibackend.domain.integration.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.integration.assembler.DashboardCalendarAssembler;
import org.teamsai.saibackend.domain.integration.assembler.DashboardRecentTransactionAssembler;
import org.teamsai.saibackend.domain.integration.calculator.DashboardAttentionCalculator;
import org.teamsai.saibackend.domain.integration.calculator.DashboardSummaryCalculator;
import org.teamsai.saibackend.domain.integration.dto.response.IntegrationDashboardResponse;
import org.teamsai.saibackend.domain.integration.reader.IntegrationDashboardDataReader;

import java.time.YearMonth;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationDashboardQueryService {
    private final IntegrationDashboardDataReader dataReader;

    public IntegrationDashboardResponse getDashboard(
            Long userId,
            YearMonth yearMonth
    ) {
        var data = dataReader.read(userId);
        var contractDashboard = data.contractDashboard();
        var loanSchedules = data.loanSchedules();
        var settlements = data.settlements();

        return IntegrationDashboardResponse.builder()
                .yearMonth(yearMonth)
                .amountSummary(DashboardSummaryCalculator.toAmountSummary(contractDashboard.getSummary(), settlements))
                .monthlySummary(DashboardSummaryCalculator.toMonthlySummary(loanSchedules, settlements, yearMonth))
                .attentionItems(DashboardAttentionCalculator.toAttentionItems(loanSchedules, settlements))
                .recentTransactions(DashboardRecentTransactionAssembler.toRecentTransactions(contractDashboard, settlements))
                .calendarDays(DashboardCalendarAssembler.toCalendarDays(loanSchedules, settlements, yearMonth, userId))
                .build();
    }
}
