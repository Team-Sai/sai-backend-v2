package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.assembler.RepaymentScheduleAssembler;
import org.teamsai.saibackend.domain.contract.dto.response.DepositReturnResponse;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleSummaryResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.repository.DepositReturnRepository;
import org.teamsai.saibackend.domain.contract.repository.EarlyRepaymentRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepaymentScheduleQueryService {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanContractService loanContractService;
    private final PaymentRecordService paymentRecordService;
    private final EarlyRepaymentRepository earlyRepaymentRepository;
    private final DepositReturnRepository depositReturnRepository;
    private final EarlyRepaymentService earlyRepaymentService;

    public RepaymentScheduleSummaryResponse getScheduleSummary(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        List<RepaymentSchedule> schedules =
                repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId);

        LocalDate nextDueDate = repaymentScheduleRepository.findFirstByContractIdAndStatusInOrderBySequenceAsc(
                        contractId,
                        List.of(RepaymentScheduleStatus.PENDING, RepaymentScheduleStatus.OVERDUE)
                )
                .map(RepaymentSchedule::getDueDate)
                .orElse(null);

        Map<Long, BigDecimal> paidByScheduleId = paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.LOAN,
                        schedules.stream().map(RepaymentSchedule::getScheduleId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        PaymentRecord::getTargetId,
                        Collectors.reducing(BigDecimal.ZERO, PaymentRecord::getAmount, BigDecimal::add)));

        List<EarlyRepaymentResponse> earlyRepayments = earlyRepaymentRepository
                .findByContractIdOrderByProcessedAtDesc(contractId)
                .stream()
                .map(EarlyRepaymentResponse::from)
                .toList();


        List<DepositReturnResponse> returnedDeposits = depositReturnRepository
                .findByContractIdOrderByReturnedAtDesc(contractId)
                .stream()
                .map(DepositReturnResponse::from)
                .toList();

        return RepaymentScheduleAssembler.toSummary(
                contract, schedules, nextDueDate, paidByScheduleId, earlyRepayments,
                earlyRepaymentService.calculateTodayPayoff(contract, schedules), returnedDeposits);
    }
}
