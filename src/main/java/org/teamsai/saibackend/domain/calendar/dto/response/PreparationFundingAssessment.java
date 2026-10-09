package org.teamsai.saibackend.domain.calendar.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PreparationFundingAssessment(
        LocalDate analysisDate,
        BigDecimal totalRequiredAmount,
        BigDecimal remainingMonthlyBudget,
        BigDecimal budgetShortfall,
        List<DeadlineAssessment> deadlines
) {
    public record DeadlineAssessment(
            LocalDate date,
            BigDecimal cumulativeRequiredAmount,
            BigDecimal availableAmount,
            BigDecimal shortfall
    ) {
    }
}