package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateRecurringSettlementRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.CreateSettlementParticipantRequest;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;

import java.util.HashSet;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class RecurringSettlementValidator {

    private final SettlementParticipantValidator participantValidator;

    public void validateCreateRequest(CreateRecurringSettlementRequest request) {
        if (request == null) {
            throw SettlementErrorCode.INVALID_SETTLEMENT_REQUEST.toException();
        }

        participantValidator.validateParticipants(request.getParticipants());
    }
}
