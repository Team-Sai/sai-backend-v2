package org.teamsai.saibackend.domain.calendar.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record PreparationProposalItem(
        Long contractId,
        Long scheduleId,
        String contractName,
        BigDecimal remainingAmount,
        LocalDate dueDate,
        boolean pastDue,
        Instant startsAt,
        Instant endsAt,
        String reason
) {
}