package org.teamsai.saibackend.domain.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringSettlementCycleResponse {

    private Long settlementId;

    private int cycleNo;

    private LocalDate cycleDate;

    private SettlementStatus settlementStatus;

    private LocalDateTime closedAt;

    private BigDecimal totalExpectedAmount;
    private BigDecimal totalPaidAmount;
    private BigDecimal totalRemainingAmount;

    private long paidCount;
    private long partiallyPaidCount;
    private long unpaidCount;

    private BigDecimal progressRate;
}
