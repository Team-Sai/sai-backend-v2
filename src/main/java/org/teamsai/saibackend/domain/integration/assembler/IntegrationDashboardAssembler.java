package org.teamsai.saibackend.domain.integration.assembler;

import org.teamsai.saibackend.domain.calendar.response.DashboardCalendarItemResponse;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardContractRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardResponse;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.service.DashboardService;
import org.teamsai.saibackend.domain.contract.type.DashboardContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardAmountSummaryResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardAttentionItemResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardCalendarDayResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardMoneyBreakdownResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardMonthlySummaryResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardRecentTransactionResponse;
import org.teamsai.saibackend.domain.integration.type.DashboardAttentionType;
import org.teamsai.saibackend.domain.integration.type.DashboardTransactionStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;


public final class IntegrationDashboardAssembler {

    public record SettlementContext(
            SettlementListResponse settlement,
            BigDecimal roleRemainingAmount,
            BigDecimal originalRoleAmount
    ) {
        public boolean isOwner() {
            return "OWNER".equals(settlement.role());
        }
    }

    private static class CalendarDirection {
        private boolean inbound;
        private boolean outbound;
    }

    private IntegrationDashboardAssembler() {
    }

    public static SettlementContext toSettlementContext(
            SettlementListResponse settlement,
            SettlementPaymentStatusResponse paymentStatus,
            Long userId
    ) {
        BigDecimal roleRemaining = "OWNER".equals(settlement.role())
                ? zeroIfNull(paymentStatus.getTotalRemainingAmount())
                : ownRemainingAmount(paymentStatus, userId);
        BigDecimal originalRoleAmount = "OWNER".equals(settlement.role())
                ? zeroIfNull(paymentStatus.getTotalExpectedAmount())
                : ownExpectedAmount(paymentStatus, userId);
        return new SettlementContext(settlement, roleRemaining, originalRoleAmount);
    }

