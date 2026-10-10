package org.teamsai.saibackend.domain.contract.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

public record RepaymentAnalysisContext(
        LocalDate analysisDate,
        YearMonth targetMonth,
        BigDecimal payableThisMonthAmount,
        BigDecimal overdueAmount,
        BigDecimal totalRequiredAmount,
        BigDecimal totalRemainingAmount,
        List<RepaymentCandidate> candidates
) {
}