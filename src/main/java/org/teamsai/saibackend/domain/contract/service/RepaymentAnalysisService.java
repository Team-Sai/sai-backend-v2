package org.teamsai.saibackend.domain.contract.service;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.calculator.RepaymentAmountCalculator;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.util.VisibleContractSelector;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@Transactional(readOnly = true)
public class RepaymentAnalysisService {

    private final LoanContractService loanContractService;
    private final RepaymentScheduleService repaymentScheduleService;
    private final Clock clock;

    public RepaymentAnalysisService(
            LoanContractService loanContractService,
            RepaymentScheduleService repaymentScheduleService,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.loanContractService = loanContractService;
        this.repaymentScheduleService = repaymentScheduleService;
        this.clock = clock;
    }

    public RepaymentAnalysisContext analyze(Long userId) {
        Objects.requireNonNull(userId, "userId must not be null");

        LocalDate analysisDate = LocalDate.now(clock);
        YearMonth targetMonth = YearMonth.from(analysisDate);
        LocalDate nextMonthStart = targetMonth.plusMonths(1).atDay(1);

        List<LoanContractResponse> debtorContracts =
                VisibleContractSelector.select(
                                loanContractService.findContractsByUser(userId)
                        ).stream()
                        .filter(c -> userId.equals(c.getDebtorId()))
                        .toList();

        if (debtorContracts.isEmpty()) {
            return new RepaymentAnalysisContext(
                    analysisDate,
                    targetMonth,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    List.of()
            );
        }

        List<Long> contractIds = debtorContracts.stream()
                .map(LoanContractResponse::getContractId)
                .toList();

        Map<Long, List<RepaymentScheduleWithRemainingProjection>> scheduleMap =
                repaymentScheduleService.getSchedulesByContractIds(contractIds);

        List<RepaymentScheduleWithRemainingProjection> allSchedules =
                debtorContracts.stream()
                        .flatMap(c -> scheduleMap
                                .getOrDefault(c.getContractId(), List.of())
                                .stream())
                        .toList();

        BigDecimal thisMonthAmount =
                RepaymentAmountCalculator.thisMonthDue(
                        allSchedules, targetMonth);

        BigDecimal overdueAmount =
                RepaymentAmountCalculator.previousMonthsUnpaid(
                        allSchedules, targetMonth);

        BigDecimal totalRemainingAmount =
                RepaymentAmountCalculator.totalRemaining(allSchedules);

        List<RepaymentCandidate> candidates = debtorContracts.stream()
                .flatMap(contract -> scheduleMap
                        .getOrDefault(contract.getContractId(), List.of())
                        .stream()
                        .filter(s -> s.getStatus().isUnresolved())
                        .filter(s -> s.getDueDate().isBefore(nextMonthStart))
                        .filter(s ->
                                RepaymentAmountCalculator.remainingAmount(s)
                                        .signum() > 0)
                        .map(s -> new RepaymentCandidate(
                                contract.getContractId(),
                                s.getScheduleId(),
                                contract.getContractAlias(),
                                s.getDueDate(),
                                RepaymentAmountCalculator.remainingAmount(s),
                                s.getDueDate().isBefore(analysisDate)
                        )))
                .sorted(
                        Comparator.comparing(RepaymentCandidate::dueDate)
                                .thenComparing(RepaymentCandidate::contractId)
                                .thenComparing(RepaymentCandidate::scheduleId)
                )
                .toList();

        return new RepaymentAnalysisContext(
                analysisDate,
                targetMonth,
                thisMonthAmount,
                overdueAmount,
                thisMonthAmount.add(overdueAmount),
                totalRemainingAmount,
                candidates
        );
    }
}