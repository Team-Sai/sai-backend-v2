package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardContractRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.DashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.type.ContractRole;
import org.teamsai.saibackend.domain.contract.type.DashboardContractStatus;
import org.teamsai.saibackend.domain.contract.type.DashboardPaymentStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.contract.type.TransactionCategory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DashboardAssembler {

    private record RoleDueSummary(BigDecimal amount, Integer dueMonth) {}

    private DashboardAssembler() {
    }

    public static DashboardContractRowResponse toRow(
            LoanContractResponse contract,
            List<RepaymentScheduleWithRemainingProjection> schedules,
            Long userId
    ) {
        BigDecimal totalRemaining = calculateTotalRemaining(schedules);
        BigDecimal thisMonthDue = calculateThisMonthDue(schedules);

        ContractRole role = determineRole(contract, userId);
        TransactionCategory category = determineCategory(role);
        DashboardContractStatus contractStatus = determineContractStatus(totalRemaining);
        DashboardPaymentStatus paymentStatus = determinePaymentStatus(totalRemaining);
        Optional<RepaymentScheduleWithRemainingProjection> nearestSchedule = findNearestSchedule(schedules);
        LocalDate nearestDueDate = nearestSchedule.map(RepaymentScheduleWithRemainingProjection::getDueDate).orElse(null);
        BigDecimal nextDueAmount = nearestSchedule
                .map(DashboardAssembler::getRemainingPaymentAmount)
                .orElse(null);
        return DashboardContractRowResponse.builder()
                .contractId(contract.getContractId())
                .contractAlias(contract.getContractAlias())
                .role(role)
                .category(category)
                .principalAmount(contract.getPrincipalAmount())
                .totalRemainingAmount(totalRemaining)
                .thisMonthDueAmount(thisMonthDue)
                .contractStatus(contractStatus)
                .repaymentStatus(determineRepaymentStatus(contract, schedules))
                .paymentStatus(paymentStatus)
                .maturityDate(contract.getMaturityDate())
                .nearestScheduleDueDate(nearestDueDate)
                .nextDueAmount(nextDueAmount)
                .createdAt(contract.getCreatedAt())
                .build();
    }

    public static DashboardSummaryResponse buildSummary(
            List<DashboardContractRowResponse> rows,
            Map<Long, List<RepaymentScheduleWithRemainingProjection>> scheduleMap
    ) {
        int totalContractCount = rows.size();

        BigDecimal totalLentAmount = rows.stream()
                .filter(c -> c.getRole() == ContractRole.CREDITOR)
                .map(DashboardContractRowResponse::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalBorrowedAmount = rows.stream()
                .filter(c -> c.getRole() == ContractRole.DEBTOR)
                .map(DashboardContractRowResponse::getTotalRemainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String defaultFilter = totalLentAmount.compareTo(totalBorrowedAmount) >= 0 ? "LENT" : "BORROWED";

        LocalDate nearestDueDate = rows.stream()
                .filter(s -> s.getNearestScheduleDueDate() != null)
                .map(DashboardContractRowResponse::getNearestScheduleDueDate)
                .min(LocalDate::compareTo)
                .orElse(null);

        RoleDueSummary allSummary = calculateRoleDueSummary(rows, scheduleMap);

        List<DashboardContractRowResponse> creditorRows = rows.stream()
                .filter(row -> row.getRole() == ContractRole.CREDITOR)
                .toList();
        List<DashboardContractRowResponse> debtorRows = rows.stream()
                .filter(row -> row.getRole() == ContractRole.DEBTOR)
                .toList();

        RoleDueSummary receivableSummary = calculateRoleDueSummary(creditorRows, scheduleMap);
        RoleDueSummary payableSummary = calculateRoleDueSummary(debtorRows, scheduleMap);

        return DashboardSummaryResponse.builder()
                .totalContractCount(totalContractCount)
                .totalLentAmount(totalLentAmount)
                .totalBorrowedAmount(totalBorrowedAmount)
                .receivableCount(creditorRows.size())
                .payableCount(debtorRows.size())
                .nearestDueDate(nearestDueDate)
                .defaultFilter(defaultFilter)
                .thisMonthDueAmount(allSummary.amount())
                .dueMonth(allSummary.dueMonth())
                .receivableThisMonthAmount(receivableSummary.amount())
                .receivableDueMonth(receivableSummary.dueMonth())
                .payableThisMonthAmount(payableSummary.amount())
                .payableDueMonth(payableSummary.dueMonth())
                .build();
    }

    private static BigDecimal calculateTotalRemaining(List<RepaymentScheduleWithRemainingProjection> schedules) {
        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved())
                .map(DashboardAssembler::getRemainingPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal calculateThisMonthDue(List<RepaymentScheduleWithRemainingProjection> schedules) {
        YearMonth thisMonth = YearMonth.now();
        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved()
                        && YearMonth.from(s.getDueDate()).equals(thisMonth))
                .map(DashboardAssembler::getRemainingPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal calculateYearMonthDue(List<RepaymentScheduleWithRemainingProjection> schedules, YearMonth targetMonth) {
        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved()
                        && YearMonth.from(s.getDueDate()).equals(targetMonth))
                .map(DashboardAssembler::getRemainingPaymentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal getRemainingPaymentAmount(RepaymentScheduleWithRemainingProjection schedule) {
        return Optional.ofNullable(schedule.getRemainingPaymentAmount())
                .orElse(schedule.getTotalPaymentDue());
    }

    private static DashboardPaymentStatus determinePaymentStatus(BigDecimal totalRemaining) {
        return totalRemaining.compareTo(BigDecimal.ZERO) == 0
                ? DashboardPaymentStatus.PAID
                : DashboardPaymentStatus.ONGOING;
    }

    private static DashboardContractStatus determineContractStatus(BigDecimal totalRemaining) {
        return totalRemaining.compareTo(BigDecimal.ZERO) == 0
                ? DashboardContractStatus.COMPLETED
                : DashboardContractStatus.ONGOING;
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

    private static RoleDueSummary calculateRoleDueSummary(List<DashboardContractRowResponse> roleRows, Map<Long, List<RepaymentScheduleWithRemainingProjection>> scheduleMap) {
        BigDecimal thisMonthDue = roleRows.stream()
                .map(DashboardContractRowResponse::getThisMonthDueAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate nearestDueDate = roleRows.stream()
                .filter(row -> row.getNearestScheduleDueDate() != null)
                .map(DashboardContractRowResponse::getNearestScheduleDueDate)
                .min(LocalDate::compareTo)
                .orElse(null);

        if (nearestDueDate == null) {
            return new RoleDueSummary(thisMonthDue, null);
        }

        YearMonth targetMonth = YearMonth.from(nearestDueDate);
        boolean isCurrentMonth = targetMonth.equals(YearMonth.now());

        if (thisMonthDue.compareTo(BigDecimal.ZERO) == 0 && !isCurrentMonth) {
            BigDecimal yearMonthDue = roleRows.stream()
                    .map(row -> calculateYearMonthDue(
                            scheduleMap.getOrDefault(row.getContractId(), List.of()), targetMonth))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            return new RoleDueSummary(yearMonthDue, targetMonth.getMonthValue());
        }

        return new RoleDueSummary(thisMonthDue, null);
    }

    private static Optional<RepaymentScheduleWithRemainingProjection> findNearestSchedule(List<RepaymentScheduleWithRemainingProjection> schedules) {
        return schedules.stream()
                .filter(s -> s.getStatus() == RepaymentScheduleStatus.PENDING)
                .min(Comparator.comparing(RepaymentScheduleWithRemainingProjection::getDueDate));
    }
}
