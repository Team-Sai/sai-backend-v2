package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.dto.request.EarlyRepaymentRequest;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentDepositResponse;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.EarlyRepayment;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.exception.RepaymentScheduleErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractAccountRepository;
import org.teamsai.saibackend.domain.contract.repository.EarlyRepaymentRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.ContractAccountStatus;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

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
public class EarlyRepaymentService {

    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final EarlyRepaymentRepository earlyRepaymentRepository;
    private final LoanContractService loanContractService;
    private final RepaymentScheduleGenerateService repaymentScheduleGenerateService;
    private final ContractAccountRepository contractAccountRepository;
    private final BankTransactionService bankTransactionService;
    private final PaymentRecordService paymentRecordService;

    public Optional<LocalDate> findLatestPaidOn(Long contractId) {
        return earlyRepaymentRepository.findFirstByContractIdOrderByProcessedAtDesc(contractId)
                .flatMap(early -> bankTransactionService.findById(early.getBankTransactionId()))
                .map(tx -> tx.getTransactionAt().toLocalDate());
    }


    public List<EarlyRepaymentDepositResponse> getDeposits(Long contractId, Long userId) {
        LoanContractResponse contract = validateCreditor(contractId, userId);
        List<RepaymentSchedule> all = repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId);
        List<RepaymentSchedule> unresolved = unresolvedRows(all);
        Map<Long, BigDecimal> paid = confirmedAmounts(unresolved);
        List<RepaymentSchedule> shortfallRows = shortfallRows(unresolved, paid);
        List<RepaymentSchedule> futureRows = futureRows(unresolved, shortfallRows);

        if (futureRows.isEmpty()) {
            return List.of();
        }

        RepaymentSchedule first = futureRows.get(0);
        BigDecimal shortfall = shortfallOf(shortfallRows, paid);
        BigDecimal balance = balanceOf(first);

