package org.teamsai.saibackend.domain.integration.model;

import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService.LoanScheduleContext;

import java.util.List;

public record DashboardSourceData(
        ContractDashboardResponse contractDashboard,
        List<LoanScheduleContext> loanSchedules,
        List<SettlementDashboardContext> settlements
) {
}
