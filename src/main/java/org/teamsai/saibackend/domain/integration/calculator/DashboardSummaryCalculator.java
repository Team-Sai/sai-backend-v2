package org.teamsai.saibackend.domain.integration.calculator;

import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardAmountSummaryResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardMoneyBreakdownResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardMonthlySummaryResponse;
import org.teamsai.saibackend.domain.integration.model.SettlementDashboardContext;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;

public final class DashboardSummaryCalculator {
    private DashboardSummaryCalculator() {
    }

    public static DashboardAmountSummaryResponse toAmountSummary(
            ContractDashboardSummaryResponse contractSummary,
            List<SettlementDashboardContext> settlements
    ) {
        BigDecimal settlementReceivable = settlements.stream()
                .filter(SettlementDashboardContext::isOwner)
                .map(SettlementDashboardContext::roleRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal settlementPayable = settlements.stream()
                .filter(context -> !context.isOwner())
                .map(SettlementDashboardContext::roleRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal loanReceivable = zeroIfNull(contractSummary.getTotalLentAmount());
        BigDecimal loanPayable = zeroIfNull(contractSummary.getTotalBorrowedAmount());

        return DashboardAmountSummaryResponse.builder()
                .receivable(DashboardMoneyBreakdownResponse.builder()
                        .totalAmount(settlementReceivable.add(loanReceivable))
                        .settlementAmount(settlementReceivable)
                        .loanAmount(loanReceivable)
                        .build())
                .payable(DashboardMoneyBreakdownResponse.builder()
                        .totalAmount(settlementPayable.add(loanPayable))
                        .settlementAmount(settlementPayable)
                        .loanAmount(loanPayable)
                        .build())
                .build();
    }

    public static DashboardMonthlySummaryResponse toMonthlySummary(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules,
            List<SettlementDashboardContext> settlements,
            YearMonth yearMonth
    ) {
        List<ContractDashboardQueryService.LoanScheduleContext> monthlySchedules = loanSchedules.stream()
                .filter(context -> YearMonth.from(context.schedule().getDueDate()).equals(yearMonth))
                .toList();

        int completedLoanRepaymentCount = (int) monthlySchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PAID)
                .count();

        int inProgressLoanRepaymentCount = (int) monthlySchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PENDING)
                .count();

        List<SettlementDashboardContext> monthlySettlements = settlements.stream()
                .filter(context -> context.effectiveDueDate() != null)
                .filter(context -> YearMonth.from(context.effectiveDueDate()).equals(yearMonth))
                .toList();
        int completedSettlementCount = (int) monthlySettlements.stream()
                .filter(context -> context.isClosed())
                .count();
        int inProgressSettlementCount = (int) monthlySettlements.stream()
                .filter(context -> !context.isClosed())
                .count();
        int totalTransactionCount = monthlySchedules.size() + monthlySettlements.size();
        int completedTransactionCount = completedLoanRepaymentCount + completedSettlementCount;

        BigDecimal completionRate = totalTransactionCount == 0
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(completedTransactionCount)
                        .multiply(BigDecimal.valueOf(100))
                        .divide(
                                BigDecimal.valueOf(totalTransactionCount),
                                2,
                                RoundingMode.HALF_UP
                        );

        return DashboardMonthlySummaryResponse.builder()
                .completedTransactionCount(completedTransactionCount)
                .inProgressSettlementCount(inProgressSettlementCount)
                .inProgressLoanRepaymentCount(inProgressLoanRepaymentCount)
                .transactionCompletionRate(completionRate)
                .build();
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }
}
