package org.teamsai.saibackend.domain.settlement.support;

import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SettlementCycleNumbers {
    private SettlementCycleNumbers() {}

    public static Map<Long, Integer> index(List<Settlement> settlements) {
        Map<Long, Integer> counts = new HashMap<>();
        Map<Long, Integer> result = new HashMap<>();
        settlements.stream()
                .sorted(Comparator.comparing(Settlement::getCycleDate,
                                Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(Settlement::getSettlementId))
                .forEach(settlement -> {
                    Long recurringId = settlement.getRecurringSettlement().getRecurringSettlementId();
                    result.put(settlement.getSettlementId(), counts.merge(recurringId, 1, Integer::sum));
                });
        return result;
    }
}