        return bankTransactionService.findUnmatchedDepositsByLinkedAccountId(activeLinkedAccountId(contractId))
                .stream()
                .filter(tx -> isEligibleDeposit(contract, tx))
                .map(tx -> EarlyRepaymentDepositResponse.of(
                        tx, shortfall,
                        accruedInterest(contract, all, first, tx.getTransactionAt().toLocalDate()),
                        balance))
                .toList();
    }

    @Transactional
    public EarlyRepaymentResponse process(Long contractId, Long userId, EarlyRepaymentRequest request) {
        LoanContractResponse contract = validateCreditor(contractId, userId);

        // 1. 같은 입금을 두 번 처리하지 못하게 입금부터 잠근다.
        BankTransaction deposit = bankTransactionService.findByIdAndLinkedAccountIdForUpdate(
                request.bankTransactionId(), activeLinkedAccountId(contractId));
        if (deposit.getTransactionType() != BankTransactionType.DEPOSIT
                || deposit.getProcessingStatus() != BankTransactionProcessingStatus.UNMATCHED
                || !isEligibleDeposit(contract, deposit)) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_DEPOSIT_INVALID.toException();
        }


        LocalDate depositDate = deposit.getTransactionAt().toLocalDate();
        if (findLatestPaidOn(contractId).filter(depositDate::isBefore).isPresent()) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_DEPOSIT_OUT_OF_ORDER.toException();
        }

        // 2. 끝나지 않은 회차를 부족 회차와 앞으로 낼 회차로 나눈다.
        List<RepaymentSchedule> all = repaymentScheduleRepository.findByContractIdForUpdate(contractId);
        List<RepaymentSchedule> unresolved = unresolvedRows(all);
        Map<Long, BigDecimal> paid = confirmedAmounts(unresolved);
        List<RepaymentSchedule> shortfallRows = shortfallRows(unresolved, paid);
        List<RepaymentSchedule> futureRows = futureRows(unresolved, shortfallRows);
        if (futureRows.isEmpty()) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_NOT_ALLOWED.toException();
        }

        // 3. 입금액을 부족 회차 → 경과 이자 → 원금 순서로 나눈다.
        RepaymentSchedule first = futureRows.get(0);
        LocalDate paidOn = deposit.getTransactionAt().toLocalDate();
        BigDecimal shortfall = shortfallOf(shortfallRows, paid);
        BigDecimal accrued = accruedInterest(contract, all, first, paidOn);
        BigDecimal principalPart = deposit.getAmount().subtract(shortfall).subtract(accrued);
        BigDecimal balance = balanceOf(first);
        if (principalPart.signum() <= 0 || principalPart.compareTo(balance) > 0) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_AMOUNT_INVALID.toException();
        }

        LocalDateTime now = LocalDateTime.now();
        shortfallRows.forEach(schedule -> schedule.fullPayment(now));

        // 4. 전액이면 끝내고, 일부면 입금일부터 새 원금으로 이자를 다시 계산한다.
        if (principalPart.compareTo(balance) == 0) {
            first.reschedule(principalPart, accrued, BigDecimal.ZERO);
            first.fullPayment(now);
            repaymentScheduleRepository.deleteAll(futureRows.subList(1, futureRows.size()));
        } else {
            recalculate(contract, contractId, futureRows, balance.subtract(principalPart), paidOn);
        }

        deposit.changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);

        EarlyRepayment saved = earlyRepaymentRepository.save(new EarlyRepayment(
                contractId, request.bankTransactionId(), deposit.getAmount(),
                shortfall, accrued, BigDecimal.ZERO, principalPart, now));
        return EarlyRepaymentResponse.from(saved);
    }

    private void recalculate(LoanContractResponse contract, Long contractId,
                             List<RepaymentSchedule> futureRows, BigDecimal newPrincipal, LocalDate paidOn) {
        List<RepaymentSchedule> recalculated = repaymentScheduleGenerateService.generateRemaining(
                contractId, contract.getRepaymentType(), newPrincipal, contract.getInterestRate(),
                futureRows.stream().map(RepaymentSchedule::getDueDate).toList(), paidOn);

        for (int i = 0; i < futureRows.size(); i++) {
            RepaymentSchedule row = recalculated.get(i);
            futureRows.get(i).reschedule(row.getPrincipalDue(), row.getInterestDue(), row.getRemainingPrincipal());
        }
    }

    private List<RepaymentSchedule> unresolvedRows(List<RepaymentSchedule> all) {
        return all.stream()
                .filter(schedule -> schedule.getStatus().isUnresolved())
                .sorted(Comparator.comparing(RepaymentSchedule::getSequence))
                .toList();
    }

    private List<RepaymentSchedule> shortfallRows(List<RepaymentSchedule> unresolved, Map<Long, BigDecimal> paid) {
        return unresolved.stream()
                .filter(schedule -> schedule.getStatus() == RepaymentScheduleStatus.OVERDUE
                        || paid.getOrDefault(schedule.getScheduleId(), BigDecimal.ZERO).signum() > 0)
                .toList();
    }

    private List<RepaymentSchedule> futureRows(List<RepaymentSchedule> unresolved, List<RepaymentSchedule> shortfallRows) {
        return unresolved.stream()
                .filter(schedule -> !shortfallRows.contains(schedule))
                .toList();
    }

    private BigDecimal shortfallOf(List<RepaymentSchedule> shortfallRows, Map<Long, BigDecimal> paid) {
        return shortfallRows.stream()
                .map(schedule -> schedule.getTotalPaymentDue()
                        .subtract(paid.getOrDefault(schedule.getScheduleId(), BigDecimal.ZERO)))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal balanceOf(RepaymentSchedule first) {
        return first.getRemainingPrincipal().add(first.getPrincipalDue());
    }

    private BigDecimal accruedInterest(LoanContractResponse contract, List<RepaymentSchedule> all,
                                       RepaymentSchedule first, LocalDate paidOn) {
        LocalDate previousDue = all.stream()
                .filter(schedule -> schedule.getSequence() == first.getSequence() - 1)
                .map(RepaymentSchedule::getDueDate)
                .findFirst()
                .orElse(contract.getStartDate());
        LocalDate lastEarly = findLatestPaidOn(contract.getContractId()).orElse(previousDue);
        LocalDate from = lastEarly.isAfter(previousDue) ? lastEarly : previousDue;
        return repaymentScheduleGenerateService.calculateDailyInterest(
                balanceOf(first), contract.getInterestRate(), from, paidOn);
    }

    private Map<Long, BigDecimal> confirmedAmounts(List<RepaymentSchedule> schedules) {
        return paymentRecordService.findConfirmedRecordsByTargetIds(
                        PaymentTargetType.LOAN,
                        schedules.stream().map(RepaymentSchedule::getScheduleId).toList())
                .stream()
                .collect(Collectors.groupingBy(
                        PaymentRecord::getTargetId,
                        Collectors.reducing(BigDecimal.ZERO, PaymentRecord::getAmount, BigDecimal::add)));
    }

    private Long activeLinkedAccountId(Long contractId) {
        return contractAccountRepository.findLatestByContractIdAndStatus(contractId, ContractAccountStatus.ACTIVE)
                .orElseThrow(RepaymentScheduleErrorCode.EARLY_REPAYMENT_NOT_ALLOWED::toException)
                .getLinkedAccount()
                .getLinkedAccountId();
    }

    private LoanContractResponse validateCreditor(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        if (!contract.isCreditor(userId)) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_FORBIDDEN.toException();
        }
        if (contract.getStatus() != ContractStatus.COMPLETED) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_NOT_ALLOWED.toException();
        }
        return contract;
    }

    private boolean isEligibleDeposit(LoanContractResponse contract, BankTransaction deposit) {
        return normalizeName(contract.getDebtorName()).equals(normalizeName(deposit.getCounterpartyName()))
                && !deposit.getTransactionAt().toLocalDate().isBefore(contract.getStartDate());
    }


    private String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }
}