package org.teamsai.saibackend.domain.contract.dto.response;

import java.time.Instant;

public record CachedRepaymentAnalysis(
        RepaymentAgentDraft draft,
        Instant analyzedAt
) {}