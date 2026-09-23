package org.teamsai.saibackend.domain.integration.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.calendar.response.DashboardCalendarItemResponse;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardResponse;
import org.teamsai.saibackend.domain.contract.service.DashboardService;
import org.teamsai.saibackend.domain.integration.assembler.IntegrationDashboardAssembler;
import org.teamsai.saibackend.domain.integration.assembler.IntegrationDashboardAssembler.SettlementContext;
import org.teamsai.saibackend.domain.integration.dto.response.IntegrationDashboardResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.service.SettlementPaymentStatusService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationDashboardService {

    private final DashboardService contractDashboardService;
    private final SettlementQueryService settlementQueryService;
    private final SettlementPaymentStatusService settlementPaymentStatusService;

    public IntegrationDashboardResponse getDashboard(
            Long userId,
            YearMonth yearMonth
    ) {
        DashboardService.IntegrationDashboardData loanData =
                contractDashboardService.getIntegrationDashboardData(userId);
        DashboardResponse contractDashboard = loanData.dashboard();
        List<DashboardService.LoanScheduleContext> loanSchedules = loanData.loanSchedules();
        List<SettlementContext> settlements = getSettlements(userId);

        return IntegrationDashboardResponse.builder()
                .yearMonth(yearMonth)
                .amountSummary(IntegrationDashboardAssembler.toAmountSummary(contractDashboard.getSummary(), settlements))
                .monthlySummary(IntegrationDashboardAssembler.toMonthlySummary(loanSchedules, settlements, yearMonth))
                .attentionItems(IntegrationDashboardAssembler.toAttentionItems(loanSchedules, settlements))
                .recentTransactions(IntegrationDashboardAssembler.toRecentTransactions(contractDashboard, settlements))
                .calendarDays(IntegrationDashboardAssembler.toCalendarDays(loanSchedules, settlements, yearMonth, userId))
                .build();
    }

    public List<DashboardCalendarItemResponse> getCalendarDayDetail(Long userId, LocalDate date) {
        DashboardService.IntegrationDashboardData loanData =
                contractDashboardService.getIntegrationDashboardData(userId);
        List<DashboardService.LoanScheduleContext> loanSchedules = loanData.loanSchedules();
        List<SettlementContext> settlements = getSettlements(userId);

        return IntegrationDashboardAssembler.toCalendarDayDetail(loanSchedules, settlements, date, userId);
    }

    private List<SettlementContext> getSettlements(Long userId) {
        List<SettlementListResponse> settlements = settlementQueryService.getSettlementList(userId);
        if (settlements == null) {
            return List.of();
        }
        return settlements.stream()
                .map(settlement -> {
                    SettlementPaymentStatusResponse paymentStatus = settlementPaymentStatusService
                            .getPaymentStatus(settlement.settlementId(), userId);
                    return IntegrationDashboardAssembler.toSettlementContext(settlement, paymentStatus, userId);
                })
                .toList();
    }
}
