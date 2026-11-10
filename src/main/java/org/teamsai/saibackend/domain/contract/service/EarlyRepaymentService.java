package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.dto.request.EarlyRepaymentRequest;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentDepositResponse;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.PayoffResponse;
import org.teamsai.saibackend.domain.contract.entity.DepositReturn;
import org.teamsai.saibackend.domain.contract.entity.EarlyRepayment;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.exception.RepaymentScheduleErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractAccountRepository;
import org.teamsai.saibackend.domain.contract.repository.DepositReturnRepository;
import org.teamsai.saibackend.domain.contract.repository.EarlyRepaymentRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.type.ContractAccountStatus;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.SourceType;
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
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EarlyRepaymentService {

    // 차용증 번호: LC-12, 계약 변경본은 LC-12-15
    private static final Pattern CONTRACT_NUMBER_PATTERN = Pattern.compile("LC-\\d+(?:-\\d+)?");

    /** 메모에 차용증 번호(LC-…)가 적혀 있는지. 적혀 있으면 차용증 입금으로 본다. */
    public static boolean hasContractNumber(String memo) {
        return memo != null
                && CONTRACT_NUMBER_PATTERN.matcher(memo.toUpperCase().replaceAll("\\s+", "")).find();
    }
    private final RepaymentScheduleRepository repaymentScheduleRepository;
    private final EarlyRepaymentRepository earlyRepaymentRepository;
    private final DepositReturnRepository depositReturnRepository;
    private final LoanContractService loanContractService;
    private final RepaymentScheduleGenerateService repaymentScheduleGenerateService;
    private final ContractAccountRepository contractAccountRepository;
    private final BankTransactionService bankTransactionService;
    private final PaymentRecordService paymentRecordService;

    /** 자동 매칭용: 메모에 다른 차용증 번호가 적혀 있으면 false. 번호가 없거나 이 차용증이면 true */
    public boolean memoAllowsSchedule(Long scheduleId, String memo) {
        if (memo == null) {
            return true;
        }
        return repaymentScheduleRepository.findById(scheduleId)
                .map(RepaymentSchedule::getContractId)
                .map(contractId -> {
                    Long creditorId = loanContractService.getContractForInternalUse(contractId).getCreditorId();
                    // 차용증 번호(LC-…)는 당사자 조회에서만 붙어서, 채권자 기준으로 다시 읽는다.
                    LoanContractResponse contract = loanContractService.findContract(contractId, creditorId);
                    return !Boolean.FALSE.equals(memoMatchesText(contract, memo));
                })
                .orElse(true);
    }


    public Optional<LocalDate> findLatestPaidOn(Long contractId) {
        // 원금을 실제로 갚은 기록만 이자 시작일로 쓴다. (밀린 회차만 낸 기록은 제외)
        return earlyRepaymentRepository
                .findFirstByContractIdAndPrincipalAmountGreaterThanOrderByProcessedAtDesc(contractId, BigDecimal.ZERO)                .flatMap(early -> bankTransactionService.findById(early.getBankTransactionId()))
                .map(tx -> tx.getTransactionAt().toLocalDate());
    }

    public List<EarlyRepaymentDepositResponse> getDeposits(Long contractId, Long userId) {
        LoanContractResponse contract = validateParty(contractId, userId);
        List<RepaymentSchedule> all = repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId);
        List<RepaymentSchedule> unresolved = unresolvedRows(all);
        Map<Long, BigDecimal> paid = confirmedAmounts(unresolved);
        List<RepaymentSchedule> shortfallRows = shortfallRows(unresolved, paid);
        List<RepaymentSchedule> futureRows = futureRows(unresolved, shortfallRows);

        if (futureRows.isEmpty() && shortfallRows.isEmpty()) {
            return List.of();
        }

        RepaymentSchedule first = futureRows.isEmpty() ? null : futureRows.get(0);
        BigDecimal shortfall = shortfallOf(shortfallRows, paid);
        BigDecimal balance = first == null ? BigDecimal.ZERO : balanceOf(first);

        return bankTransactionService.findUnmatchedDepositsByLinkedAccountId(activeLinkedAccountId(contractId))
                .stream()
                .filter(tx -> isEligibleDeposit(contract, tx))
                .map(tx -> EarlyRepaymentDepositResponse.of(
                        tx, shortfall,
                        first == null ? BigDecimal.ZERO
                                : accruedInterest(contract, all, first, tx.getTransactionAt().toLocalDate()),
                        balance,
                        Boolean.TRUE.equals(memoMatches(contract, tx))))
                .toList();
    }

    @Transactional
    public EarlyRepaymentResponse process(Long contractId, Long userId, EarlyRepaymentRequest request) {
        LoanContractResponse contract = validateCreditor(contractId, userId);

        // 1. 같은 입금을 두 번 처리하지 못하게 입금부터 잠근다.
        BankTransaction deposit = lockEligibleDeposit(contract, contractId, request.bankTransactionId());

        LocalDate depositDate = deposit.getTransactionAt().toLocalDate();
        if (findLatestPaidOn(contractId).filter(depositDate::isBefore).isPresent()) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_DEPOSIT_OUT_OF_ORDER.toException();
        }

        // 2. 끝나지 않은 회차를 밀린 회차와 앞으로 낼 회차로 나눈다.
        List<RepaymentSchedule> all = repaymentScheduleRepository.findByContractIdForUpdate(contractId);
        List<RepaymentSchedule> unresolved = unresolvedRows(all);
        Map<Long, BigDecimal> paid = confirmedAmounts(unresolved);
        List<RepaymentSchedule> shortfallRows = shortfallRows(unresolved, paid);
        List<RepaymentSchedule> futureRows = futureRows(unresolved, shortfallRows);

        LocalDate paidOn = deposit.getTransactionAt().toLocalDate();
        BigDecimal shortfall = shortfallOf(shortfallRows, paid);
        LocalDateTime now = LocalDateTime.now();

        RepaymentSchedule first = futureRows.isEmpty() ? null : futureRows.get(0);
        BigDecimal accrued = first == null ? BigDecimal.ZERO : accruedInterest(contract, all, first, paidOn);
        BigDecimal amount = deposit.getAmount();

        // 3-1. 원금을 갚기엔 모자라면 밀린 회차만 앞에서부터 채운다. 남는 돈은 반환으로 남긴다.
        if (shortfall.signum() > 0 && (first == null || amount.compareTo(shortfall.add(accrued)) <= 0)) {
            BigDecimal toArrears = amount.min(shortfall);
            BigDecimal leftover = amount.subtract(toArrears);
            if (leftover.signum() > 0 && !request.returnExcess()) {
                throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_AMOUNT_INVALID.toException();
            }

            fillArrears(shortfallRows, paid, toArrears, request.bankTransactionId(), now);
            deposit.changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);

            EarlyRepayment saved = earlyRepaymentRepository.save(new EarlyRepayment(
                    contractId, request.bankTransactionId(), amount,
                    toArrears, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, now));
            if (leftover.signum() > 0) {
                depositReturnRepository.save(new DepositReturn(
                        contractId, request.bankTransactionId(), leftover, deposit.getTransactionAt(), now));
            }
            return EarlyRepaymentResponse.from(saved);
        }

        if (first == null) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_NOT_ALLOWED.toException();
        }

        // 3-2. 입금액을 밀린 회차 → 경과 이자 → 원금 순서로 나눈다.
        BigDecimal principalPart = deposit.getAmount().subtract(shortfall).subtract(accrued);
        BigDecimal balance = balanceOf(first);

        // 넘친 금액을 반환하기로 했으면 남은 원금까지만 갚고 나머지는 반환으로 남긴다.
        BigDecimal excess = BigDecimal.ZERO;
        if (request.returnExcess() && principalPart.compareTo(balance) > 0) {
            excess = principalPart.subtract(balance);
            principalPart = balance;
        }
        if (principalPart.signum() <= 0 || principalPart.compareTo(balance) > 0) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_AMOUNT_INVALID.toException();
        }

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
        if (excess.signum() > 0) {
            depositReturnRepository.save(new DepositReturn(
                    contractId, request.bankTransactionId(), excess,
                    deposit.getTransactionAt(), now));
        }

        return EarlyRepaymentResponse.from(saved);
    }

    /** 채권자가 처리할 수 없는 입금을 직접 돌려준 뒤 반환했다고 표시한다. */
    @Transactional
    public void markReturned(Long contractId, Long userId, Long bankTransactionId) {
        LoanContractResponse contract = validateCreditor(contractId, userId);
        BankTransaction deposit = lockEligibleDeposit(contract, contractId, bankTransactionId);

        // 재시도 배치가 다시 매칭하지 않도록 처리 끝 상태로 둔다. 반환 사실은 deposit_return에 남긴다.
        deposit.changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
        depositReturnRepository.save(new DepositReturn(
                contractId, bankTransactionId, deposit.getAmount(),
                deposit.getTransactionAt(), LocalDateTime.now()));
    }

    /** 오늘 한 번에 갚을 금액. 남은 회차가 없으면 null */
    public PayoffResponse calculateTodayPayoff(LoanContractResponse contract, List<RepaymentSchedule> all) {
        return calculatePayoff(contract, all, LocalDate.now());
    }

    /** 이 회차가 속한 계약을 그날 한 번에 갚는 금액. 남은 회차가 없으면 null */
    public BigDecimal payoffAmountOfSchedule(Long scheduleId, LocalDate on) {
        return repaymentScheduleRepository.findById(scheduleId)
                .map(RepaymentSchedule::getContractId)
                .map(contractId -> calculatePayoff(
                        loanContractService.getContractForInternalUse(contractId),
                        repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId),
                        on))
                .map(PayoffResponse::total)
                .orElse(null);
    }

    private PayoffResponse calculatePayoff(LoanContractResponse contract, List<RepaymentSchedule> all, LocalDate on) {
        List<RepaymentSchedule> unresolved = unresolvedRows(all);
        Map<Long, BigDecimal> paid = confirmedAmounts(unresolved);
        List<RepaymentSchedule> shortfallRows = shortfallRows(unresolved, paid);
        List<RepaymentSchedule> futureRows = futureRows(unresolved, shortfallRows);

        if (futureRows.isEmpty()) {
            // 앞으로 낼 회차가 없으면 밀린 금액만 남는다. 그것도 없으면 다 갚은 것이다.
            BigDecimal shortfall = shortfallOf(shortfallRows, paid);
            return shortfall.signum() > 0
                    ? new PayoffResponse(BigDecimal.ZERO, BigDecimal.ZERO, shortfall, shortfall)
                    : null;
        }

        RepaymentSchedule first = futureRows.get(0);
        BigDecimal principal = balanceOf(first);
        BigDecimal interest = accruedInterest(contract, all, first, on);        BigDecimal shortfall = shortfallOf(shortfallRows, paid);
        return new PayoffResponse(principal, interest, shortfall, principal.add(interest).add(shortfall));
    }

    private BankTransaction lockEligibleDeposit(LoanContractResponse contract, Long contractId, Long bankTransactionId) {
        BankTransaction deposit = bankTransactionService.findByIdAndLinkedAccountIdForUpdate(
                bankTransactionId, activeLinkedAccountId(contractId));
        if (deposit.getTransactionType() != BankTransactionType.DEPOSIT
                || !isOpenStatus(deposit.getProcessingStatus())
                || !isEligibleDeposit(contract, deposit)) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_DEPOSIT_INVALID.toException();
        }
        return deposit;
    }

    // 대기(아직 분류 전)와 미매칭 입금만 처리할 수 있다. 검토 목록(NEEDS_CHECK)은 그쪽에서 고른다.
    private boolean isOpenStatus(BankTransactionProcessingStatus status) {
        return status == BankTransactionProcessingStatus.PENDING
                || status == BankTransactionProcessingStatus.UNMATCHED;
    }

    /** 밀린 회차를 앞에서부터 채운다. 다 채운 회차는 완료, 덜 채운 마지막 회차 하나만 납부 기록을 남긴다. */
    private void fillArrears(List<RepaymentSchedule> shortfallRows, Map<Long, BigDecimal> paid,
                             BigDecimal amount, Long bankTransactionId, LocalDateTime now) {
        BigDecimal remaining = amount;

        for (RepaymentSchedule row : shortfallRows) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal owed = row.getTotalPaymentDue()
                    .subtract(paid.getOrDefault(row.getScheduleId(), BigDecimal.ZERO));

            if (remaining.compareTo(owed) >= 0) {
                row.fullPayment(now);
                remaining = remaining.subtract(owed);
            } else {
                paymentRecordService.createConfirmedRecord(
                        bankTransactionId, PaymentTargetType.LOAN, row.getScheduleId(), remaining, SourceType.MANUAL);
                remaining = BigDecimal.ZERO;
            }
        }
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
                .orElseThrow(RepaymentScheduleErrorCode.EARLY_REPAYMENT_ACCOUNT_MISSING::toException)
                .getLinkedAccount()
                .getLinkedAccountId();
    }

    /** 조회는 계약 당사자 모두 할 수 있다. */
    private LoanContractResponse validateParty(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        if (contract.getStatus() != ContractStatus.COMPLETED) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_CONTRACT_NOT_ACTIVE.toException();
        }
        return contract;
    }


    private LoanContractResponse validateCreditor(Long contractId, Long userId) {
        LoanContractResponse contract = loanContractService.findContract(contractId, userId);
        if (!contract.isCreditor(userId)) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_FORBIDDEN.toException();
        }
        if (contract.getStatus() != ContractStatus.COMPLETED) {
            throw RepaymentScheduleErrorCode.EARLY_REPAYMENT_CONTRACT_NOT_ACTIVE.toException();
        }
        return contract;
    }

    private boolean isEligibleDeposit(LoanContractResponse contract, BankTransaction deposit) {
        return normalizeName(contract.getDebtorName()).equals(normalizeName(deposit.getCounterpartyName()))
                && !deposit.getTransactionAt().toLocalDate().isBefore(contract.getStartDate())
                && !Boolean.FALSE.equals(memoMatches(contract, deposit)); // 다른 차용증 번호가 적힌 입금은 제외
    }

    /**
     * 받는 분 메모의 차용증 번호로 이 계약의 입금인지 본다.
     * true: 이 차용증 번호 / false: 다른 차용증 번호 / null: 번호 없음(알 수 없음)
     */
    private Boolean memoMatches(LoanContractResponse contract, BankTransaction deposit) {
        return memoMatchesText(contract, deposit.getMemo());
    }

    private Boolean memoMatchesText(LoanContractResponse contract, String memo) {
        if (memo == null) {
            return null;
        }

        List<String> numbers = CONTRACT_NUMBER_PATTERN.matcher(memo.toUpperCase().replaceAll("\\s+", ""))
                .results()
                .map(MatchResult::group)
                .toList();
        if (numbers.isEmpty()) {
            return null;
        }

        String mine = contract.getContractDisplayId();
        if (mine == null) {
            return null; // 차용증 번호를 모르면 메모로 판단하지 않는다
        }
        return numbers.stream().anyMatch(number -> number.equals(mine) || mine.startsWith(number + "-"));
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "");
    }
}