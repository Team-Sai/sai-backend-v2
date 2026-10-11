package org.teamsai.saibackend.domain.calendar;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.request.PreparationFundingRequest;
import org.teamsai.saibackend.domain.calendar.calculator.PreparationFundingAssessmentCalculator;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PreparationFundingAssessmentCalculatorTest {

    private final PreparationFundingAssessmentCalculator service =
            new PreparationFundingAssessmentCalculator();

    private final LocalDate today = LocalDate.of(2026, 10, 10);
    private final YearMonth month = YearMonth.of(2026, 10);

    @Test
    void returnsNullWhenFundingIsNotProvided() {
        assertNull(service.assess(
                List.of(),
                null,
                today,
                month
        ));
    }

    @Test
    void calculatesBudgetAndDeadlineShortfall() {
        var result = service.assess(
                List.of(
                        candidate(1L, "2026-10-15", "100000"),
                        candidate(2L, "2026-10-28", "250000")
                ),
                funding("300000", "100000",
                        income("2026-10-25", "200000")),
                today,
                month
        );

        assertMoney("350000", result.totalRequiredAmount());
        assertMoney("50000", result.budgetShortfall());

        assertMoney("0", result.deadlines().get(0).shortfall());
        assertMoney("50000", result.deadlines().get(1).shortfall());
    }

    @Test
    void detectsFundsArrivingAfterDueDate() {
        var result = service.assess(
                List.of(candidate(1L, "2026-10-15", "100000")),
                funding("100000", "0",
                        income("2026-10-25", "100000")),
                today,
                month
        );

        assertMoney("0", result.budgetShortfall());
        assertMoney("100000", result.deadlines().get(0).shortfall());
    }

    @Test
    void capsAvailableFundsAtRemainingBudget() {
        var result = service.assess(
                List.of(candidate(1L, "2026-10-28", "150000")),
                funding("100000", "10000",
                        income("2026-10-25", "500000")),
                today,
                month
        );

        assertMoney("100000", result.deadlines().get(0).availableAmount());
        assertMoney("50000", result.deadlines().get(0).shortfall());
    }

    @Test
    void groupsOverdueAndTodayAmountsAtToday() {
        var result = service.assess(
                List.of(
                        candidate(1L, "2026-09-25", "80000"),
                        candidate(2L, "2026-10-10", "20000")
                ),
                funding("100000", "30000"),
                today,
                month
        );

        assertEquals(1, result.deadlines().size());
        assertEquals(today, result.deadlines().get(0).date());
        assertMoney("100000",
                result.deadlines().get(0).cumulativeRequiredAmount());
        assertMoney("70000", result.deadlines().get(0).shortfall());
    }

    @Test
    void rejectsExpectedIncomeThatIsAlreadyAvailable() {
        assertThrows(IllegalArgumentException.class, () ->
                service.assess(
                        List.of(),
                        funding("100000", "0",
                                income("2026-10-10", "100000")),
                        today,
                        month
                )
        );
    }

    @Test
    void rejectsIncomeOutsideTargetMonth() {
        assertThrows(IllegalArgumentException.class, () ->
                service.assess(
                        List.of(),
                        funding("100000", "0",
                                income("2026-11-01", "100000")),
                        today,
                        month
                )
        );
    }

    private RepaymentCandidate candidate(
            Long id,
            String dueDate,
            String amount
    ) {
        LocalDate due = LocalDate.parse(dueDate);

        return new RepaymentCandidate(
                id,
                id,
                "테스트",
                due,
                new BigDecimal(amount),
                due.isBefore(today)
        );
    }

    private PreparationFundingRequest funding(
            String budget,
            String available,
            PreparationFundingRequest.ExpectedIncome... income
    ) {
        return new PreparationFundingRequest(
                new BigDecimal(budget),
                new BigDecimal(available),
                List.of(income)
        );
    }

    private PreparationFundingRequest.ExpectedIncome income(
            String date,
            String amount
    ) {
        return new PreparationFundingRequest.ExpectedIncome(
                LocalDate.parse(date),
                new BigDecimal(amount)
        );
    }

    private void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}