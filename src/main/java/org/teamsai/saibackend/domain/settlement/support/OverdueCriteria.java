package org.teamsai.saibackend.domain.settlement.support;

import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.time.LocalDate;

@Component
public class OverdueCriteria {
    public boolean isOverdue(Settlement settlement, LocalDate baseDate, LocalDate referenceDate) {
        if (settlement.getSettlementStatus() != SettlementStatus.IN_PROGRESS) {
            return false;
        }
        return referenceDate != null && referenceDate.isBefore(baseDate);
    }

    public LocalDate resolveReferenceDate(Settlement settlement) {
        return switch (settlement.getSettlementType()) {
            case SHARED -> settlement.getDueDate();
            case RECURRING -> settlement.getCycleDate();
        };
    }
}
