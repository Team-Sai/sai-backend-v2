package org.teamsai.saibackend.domain.integration.reader;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.integration.model.DashboardSourceData;
import org.teamsai.saibackend.domain.integration.model.SettlementDashboardContext;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementWithPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntegrationDashboardDataReader {
    private final ContractDashboardQueryService contractDashboardQueryService;
    private final SettlementQueryService settlementQueryService;

    public DashboardSourceData read(Long userId) {
        var loanData = contractDashboardQueryService.getIntegrationDashboardData(userId);
        return new DashboardSourceData(loanData.dashboard(), loanData.loanSchedules(), getSettlements(userId));
    }

    private List<SettlementDashboardContext> getSettlements(Long userId) {
        List<SettlementWithPaymentStatusResponse> settlements =
                settlementQueryService.getSettlementListWithPaymentStatus(userId);
        if (settlements == null) {
            return List.of();
        }
        return settlements.stream()
                .map(settlement -> SettlementDashboardContext.from(
                        settlement.settlement(), settlement.paymentStatus(), userId
                ))
                .toList();
    }
}
