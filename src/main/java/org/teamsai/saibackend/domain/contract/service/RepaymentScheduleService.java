package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.event.ContractCreatedEvent;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.dto.RepaymentScheduleDTO;
import org.teamsai.saibackend.domain.contract.exception.RepaymentScheduleErrorCode;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RepaymentScheduleService {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final LoanContractService loanContractService;
    private final RepaymentScheduleGenerator repaymentScheduleGenerator;

    @EventListener
    @Transactional
    public void onContractCreated(ContractCreatedEvent event) {
        generateSchedule(event.contractId());
    }

    private RepaymentScheduleEntity toEntity(RepaymentScheduleDTO dto) {
        return new RepaymentScheduleEntity(
                dto.getContractId(),
                dto.getSequence(),
                dto.getDueDate(),
                dto.getPrincipalDue(),
                dto.getInterestDue(),
                dto.getTotalPaymentDue(),
                dto.getRemainingPrincipal(),
                dto.getStatus(),
                dto.getCreatedAt()
        );
    }

    private RepaymentScheduleDTO toDTO(RepaymentScheduleEntity entity) {
        return RepaymentScheduleDTO.builder()
                .scheduleId(entity.getScheduleId())
                .contractId(entity.getContractId())
                .sequence(entity.getSequence())
                .dueDate(entity.getDueDate())
                .principalDue(entity.getPrincipalDue())
                .interestDue(entity.getInterestDue())
                .totalPaymentDue(entity.getTotalPaymentDue())
                .remainingPrincipal(entity.getRemainingPrincipal())
                .status(entity.getStatus())
                .paidAt(entity.getPaidAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    @Transactional
    public void generateSchedule(Long contractId) {
        LoanContractResponse contract = loanContractService.getContractForInternalUse(contractId);

        List<RepaymentScheduleDTO> schedules = repaymentScheduleGenerator.generate(
                contractId, contract.getRepaymentType(), contract.getPrincipalAmount(),
                contract.getInterestRate(), contract.getStartDate(), contract.getMaturityDate());

        List<RepaymentScheduleEntity> entities = schedules.stream()
                .map(this::toEntity)
                .toList();
        repaymentScheduleRepository.saveAll(entities);
    }

    public List<RepaymentScheduleEntity> getSchedule(Long contractId) {
        return repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId);
    }

    public Optional<RepaymentScheduleEntity> findNextPendingSchedule(Long contractId) {
        return repaymentScheduleRepository.findFirstByContractIdAndStatusInOrderBySequenceAsc(
                contractId, List.of(RepaymentScheduleStatus.PENDING, RepaymentScheduleStatus.OVERDUE)
        );
    }

    @Transactional
    public void markAsPaid(Long scheduleId, LocalDateTime paidAt) {
        RepaymentScheduleEntity schedule = repaymentScheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(RepaymentScheduleErrorCode.SCHEDULE_NOT_FOUND::toException);
        schedule.fullPayment(paidAt);
        repaymentScheduleRepository.save(schedule);

    }

    @Transactional
    public void generateChangedSchedule(Long v1ContractId, Long v2ContractId) {
        List<RepaymentScheduleEntity> v1Schedules = repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(v1ContractId);

        Optional<RepaymentScheduleEntity> lastPaid = v1Schedules.stream()
                .filter(s -> s.getStatus() == RepaymentScheduleStatus.PAID)
                .max(Comparator.comparing(RepaymentScheduleEntity::getSequence));

        LoanContractResponse v1 = loanContractService.getContractForInternalUse(v1ContractId);
        LoanContractResponse v2 = loanContractService.getContractForInternalUse(v2ContractId);

        BigDecimal openingPrincipal = lastPaid.map(RepaymentScheduleEntity::getRemainingPrincipal)
                .orElse(v1.getPrincipalAmount());
        LocalDate baseDate = lastPaid.map(RepaymentScheduleEntity::getDueDate)
                .orElse(v1.getStartDate());

        repaymentScheduleRepository.deleteByContractIdAndStatus(v1ContractId, RepaymentScheduleStatus.PENDING);

        List<RepaymentScheduleDTO> newSchedules = repaymentScheduleGenerator.generate(
                v2ContractId, v2.getRepaymentType(), openingPrincipal,
                v2.getInterestRate(), baseDate, v2.getMaturityDate());

        List<RepaymentScheduleEntity> newEntities = newSchedules.stream()
                .map(this::toEntity)
                .toList();

        repaymentScheduleRepository.saveAll(newEntities);
    }

    public RepaymentScheduleDTO getScheduleByScheduleId(Long scheduleId) {
        RepaymentScheduleEntity entity = repaymentScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> RepaymentScheduleErrorCode.SCHEDULE_NOT_FOUND.toException());
        return toDTO(entity);
    }

    public Map<Long, List<RepaymentScheduleWithRemainingProjection>> getSchedulesByContractIds(List<Long> contractIds) {
        if (contractIds == null || contractIds.isEmpty()) {
            return Map.of();
        }

        List<RepaymentScheduleWithRemainingProjection> all = repaymentScheduleRepository.findByContractIds(contractIds);
        return all.stream()
                .collect(Collectors.groupingBy(RepaymentScheduleWithRemainingProjection::getContractId));
    }

    public List<Long> findWriteOffCandidateScheduleIds(LocalDate cutoffDate) {
        return repaymentScheduleRepository.findScheduleIdsByStatusAndDueDateLessThanEqual(
                RepaymentScheduleStatus.OVERDUE, cutoffDate);
    }

    @Transactional
    public int writeOffSchedules(List<Long> scheduleIds) {
        return repaymentScheduleRepository.updateStatusBulk(
                scheduleIds, RepaymentScheduleStatus.WRITTEN_OFF, RepaymentScheduleStatus.OVERDUE);
    }

    @Transactional
    public int markSchedulesOverdue(LocalDate baseDate) {
        return repaymentScheduleRepository.markOverdueBulk(
                RepaymentScheduleStatus.OVERDUE, RepaymentScheduleStatus.PENDING, baseDate);
    }
}
