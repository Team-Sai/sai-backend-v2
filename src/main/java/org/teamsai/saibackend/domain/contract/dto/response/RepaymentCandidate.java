package org.teamsai.saibackend.domain.contract.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

public record RepaymentCandidate(
        Long contractId,
        Long scheduleId,
        String contractName,
        LocalDate dueDate,
        BigDecimal remainingAmount,
        boolean pastDue
) {
}