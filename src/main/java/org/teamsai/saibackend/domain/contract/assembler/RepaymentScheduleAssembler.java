package org.teamsai.saibackend.domain.contract.assembler;

import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleSummaryResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class RepaymentScheduleAssembler {

    private RepaymentScheduleAssembler() {
    }

    public static RepaymentScheduleSummaryResponse toSummary(
            LoanContractResponse contract,
            List<RepaymentSchedule> schedules,
            LocalDate nextDueDate
    ) {
        BigDecimal totalScheduledAmount = schedules.stream()
                .map(RepaymentSchedule::getTotalPaymentDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal paidAmount = schedules.stream()
                .filter(s -> s.getStatus() == RepaymentScheduleStatus.PAID)
                .map(RepaymentSchedule::getTotalPaymentDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remainingAmount = totalScheduledAmount.subtract(paidAmount);

        int paidCount = (int) schedules.stream()
                .filter(s -> s.getStatus() == RepaymentScheduleStatus.PAID)
                .count();
        int totalCount = schedules.size();

        List<RepaymentScheduleResponse> scheduleResponses = schedules.stream()
                .map(RepaymentScheduleResponse::from)
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
                .build();
    }
}
