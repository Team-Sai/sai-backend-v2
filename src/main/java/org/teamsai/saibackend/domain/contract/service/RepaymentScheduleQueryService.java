package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.assembler.RepaymentScheduleAssembler;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleSummaryResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.time.LocalDate;
import java.util.List;

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

        LocalDate nextDueDate = repaymentScheduleRepository.findFirstByContractIdAndStatusInOrderBySequenceAsc(
                        contractId,
                        List.of(RepaymentScheduleStatus.PENDING, RepaymentScheduleStatus.OVERDUE)
                )
                .map(RepaymentScheduleEntity::getDueDate)
                .orElse(null);

        return RepaymentScheduleAssembler.toSummary(contract, schedules, nextDueDate);
    }
}
