package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.settlement.dto.request.RecurringSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.SettlementParticipantCreateRequest;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.type.SplitType;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class RecurringSettlementValidator {

    private final SettlementParticipantValidator participantValidator;

    public void validateCreateRequest(RecurringSettlementCreateRequest request) {
        if (request == null) {
            throw SettlementErrorCode.INVALID_SETTLEMENT_REQUEST.toException();
        }

        participantValidator.validateParticipants(request.getParticipants());

        if (request.getSplitType() == SplitType.CUSTOM) {
            validateCustomAmounts(request);
        }
    }

    private void validateCustomAmounts(RecurringSettlementCreateRequest request){
        BigDecimal ownerAmount = request.getOwnerAmount();

        if (ownerAmount == null || ownerAmount.compareTo(BigDecimal.ZERO) < 0){
            throw SettlementErrorCode.INVALID_OWNER_AMOUNT.toException();
        }

        BigDecimal total = ownerAmount;

        for (SettlementParticipantCreateRequest participant : request.getParticipants()){
            BigDecimal amount = participant.getAmount();

            if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0){
                throw SettlementErrorCode.INVALID_PARTICIPANT_AMOUNT.toException();
            }

            total = total.add(amount);
        }

        if (total.compareTo(request.getTotalAmount()) != 0){
            throw SettlementErrorCode.SETTLEMENT_AMOUNT_MISMATCH.toException();
        }
    }
}
