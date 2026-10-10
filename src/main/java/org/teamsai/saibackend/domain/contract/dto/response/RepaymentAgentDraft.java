package org.teamsai.saibackend.domain.contract.dto.response;

import java.util.List;

public record RepaymentAgentDraft(
        String summary,
        List<ActionExplanation> actions,
        String recommendation
) {
    public record ActionExplanation(
            Long scheduleId,
            String reason
    ) {
    }
}