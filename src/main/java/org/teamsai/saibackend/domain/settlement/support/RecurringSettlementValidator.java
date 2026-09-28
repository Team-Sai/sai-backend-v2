package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.settlement.dto.request.RecurringSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;

@Component
@RequiredArgsConstructor
public class RecurringSettlementValidator {

    private final SettlementParticipantValidator participantValidator;

    public void validateCreateRequest(RecurringSettlementCreateRequest request) {
        if (request == null) {
            throw SettlementErrorCode.INVALID_SETTLEMENT_REQUEST.toException();
        }

        participantValidator.validateParticipants(request.getParticipants());
    }
}
