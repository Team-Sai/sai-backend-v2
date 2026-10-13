package org.teamsai.saibackend.domain.integration.assembler;

import org.teamsai.saibackend.domain.calendar.dto.response.DashboardCalendarItemResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardCalendarDayResponse;
import org.teamsai.saibackend.domain.integration.model.SettlementDashboardContext;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.TreeMap;

public final class DashboardCalendarAssembler {
    private DashboardCalendarAssembler() {
    }

    private static class CalendarDirection {
        private boolean inbound;
        private boolean outbound;
    }

    public static List<DashboardCalendarDayResponse> toCalendarDays(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules,
            List<SettlementDashboardContext> settlements,
            YearMonth yearMonth,
            Long userId
    ) {
        Map<LocalDate, CalendarDirection> directionsByDate = new TreeMap<>();
        loanCalendarDays(loanSchedules, yearMonth, userId).forEach(day -> {
            CalendarDirection direction = directionsByDate.computeIfAbsent(
                    day.getDate(), ignored -> new CalendarDirection()
            );
            direction.inbound |= day.isHasInbound();
            direction.outbound |= day.isHasOutbound();
        });
        settlements.stream()
                .filter(context -> !context.isClosed())
                .filter(context -> context.roleRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(context -> context.effectiveDueDate() != null)
                .filter(context -> YearMonth.from(context.effectiveDueDate()).equals(yearMonth))
                .forEach(context -> {
                    CalendarDirection direction = directionsByDate.computeIfAbsent(
                            context.effectiveDueDate(), ignored -> new CalendarDirection()
                    );
                    direction.inbound |= context.isOwner();
                    direction.outbound |= !context.isOwner();
                });
        return directionsByDate.entrySet().stream()
                .map(entry -> DashboardCalendarDayResponse.builder()
                        .date(entry.getKey())
                        .hasInbound(entry.getValue().inbound)
                        .hasOutbound(entry.getValue().outbound)
                        .build())
                .toList();
    }

    private static List<DashboardCalendarDayResponse> loanCalendarDays(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules,
            YearMonth yearMonth,
            Long userId
    ) {
        Map<LocalDate, CalendarDirection> directionsByDate = new TreeMap<>();

        loanSchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PENDING)
                .filter(context -> YearMonth.from(context.schedule().getDueDate()).equals(yearMonth))
                .forEach(context -> {
                    CalendarDirection direction = directionsByDate.computeIfAbsent(
                            context.schedule().getDueDate(),
                            ignored -> new CalendarDirection()
                    );
                    if (userId.equals(context.contract().getCreditorId())) {
                        direction.inbound = true;
                    }
                    if (userId.equals(context.contract().getDebtorId())) {
                        direction.outbound = true;
                    }
                });

        return directionsByDate.entrySet().stream()
                .map(entry -> DashboardCalendarDayResponse.builder()
                        .date(entry.getKey())
                        .hasInbound(entry.getValue().inbound)
                        .hasOutbound(entry.getValue().outbound)
                        .build())
                .toList();
    }

    public static List<DashboardCalendarItemResponse> toCalendarDayDetail(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules,
            List<SettlementDashboardContext> settlements,
            LocalDate date,
            Long userId
    ) {
        List<DashboardCalendarItemResponse> items = new ArrayList<>();
        items.addAll(loanCalendarItems(loanSchedules, date, userId));
        items.addAll(settlementCalendarItems(settlements, date));
        return items.stream()
                .sorted(Comparator.comparing(DashboardCalendarItemResponse::getTitle))
                .toList();
    }

    private static List<DashboardCalendarItemResponse> loanCalendarItems(
            List<ContractDashboardQueryService.LoanScheduleContext> loanSchedules,
            LocalDate date,
            Long userId
    ) {
        Map<Long, Long> totalInstallmentsByContract = loanSchedules.stream()
                .collect(Collectors.groupingBy(
                        context -> context.contract().getContractId(),
                        Collectors.counting()
                ));

        LocalDate today = LocalDate.now();

        return loanSchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PENDING)
                .filter(context -> date.equals(context.schedule().getDueDate()))
                .map(context -> {
                    boolean isCreditor = userId.equals(context.contract().getCreditorId());
                    Long totalInstallments = totalInstallmentsByContract.get(context.contract().getContractId());

                    return DashboardCalendarItemResponse.builder()
                            .targetId(context.contract().getContractId())
                            .type(PaymentTargetType.LOAN)
                            .title(context.contract().getContractAlias())
                            .subLabel(isCreditor ? "수취예정" : "납부예정")
                            .amount(context.schedule().getTotalPaymentDue())
                            .detailUrl("/contracts/" + context.contract().getContractId() + "/schedule")
                            .counterpartyName(isCreditor
                                    ? context.contract().getDebtorName()
                                    : context.contract().getCreditorName())
                            .installmentInfo(context.schedule().getSequence() + "/" + totalInstallments + "회차")
                            .overdue(date.isBefore(today))
                            .maturityDate(context.contract().getMaturityDate())
                            .principalAmount(context.contract().getPrincipalAmount())
                            .interestRate(context.contract().getInterestRate())
                            .build();
                })
                .toList();
    }

    private static List<DashboardCalendarItemResponse> settlementCalendarItems(
            List<SettlementDashboardContext> settlements,
            LocalDate date
    ) {
        LocalDate today = LocalDate.now();

        return settlements.stream()
                .filter(context -> !context.isClosed())
                .filter(context -> context.roleRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(context -> context.effectiveDueDate() != null)
                .filter(context -> context.effectiveDueDate().equals(date))
                .map(context -> DashboardCalendarItemResponse.builder()
                        .targetId(context.settlement().settlementId())
                        .type(PaymentTargetType.SETTLEMENT)
                        .title(context.settlement().title())
                        .subLabel(context.isOwner() ? "받을 돈" : "보낼 돈")
                        .amount(context.roleRemainingAmount())
                        .detailUrl("/settlements/" + context.settlement().settlementId())
                        .categoryLabel(context.settlement().settlementCategory())
                        .overdue(date.isBefore(today))
                        .settlementTypeLabel(settlementTypeLabel(context.settlement().settlementType()))
                        .splitTypeLabel(splitTypeLabel(context.settlement().splitType()))
                        .periodStartDate(context.settlement().startDate())
                        .periodEndDate(context.settlement().endDate())
                        .build())
                .toList();
    }

    private static String settlementTypeLabel(String settlementType) {
        return "RECURRING".equals(settlementType) ? "정기정산" : "공동정산";
    }

    private static String splitTypeLabel(String splitType) {
        return "CUSTOM".equals(splitType) ? "직접입력" : "균등";
    }
}
