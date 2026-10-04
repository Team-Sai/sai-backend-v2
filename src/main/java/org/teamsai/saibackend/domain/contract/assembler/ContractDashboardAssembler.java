package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.type.ContractRole;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardStatus;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardPaymentStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.contract.type.TransactionCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ContractDashboardAssembler {

    private ContractDashboardAssembler() {
    }

    public static ContractDashboardRowResponse toRow(
            LoanContractResponse contract,
            List<RepaymentScheduleWithRemainingProjection> schedules,
            Long userId,
            YearMonth targetMonth
    ) {
        BigDecimal totalRemaining = calculateTotalRemaining(schedules);
        BigDecimal thisMonthDue = calculateThisMonthDue(schedules, targetMonth);
        BigDecimal overdueAmount =
                calculateOverdueAmount(schedules, targetMonth);

        ContractRole role = determineRole(contract, userId);
        TransactionCategory category = determineCategory(role);
        ContractDashboardStatus contractStatus = determineContractStatus(totalRemaining);
        ContractDashboardPaymentStatus paymentStatus = determinePaymentStatus(totalRemaining);
        Optional<RepaymentScheduleWithRemainingProjection> nearestSchedule = findNearestSchedule(schedules);
        LocalDate nearestDueDate = nearestSchedule.map(RepaymentScheduleWithRemainingProjection::getDueDate).orElse(null);
        BigDecimal nextDueAmount = nearestSchedule
                .map(ContractDashboardAssembler::getRemainingPaymentAmount)
                .orElse(null);
        return ContractDashboardRowResponse.builder()
                .contractId(contract.getContractId())
                .contractAlias(contract.getContractAlias())
                .role(role)
                .category(category)
                .principalAmount(contract.getPrincipalAmount())
                .totalRemainingAmount(totalRemaining)
                .thisMonthDueAmount(thisMonthDue)
                .overdueAmount(overdueAmount)
                .contractStatus(contractStatus)
                .repaymentStatus(determineRepaymentStatus(contract, schedules))
                .paymentStatus(paymentStatus)
                .maturityDate(contract.getMaturityDate())
                .nearestScheduleDueDate(nearestDueDate)
                .nextDueAmount(nextDueAmount)
                .createdAt(contract.getCreatedAt())
                .build();
    }

    public static ContractDashboardSummaryResponse buildSummary(
            List<ContractDashboardRowResponse> rows) {
        int totalContractCount = rows.size();

        BigDecimal totalLentAmount = rows.stream()
                .filter(c -> c.getRole() == ContractRole.CREDITOR)
                .map(ContractDashboardRowResponse::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBorrowedAmount = rows.stream()
                .filter(c -> c.getRole() == ContractRole.DEBTOR)
                .map(ContractDashboardRowResponse::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String defaultFilter = totalLentAmount.compareTo(totalBorrowedAmount) >= 0 ? "LENT" : "BORROWED";

        LocalDate nearestDueDate = rows.stream()
                .filter(s -> s.getNearestScheduleDueDate() != null)
                .map(ContractDashboardRowResponse::getNearestScheduleDueDate)
                .min(LocalDate::compareTo)
                .orElse(null);

        List<ContractDashboardRowResponse> creditorRows = rows.stream()
                .filter(row -> row.getRole() == ContractRole.CREDITOR)
                .toList();
        List<ContractDashboardRowResponse> debtorRows = rows.stream()
                .filter(row -> row.getRole() == ContractRole.DEBTOR)
                .toList();
        BigDecimal thisMonthDueAmount = rows.stream()
                .map(ContractDashboardRowResponse::getThisMonthDueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal receivableThisMonthAmount = creditorRows.stream()
                .map(ContractDashboardRowResponse::getThisMonthDueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal payableThisMonthAmount = debtorRows.stream()
                .map(ContractDashboardRowResponse::getThisMonthDueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal receivableOverdueAmount = creditorRows.stream()
                .map(ContractDashboardRowResponse::getOverdueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal payableOverdueAmount = debtorRows.stream()
                .map(ContractDashboardRowResponse::getOverdueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal payableTotalRequiredAmount =
                payableThisMonthAmount.add(payableOverdueAmount);

        return ContractDashboardSummaryResponse.builder()
                .totalContractCount(totalContractCount)
                .totalLentAmount(totalLentAmount)
                .totalBorrowedAmount(totalBorrowedAmount)
                .receivableCount(creditorRows.size())
                .payableCount(debtorRows.size())
                .nearestDueDate(nearestDueDate)
                .defaultFilter(defaultFilter)
                .thisMonthDueAmount(thisMonthDueAmount)
                .dueMonth(null)
                .receivableThisMonthAmount(receivableThisMonthAmount)
                .receivableDueMonth(null)
                .payableThisMonthAmount(payableThisMonthAmount)
                .payableDueMonth(null)
                .receivableOverdueAmount(receivableOverdueAmount)
                .payableOverdueAmount(payableOverdueAmount)
                .payableTotalRequiredAmount(payableTotalRequiredAmount)
                .build();
    }

    private static BigDecimal calculateTotalRemaining(List<RepaymentScheduleWithRemainingProjection> schedules) {
        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved())
                .map(ContractDashboardAssembler::getRemainingPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal calculateThisMonthDue(
            List<RepaymentScheduleWithRemainingProjection> schedules,
            YearMonth targetMonth
    ) {
        LocalDate monthStart = targetMonth.atDay(1);
        LocalDate nextMonthStart = targetMonth.plusMonths(1).atDay(1);

        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved())
                .filter(s ->
                        !s.getDueDate().isBefore(monthStart)
                                && s.getDueDate().isBefore(nextMonthStart))
                .map(ContractDashboardAssembler::getRemainingPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal calculateOverdueAmount(
            List<RepaymentScheduleWithRemainingProjection> schedules,
            YearMonth targetMonth
    ) {
        LocalDate monthStart = targetMonth.atDay(1);

        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved())
                .filter(s -> s.getDueDate().isBefore(monthStart))
                .map(ContractDashboardAssembler::getRemainingPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal getRemainingPaymentAmount(RepaymentScheduleWithRemainingProjection schedule) {
        return Optional.ofNullable(schedule.getRemainingPaymentAmount())
                .orElse(schedule.getTotalPaymentDue());
    }

    private static ContractDashboardPaymentStatus determinePaymentStatus(BigDecimal totalRemaining) {
        return totalRemaining.compareTo(BigDecimal.ZERO) == 0
                ? ContractDashboardPaymentStatus.PAID
                : ContractDashboardPaymentStatus.ONGOING;
    }

    private static ContractDashboardStatus determineContractStatus(BigDecimal totalRemaining) {
        return totalRemaining.compareTo(BigDecimal.ZERO) == 0
                ? ContractDashboardStatus.COMPLETED
                : ContractDashboardStatus.ONGOING;
    }

    private static String determineRepaymentStatus(LoanContractResponse contract, List<RepaymentScheduleWithRemainingProjection> schedules) {
        if (schedules.isEmpty() || contract.getStatus() != ContractStatus.COMPLETED) return "ONGOING";
        return schedules.stream().allMatch(s -> s.getStatus().isSettled())
                ? "COMPLETED" : "REPAYING";
    }

    private static ContractRole determineRole(LoanContractResponse contract, Long userId) {
        return contract.getCreditorId().equals(userId)
                ? ContractRole.CREDITOR
                : ContractRole.DEBTOR;
    }

    private static TransactionCategory determineCategory(ContractRole role) {
        return role == ContractRole.CREDITOR
                ? TransactionCategory.RECEIVE
                : TransactionCategory.PAY;
    }

    private static Optional<RepaymentScheduleWithRemainingProjection> findNearestSchedule(List<RepaymentScheduleWithRemainingProjection> schedules) {
        return schedules.stream()
                .filter(s -> s.getStatus() == RepaymentScheduleStatus.PENDING)
                .min(Comparator.comparing(RepaymentScheduleWithRemainingProjection::getDueDate));
    }
}
