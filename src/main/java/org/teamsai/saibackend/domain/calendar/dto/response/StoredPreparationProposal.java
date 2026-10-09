package org.teamsai.saibackend.domain.calendar.dto.response;

import org.teamsai.saibackend.domain.calendar.dto.request.PreparationProposalRequest;

import java.util.List;

public record StoredPreparationProposal(
        PreparationProposalRequest request,
        List<PreparationProposalItem> items,
        PreparationRescheduleTarget rescheduleTarget
) {
    public StoredPreparationProposal(
            PreparationProposalRequest request,
            List<PreparationProposalItem> items
    ) {
        this(request, items, null);
    }
}