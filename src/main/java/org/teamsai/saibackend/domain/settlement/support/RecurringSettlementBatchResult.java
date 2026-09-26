package org.teamsai.saibackend.domain.settlement.support;

import java.util.List;

public record RecurringSettlementBatchResult(
        int totalCandidates,
        int succeeded,
        int failed,
        List<Long> failedRecurringIds
) {
}