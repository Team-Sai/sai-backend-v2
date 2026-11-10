package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.response.*;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public final class RepaymentScheduleAssembler {

    private RepaymentScheduleAssembler() {
    }

    public static RepaymentScheduleSummaryResponse toSummary(
            LoanContractResponse contract,
            List<RepaymentSchedule> schedules,
            LocalDate nextDueDate,
            Map<Long, BigDecimal> paidByScheduleId,
            List<EarlyRepaymentResponse> earlyRepayments,
            PayoffResponse todayPayoff,
            List<DepositReturnResponse> returnedDeposits
    ) {
        // 회차에 안 담긴 중도상환 원금·경과 이자 (수수료는 상환액이 아니라 제외)
        BigDecimal earlyPaid = earlyRepayments.stream()
                .filter(early -> !isPayoffRow(early, schedules))
                .map(early -> early.principalAmount().add(early.accruedInterest()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalScheduledAmount = schedules.stream()
                .map(RepaymentSchedule::getTotalPaymentDue)
                .reduce(earlyPaid, BigDecimal::add);

        BigDecimal paidAmount = schedules.stream()
                .map(s -> paidAmountOf(s, paidByScheduleId))
                .reduce(earlyPaid, BigDecimal::add);

        BigDecimal remainingAmount = totalScheduledAmount.subtract(paidAmount);

        int paidCount = (int) schedules.stream()
                .filter(s -> s.getStatus() == RepaymentScheduleStatus.PAID)
                .count();
        int totalCount = schedules.size();

        List<RepaymentScheduleResponse> scheduleResponses = schedules.stream()
                .map(s -> RepaymentScheduleResponse.from(
                        s, paidAmountOf(s, paidByScheduleId),
                        earlyRepayments.stream().anyMatch(early -> isPayoff(s, early))))
                .toList();

        return RepaymentScheduleSummaryResponse.builder()
                .creditorName(contract.getCreditorName())
                .debtorName(contract.getDebtorName())
                .totalScheduledAmount(totalScheduledAmount)
                .paidAmount(paidAmount)
                .remainingAmount(remainingAmount)
                .paidCount(paidCount)
                .totalCount(totalCount)
                .schedules(scheduleResponses)
                .nextDueDate(nextDueDate)
                .earlyRepayments(earlyRepayments)
                .contractDisplayId(contract.getContractDisplayId())
                .todayPayoff(todayPayoff)
                .returnedDeposits(returnedDeposits)
                .build();
    }

    private static boolean isPayoffRow(EarlyRepaymentResponse early, List<RepaymentSchedule> schedules) {
        return schedules.stream().anyMatch(s -> isPayoff(s, early));
    }

    // 전액 중도상환이 기록된 회차인지 (같은 시각에 남은 원금 0으로 완료됨)
    private static boolean isPayoff(RepaymentSchedule s, EarlyRepaymentResponse early) {
        return s.getStatus() == RepaymentScheduleStatus.PAID
                && s.getRemainingPrincipal().signum() == 0
                && s.getPrincipalDue().compareTo(early.principalAmount()) == 0
                && early.processedAt().equals(s.getPaidAt());
    }


    private static BigDecimal paidAmountOf(RepaymentSchedule schedule, Map<Long, BigDecimal> paidByScheduleId) {
        if (schedule.getStatus() == RepaymentScheduleStatus.PAID) {
            return schedule.getTotalPaymentDue();
        }
        return paidByScheduleId.getOrDefault(schedule.getScheduleId(), BigDecimal.ZERO);
    }
}
