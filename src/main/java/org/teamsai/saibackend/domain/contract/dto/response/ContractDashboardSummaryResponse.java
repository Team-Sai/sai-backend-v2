package org.teamsai.saibackend.domain.contract.dto.response;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class ContractDashboardSummaryResponse {

    private int totalContractCount;
    private BigDecimal totalLentAmount;
    private BigDecimal totalBorrowedAmount;
    private int receivableCount;
    private int payableCount;

    private BigDecimal thisMonthDueAmount;
    private Integer dueMonth;

    private BigDecimal receivableThisMonthAmount;
    private Integer receivableDueMonth;

    private BigDecimal payableThisMonthAmount;
    private Integer payableDueMonth;

    // 받을 돈 중 이전 달까지의 미회수액
    private BigDecimal receivableOverdueAmount;

    // 갚을 돈 중 이전 달까지의 미상환액
    private BigDecimal payableOverdueAmount;

    // 이전 달 미상환액 + 이번 달 미상환액
    private BigDecimal payableTotalRequiredAmount;

    private LocalDate nearestDueDate;
    private String defaultFilter;
}