    public static DashboardAmountSummaryResponse toAmountSummary(
            DashboardSummaryResponse contractSummary,
            List<SettlementContext> settlements
    ) {
        BigDecimal settlementReceivable = settlements.stream()
                .filter(SettlementContext::isOwner)
                .map(SettlementContext::roleRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal settlementPayable = settlements.stream()
                .filter(context -> !context.isOwner())
                .map(SettlementContext::roleRemainingAmount)
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

    public static List<DashboardRecentTransactionResponse> toRecentTransactions(
            DashboardResponse contractDashboard,
            List<SettlementContext> settlements
    ) {
        Comparator<DashboardRecentTransactionResponse> newestFirst = Comparator.comparing(
                DashboardRecentTransactionResponse::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())
        );
        List<DashboardRecentTransactionResponse> transactions = new ArrayList<>();
        recentLoanTransactions(contractDashboard).stream()
                .sorted(newestFirst)
                .limit(5)
                .forEach(transactions::add);
        settlements.stream()
                .map(IntegrationDashboardAssembler::toRecentSettlementTransaction)
                .sorted(newestFirst)
                .limit(5)
                .forEach(transactions::add);
        return transactions.stream()
                .sorted(newestFirst)
                .toList();
    }

    private static List<DashboardRecentTransactionResponse> recentLoanTransactions(DashboardResponse contractDashboard) {
        List<DashboardContractRowResponse> contracts = contractDashboard.getContracts();
        if (contracts == null) {
            return List.of();
        }
        return contracts.stream()
                .map(IntegrationDashboardAssembler::toRecentLoanTransaction)
                .toList();
    }

    private static DashboardRecentTransactionResponse toRecentLoanTransaction(DashboardContractRowResponse contract) {
        DashboardTransactionStatus status =
                contract.getContractStatus() == DashboardContractStatus.COMPLETED
                        ? DashboardTransactionStatus.COMPLETED
                        : DashboardTransactionStatus.IN_PROGRESS;

        return DashboardRecentTransactionResponse.builder()
                .targetId(contract.getContractId())
                .type(PaymentTargetType.LOAN)
                .title(contract.getContractAlias())
                .status(status)
                .amount(zeroIfNull(contract.getPrincipalAmount()))
                .detailUrl("/contracts/" + contract.getContractId() + "/contract-detail")
                .createdAt(contract.getCreatedAt())
                .build();
    }

    private static DashboardRecentTransactionResponse toRecentSettlementTransaction(SettlementContext context) {
        SettlementListResponse settlement = context.settlement();
        return DashboardRecentTransactionResponse.builder()
                .targetId(settlement.settlementId())
                .type(PaymentTargetType.SETTLEMENT)
                .title(settlement.title())
                .status(isClosed(settlement)
                        ? DashboardTransactionStatus.COMPLETED
                        : DashboardTransactionStatus.IN_PROGRESS)
                .amount(context.originalRoleAmount())
                .detailUrl("/settlements/" + settlement.settlementId())
                .createdAt(settlement.createdAt())
                .build();
    }

    public static List<DashboardCalendarDayResponse> toCalendarDays(
            List<DashboardService.LoanScheduleContext> loanSchedules,
            List<SettlementContext> settlements,
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
                .filter(context -> !isClosed(context.settlement()))
                .filter(context -> context.roleRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(context -> effectiveDueDate(context.settlement()) != null)
                .filter(context -> YearMonth.from(effectiveDueDate(context.settlement())).equals(yearMonth))
                .forEach(context -> {
                    CalendarDirection direction = directionsByDate.computeIfAbsent(
                            effectiveDueDate(context.settlement()), ignored -> new CalendarDirection()
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
            List<DashboardService.LoanScheduleContext> loanSchedules,
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
            List<DashboardService.LoanScheduleContext> loanSchedules,
            List<SettlementContext> settlements,
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
            List<DashboardService.LoanScheduleContext> loanSchedules,
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
            List<SettlementContext> settlements,
            LocalDate date
    ) {
        LocalDate today = LocalDate.now();

        return settlements.stream()
                .filter(context -> !isClosed(context.settlement()))
                .filter(context -> context.roleRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(context -> effectiveDueDate(context.settlement()) != null)
                .filter(context -> effectiveDueDate(context.settlement()).equals(date))
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

    public static List<DashboardAttentionItemResponse> toAttentionItems(
            List<DashboardService.LoanScheduleContext> loanSchedules,
            List<SettlementContext> settlements
    ) {
        LocalDate today = LocalDate.now();
        LocalDate attentionLimit = today.plusDays(3);
        List<DashboardAttentionItemResponse> items = new ArrayList<>(
                upcomingLoanAttentionItems(loanSchedules)
        );
        settlements.stream()
                .filter(context -> !isClosed(context.settlement()))
                .filter(context -> context.isOwner()
                        || context.roleRemainingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(context -> effectiveDueDate(context.settlement()) != null)
                .filter(context -> !effectiveDueDate(context.settlement()).isBefore(today))
                .filter(context -> !effectiveDueDate(context.settlement()).isAfter(attentionLimit))
                .map(context -> DashboardAttentionItemResponse.builder()
                        .id(context.settlement().settlementId())
                        .type(DashboardAttentionType.SETTLEMENT_DUE_SOON)
                        .remainingDays(ChronoUnit.DAYS.between(today, effectiveDueDate(context.settlement())))
                        .actionUrl("/settlements/" + context.settlement().settlementId())
                        .build())
                .forEach(items::add);
        return items.stream()
                .sorted(Comparator.comparing(DashboardAttentionItemResponse::getRemainingDays))
                .toList();
    }

    private static List<DashboardAttentionItemResponse> upcomingLoanAttentionItems(
            List<DashboardService.LoanScheduleContext> loanSchedules
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

    public static DashboardMonthlySummaryResponse toMonthlySummary(
            List<DashboardService.LoanScheduleContext> loanSchedules,
            List<SettlementContext> settlements,
            YearMonth yearMonth
    ) {
        List<DashboardService.LoanScheduleContext> monthlySchedules = loanSchedules.stream()
                .filter(context -> YearMonth.from(context.schedule().getDueDate()).equals(yearMonth))
                .toList();

        int completedLoanRepaymentCount = (int) monthlySchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PAID)
                .count();

        int inProgressLoanRepaymentCount = (int) monthlySchedules.stream()
                .filter(context -> context.schedule().getStatus() == RepaymentScheduleStatus.PENDING)
                .count();

        List<SettlementContext> monthlySettlements = settlements.stream()
                .filter(context -> effectiveDueDate(context.settlement()) != null)
                .filter(context -> YearMonth.from(effectiveDueDate(context.settlement())).equals(yearMonth))
                .toList();
        int completedSettlementCount = (int) monthlySettlements.stream()
                .filter(context -> isClosed(context.settlement()))
                .count();
        int inProgressSettlementCount = (int) monthlySettlements.stream()
                .filter(context -> !isClosed(context.settlement()))
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

    private static BigDecimal ownRemainingAmount(SettlementPaymentStatusResponse status, Long userId) {
        if (status.getObligations() == null) {
            return BigDecimal.ZERO;
        }
        return status.getObligations().stream()
                .filter(obligation -> userId.equals(obligation.getUserId()))
                .map(SettlementPaymentObligationResponse::getRemainingAmount)
                .map(IntegrationDashboardAssembler::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal ownExpectedAmount(SettlementPaymentStatusResponse status, Long userId) {
        if (status.getObligations() == null) {
            return BigDecimal.ZERO;
        }
        return status.getObligations().stream()
                .filter(obligation -> userId.equals(obligation.getUserId()))
                .map(SettlementPaymentObligationResponse::getExpectedAmount)
                .map(IntegrationDashboardAssembler::zeroIfNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal zeroIfNull(BigDecimal amount) {
        return amount == null ? BigDecimal.ZERO : amount;
    }

    private static boolean isClosed(SettlementListResponse settlement) {
        return "CLOSED".equals(settlement.settlementStatus());
    }

    private static LocalDate effectiveDueDate(SettlementListResponse settlement) {
        return "RECURRING".equals(settlement.settlementType())
                ? settlement.cycleDate()
                : settlement.dueDate();
    }

    private static String settlementTypeLabel(String settlementType) {
        return "RECURRING".equals(settlementType) ? "정기정산" : "공동정산";
    }

    private static String splitTypeLabel(String splitType) {
        return "CUSTOM".equals(splitType) ? "직접입력" : "균등";
    }
}
