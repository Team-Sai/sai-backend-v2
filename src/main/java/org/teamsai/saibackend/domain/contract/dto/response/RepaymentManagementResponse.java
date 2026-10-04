package org.teamsai.saibackend.domain.contract.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record RepaymentManagementResponse(
        RepaymentAnalysisContext context,
        AgentAnalysis agentAnalysis
) {
    public record AgentAnalysis(
            String source,
            String status,
            String summary,
            List<PlanItem> plans,
            String recommendation
    ) {
    }

    public record PlanItem(
            int priority,
            Long contractId,
            Long scheduleId,
            String contractName,
            LocalDate dueDate,
            BigDecimal amount,
            boolean pastDue,
            String reason
    ) {
    }
}