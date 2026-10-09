package org.teamsai.saibackend.domain.calendar.service;

import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationAgentDraft;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningContext;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationPlanningViolation;

import java.time.Instant;
import java.util.List;

public interface PreparationPlanningAgent {

    PreparationAgentDraft generate(
            PreparationPlanningContext context,
            PreparationProposalRequest request,
            Instant now,
            PreparationAgentDraft previousDraft,
            List<PreparationPlanningViolation> feedback
    );
}