package org.teamsai.saibackend.domain.settlement.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.settlement.type.CycleRule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecurringSettlementCycleListResponse {

    private Long recurringSettlementId;

    private String title;

    private String settlementCategory;

    private String role;

    private CycleRule cycleRule;

    private BigDecimal totalAmount;

    private LocalDate startDate;

    private LocalDate endDate;

    private int totalCycleCount;

    private List<RecurringSettlementCycleResponse> cycles;
}
