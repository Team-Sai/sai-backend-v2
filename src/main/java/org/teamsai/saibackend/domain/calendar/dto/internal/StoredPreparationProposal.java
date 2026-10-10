package org.teamsai.saibackend.domain.calendar.dto.internal;

import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationProposalItemResponse;
import org.teamsai.saibackend.domain.calendar.dto.response.PreparationRescheduleTargetResponse;

import java.util.List;

public record StoredPreparationProposal(
        PreparationProposalRequest request,
        List<PreparationProposalItemResponse> items,
        PreparationRescheduleTargetResponse rescheduleTarget
) {
    public StoredPreparationProposal(
            PreparationProposalRequest request,
            List<PreparationProposalItemResponse> items
    ) {
        this(request, items, null);
    }
}