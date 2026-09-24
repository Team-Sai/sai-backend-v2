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
import org.teamsai.saibackend.domain.contract.exception.RepaymentScheduleErrorCode;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.contract.util.ScheduleGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
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

    @EventListener
    @Transactional
    public void onContractCreated(ContractCreatedEvent event) {
        generateSchedule(event.contractId());
    }

    @Transactional
    public void generateSchedule(Long contractId) {
        LoanContractResponse contract = loanContractService.getContractForInternalUse(contractId);

        Period period = Period.between(contract.getStartDate(), contract.getMaturityDate());
        int months = period.getYears() * 12 + period.getMonths();

        if (months <= 0) {
            throw RepaymentScheduleErrorCode.INVALID_CONTRACT_PERIOD.toException();
        }

        List<RepaymentScheduleEntity> schedules = switch (contract.getRepaymentType()) {
            case EQUAL_PRINCIPAL_AND_INTEREST -> ScheduleGenerator.generateEqualPrincipalAndInterest(
                    contractId, contract.getPrincipalAmount(), contract.getInterestRate(), months, contract.getStartDate());
            case EQUAL_PRINCIPAL -> ScheduleGenerator.generateEqualPrincipal(
                    contractId, contract.getPrincipalAmount(), contract.getInterestRate(), months, contract.getStartDate());
            case BULLET_REPAYMENT -> ScheduleGenerator.generateBulletRepayment(
                    contractId, contract.getPrincipalAmount(), contract.getInterestRate(), months, contract.getStartDate());
        };

        repaymentScheduleRepository.saveAll(schedules);
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

        Period period = Period.between(baseDate, v2.getMaturityDate());
        int months = period.getYears() * 12 + period.getMonths();

        if (months <= 0) {
            throw RepaymentScheduleErrorCode.INVALID_CONTRACT_PERIOD.toException();
        }

        List<RepaymentScheduleEntity> newSchedules = switch (v2.getRepaymentType()) {
            case EQUAL_PRINCIPAL_AND_INTEREST -> ScheduleGenerator.generateEqualPrincipalAndInterest(
                    v2ContractId, openingPrincipal, v2.getInterestRate(), months, baseDate);
            case EQUAL_PRINCIPAL -> ScheduleGenerator.generateEqualPrincipal(
                    v2ContractId, openingPrincipal, v2.getInterestRate(), months, baseDate);
            case BULLET_REPAYMENT -> ScheduleGenerator.generateBulletRepayment(
                    v2ContractId, openingPrincipal, v2.getInterestRate(), months, baseDate);
        };

        repaymentScheduleRepository.saveAll(newSchedules);
    }

    public RepaymentScheduleEntity getScheduleByScheduleId(Long scheduleId) {
        return repaymentScheduleRepository.findById(scheduleId)
                .orElseThrow(() -> RepaymentScheduleErrorCode.SCHEDULE_NOT_FOUND.toException());
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
