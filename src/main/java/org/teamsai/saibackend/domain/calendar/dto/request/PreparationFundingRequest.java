package org.teamsai.saibackend.domain.calendar.dto.request;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PreparationFundingRequest(
        BigDecimal remainingMonthlyBudget,
        BigDecimal availableNow,
        List<ExpectedIncome> expectedIncome
) {
    public record ExpectedIncome(
            LocalDate availableDate,
            BigDecimal amount
    ) {
    }
}