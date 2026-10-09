package org.teamsai.saibackend.domain.calendar.service;

import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationFundingRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationFundingAssessment;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

@Service
public class PreparationFundingAssessmentService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private static final BigDecimal MAX_INPUT =
            new BigDecimal("999999999999.99");

    public PreparationFundingAssessment assess(
            List<RepaymentCandidate> allCandidates,
            PreparationFundingRequest funding,
            LocalDate today,
            YearMonth targetMonth
    ) {
        if (funding == null) {
            return null;
        }

        Objects.requireNonNull(allCandidates);
        Objects.requireNonNull(today);
        Objects.requireNonNull(targetMonth);

        if (!YearMonth.from(today).equals(targetMonth)) {
            throw new IllegalArgumentException(
                    "예산 분석은 현재 월에 대해서만 지원합니다."
            );
        }

        validateFunding(funding, today, targetMonth);

        Map<LocalDate, BigDecimal> requiredByDate =
                new TreeMap<>();

        for (RepaymentCandidate candidate : allCandidates) {
            if (candidate == null
                    || candidate.dueDate() == null
                    || candidate.remainingAmount() == null
                    || candidate.remainingAmount().signum() < 0) {
                throw new IllegalArgumentException(
                        "상환 후보 정보가 올바르지 않습니다."
                );
            }

            if (candidate.dueDate().isAfter(targetMonth.atEndOfMonth())) {
                throw new IllegalArgumentException(
                        "다음 달 이후 회차가 분석 대상에 포함되었습니다."
                );
            }

            // 연체 회차는 오늘 필요한 금액으로 계산한다.
            // 계약의 원래 dueDate를 변경하지 않는다.
            LocalDate requiredDate =
                    candidate.dueDate().isBefore(today)
                            ? today
                            : candidate.dueDate();

            requiredByDate.merge(
                    requiredDate,
                    candidate.remainingAmount(),
                    BigDecimal::add
            );
        }

        BigDecimal totalRequiredAmount =
                requiredByDate.values().stream()
                        .reduce(ZERO, BigDecimal::add);

        BigDecimal budgetShortfall = positive(
                totalRequiredAmount.subtract(
                        funding.remainingMonthlyBudget()
                )
        );

        List<PreparationFundingAssessment.DeadlineAssessment>
                deadlines = new ArrayList<>();

        BigDecimal cumulativeRequired = ZERO;

        for (var entry : requiredByDate.entrySet()) {
            LocalDate date = entry.getKey();

            cumulativeRequired =
                    cumulativeRequired.add(entry.getValue());

            BigDecimal expectedByDate =
                    funding.expectedIncome().stream()
                            .filter(income ->
                                    !income.availableDate().isAfter(date)
                            )
                            .map(PreparationFundingRequest.ExpectedIncome::amount)
                            .reduce(ZERO, BigDecimal::add);

            BigDecimal availableAmount =
                    funding.availableNow()
                            .add(expectedByDate)
                            .min(funding.remainingMonthlyBudget());

            BigDecimal shortfall = positive(
                    cumulativeRequired.subtract(availableAmount)
            );

            deadlines.add(
                    new PreparationFundingAssessment.DeadlineAssessment(
                            date,
                            cumulativeRequired,
                            availableAmount,
                            shortfall
                    )
            );
        }

        return new PreparationFundingAssessment(
                today,
                totalRequiredAmount,
                funding.remainingMonthlyBudget(),
                budgetShortfall,
                List.copyOf(deadlines)
        );
    }

    private void validateFunding(
            PreparationFundingRequest funding,
            LocalDate today,
            YearMonth targetMonth
    ) {
        validateAmount(
                funding.remainingMonthlyBudget(),
                false,
                "남은 상환 예산"
        );

        validateAmount(
                funding.availableNow(),
                false,
                "현재 사용 가능액"
        );

        if (funding.availableNow()
                .compareTo(funding.remainingMonthlyBudget()) > 0) {
            throw new IllegalArgumentException(
                    "현재 사용 가능액은 남은 상환 예산 이하여야 합니다."
            );
        }

        if (funding.expectedIncome() == null
                || funding.expectedIncome().size() > 10) {
            throw new IllegalArgumentException(
                    "예정 자금은 최대 10건까지 입력하세요."
            );
        }

        for (var income : funding.expectedIncome()) {
            if (income == null || income.availableDate() == null) {
                throw new IllegalArgumentException(
                        "예정 자금 날짜를 입력하세요."
                );
            }

            validateAmount(income.amount(), true, "예정 자금");

            if (!income.availableDate().isAfter(today)
                    || !YearMonth.from(income.availableDate())
                    .equals(targetMonth)) {
                throw new IllegalArgumentException(
                        "예정 자금은 오늘 이후이며 대상 월 안이어야 합니다. "
                                + "현재 확보한 자금은 현재 사용 가능액에 포함하세요."
                );
            }
        }
    }

    private void validateAmount(
            BigDecimal amount,
            boolean positiveOnly,
            String label
    ) {
        if (amount == null
                || amount.signum() < 0
                || (positiveOnly && amount.signum() == 0)
                || amount.compareTo(MAX_INPUT) > 0
                || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException(
                    label + "의 금액 형식을 확인하세요."
            );
        }
    }

    private BigDecimal positive(BigDecimal amount) {
        return amount.max(ZERO);
    }
}