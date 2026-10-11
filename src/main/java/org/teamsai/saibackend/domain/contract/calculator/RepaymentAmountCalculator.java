package org.teamsai.saibackend.domain.contract.calculator;

import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

public final class RepaymentAmountCalculator {

    private RepaymentAmountCalculator() {
    }

    public static BigDecimal remainingAmount(
            RepaymentScheduleWithRemainingProjection schedule
    ) {
        return Optional.ofNullable(schedule.getRemainingPaymentAmount())
                .orElse(schedule.getTotalPaymentDue());
    }

    public static BigDecimal totalRemaining(
            List<RepaymentScheduleWithRemainingProjection> schedules
    ) {
        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved())
                .map(RepaymentAmountCalculator::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal thisMonthDue(
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
                .map(RepaymentAmountCalculator::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static BigDecimal previousMonthsUnpaid(
            List<RepaymentScheduleWithRemainingProjection> schedules,
            YearMonth targetMonth
    ) {
        LocalDate monthStart = targetMonth.atDay(1);

        return schedules.stream()
                .filter(s -> s.getStatus().isUnresolved())
                .filter(s -> s.getDueDate().isBefore(monthStart))
                .map(RepaymentAmountCalculator::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}