package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleSummaryResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepaymentScheduleQueryService {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanContractService loanContractService;

    public RepaymentScheduleSummaryResponse getScheduleSummary(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        List<RepaymentScheduleEntity> schedules =
                repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId);

        BigDecimal totalScheduledAmount = schedules.stream()
                .map(RepaymentScheduleEntity::getTotalPaymentDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal paidAmount = schedules.stream()
                .filter(schedule -> schedule.getStatus() == RepaymentScheduleStatus.PAID)
                .map(RepaymentScheduleEntity::getTotalPaymentDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal remainingAmount = totalScheduledAmount.subtract(paidAmount);
        int paidCount = (int) schedules.stream()
                .filter(schedule -> schedule.getStatus() == RepaymentScheduleStatus.PAID)
                .count();
        int totalCount = schedules.size();

        Optional<RepaymentScheduleEntity> nextDueSchedule =
                repaymentScheduleRepository.findFirstByContractIdAndStatusInOrderBySequenceAsc(
                        contractId,
                        List.of(RepaymentScheduleStatus.PENDING, RepaymentScheduleStatus.OVERDUE)
                );
        LocalDate nextDueDate = nextDueSchedule
                .map(RepaymentScheduleEntity::getDueDate)
                .orElse(null);

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
