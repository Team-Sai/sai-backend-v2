package org.teamsai.saibackend.domain.settlement.support;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSettlementParticipantRequest;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Component
public class SettlementParticipantValidator {

    public void validateParticipants(
            List<CreateSettlementParticipantRequest> participants
    ) {
        if (participants == null || participants.isEmpty()) {
            throw SettlementErrorCode.SETTLEMENT_PARTICIPANT_REQUIRED
                    .toException();
        }

        Set<String> userTokens = new HashSet<>();

        for (CreateSettlementParticipantRequest participant : participants) {
            if (participant == null
                    || !StringUtils.hasText(participant.getUserToken())) {
                throw SettlementErrorCode.INVALID_SETTLEMENT_PARTICIPANT
                        .toException();
            }

            if (!userTokens.add(participant.getUserToken())) {
                throw SettlementErrorCode.DUPLICATE_SETTLEMENT_PARTICIPANT
                        .toException();
            }
        }
    }
}
