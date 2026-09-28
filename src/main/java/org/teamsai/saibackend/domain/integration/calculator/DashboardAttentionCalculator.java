package org.teamsai.saibackend.domain.integration.calculator;

import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardAttentionItemResponse;
import org.teamsai.saibackend.domain.integration.model.SettlementDashboardContext;
import org.teamsai.saibackend.domain.integration.type.DashboardAttentionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DashboardAttentionCalculator {
    private DashboardAttentionCalculator() {
    }

    public static List<DashboardAttentionItemResponse> toAttentionItems(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules,
            List<SettlementDashboardContext> settlements
    ) {
        LocalDate today = LocalDate.now();
        LocalDate attentionLimit = today.plusDays(3);
        List<DashboardAttentionItemResponse> items = new ArrayList<>(
                upcomingLoanAttentionItems(loanSchedules)
        );
        settlements.stream()
                .filter(context -> !context.isClosed())
                .filter(context -> context.isOwner()
                        || context.roleRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(context -> context.effectiveDueDate() != null)
                .filter(context -> !context.effectiveDueDate().isBefore(today))
                .filter(context -> !context.effectiveDueDate().isAfter(attentionLimit))
                .map(context -> DashboardAttentionItemResponse.builder()
                        .id(context.settlement().settlementId())
                        .type(DashboardAttentionType.SETTLEMENT_DUE_SOON)
                        .remainingDays(ChronoUnit.DAYS.between(today, context.effectiveDueDate()))
                        .actionUrl("/settlements/" + context.settlement().settlementId())
                        .build())
                .forEach(items::add);
        return items.stream()
                .sorted(Comparator.comparing(DashboardAttentionItemResponse::getRemainingDays))
                .toList();
    }

    private static List<DashboardAttentionItemResponse> upcomingLoanAttentionItems(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules
    ) {
        LocalDate today = LocalDate.now();
        LocalDate attentionLimit = today.plusDays(3);

        return loanSchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PENDING)
                .filter(context -> !context.schedule().getDueDate().isBefore(today))
                .filter(context -> !context.schedule().getDueDate().isAfter(attentionLimit))
                .sorted(Comparator.comparing(context -> context.schedule().getDueDate()))
                .map(context -> {
                    long remainingDays = ChronoUnit.DAYS.between(
                            today,
                            context.schedule().getDueDate()
                    );

                    return DashboardAttentionItemResponse.builder()
                            .id(context.schedule().getScheduleId())
                            .type(DashboardAttentionType.LOAN_DUE_SOON)
                            .remainingDays(remainingDays)
                            .actionUrl("/contracts/"
                                    + context.contract().getContractId()
                                    + "/schedule")
                            .build();
                })
                .toList();
    }
}
