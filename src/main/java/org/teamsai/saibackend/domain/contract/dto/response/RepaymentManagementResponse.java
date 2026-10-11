package org.teamsai.saibackend.domain.contract.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record RepaymentManagementResponse(
        RepaymentAnalysisContext context,
        AgentAnalysis agentAnalysis,
        Metadata metadata
) {

    public record Metadata(
            Instant analyzedAt,
            Instant checkedAt,
            boolean reused,
            String delivery,
            String fallbackReason,
            Integer retryAfterSeconds
    ) {}

    public record AgentAnalysis(
            String source,
            String status,
            String summary,
            List<PlanItem> plans,
            String recommendation
    ) {}

    public record PlanItem(
            int priority,
            Long contractId,
            Long scheduleId,
            String contractName,
            LocalDate dueDate,
            BigDecimal amount,
            boolean pastDue,
            String reason
    ) {}
}