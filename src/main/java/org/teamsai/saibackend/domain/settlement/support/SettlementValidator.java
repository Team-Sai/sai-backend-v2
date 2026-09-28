package org.teamsai.saibackend.domain.settlement.support;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.settlement.dto.request.SettlementParticipantCreateRequest;
import org.teamsai.saibackend.domain.settlement.dto.request.SharedSettlementCreateRequest;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.settlement.type.SplitType;

import java.math.BigDecimal;

@Component
@RequiredArgsConstructor
public class SettlementValidator {

    private final LinkedBankAccountService linkedBankAccountService;
    private final SettlementParticipantRepository settlementParticipantRepository;
    private final SettlementParticipantValidator participantValidator;

    public void validateOwner(Settlement settlement, Long userId){
        if(!settlement.getOwner().getUserId().equals(userId)){
            throw SettlementErrorCode.SETTLEMENT_ACCESS_DENIED.toException();
        }
    }

    public void validateLinkedAccountOwner(Long userId, Long linkedAccountId
    ) {
        if (!linkedBankAccountService.isOwnedLinkedAccount(userId, linkedAccountId)) {
            throw SettlementErrorCode.INVALID_SETTLEMENT_ACCOUNT.toException();
        }
    }

    public void validateCreateRequest(SharedSettlementCreateRequest request) {
        if (request == null) {
            throw SettlementErrorCode.INVALID_SETTLEMENT_REQUEST.toException();
        }

        participantValidator.validateParticipants(request.getParticipants());

        if (request.getSplitType() == SplitType.CUSTOM){
            validateCustomAmounts(request);
        }
    }

    public void validateAccessibleUser(
            Settlement settlement,
            Long userId
    ) {
        if (settlement.getOwner().getUserId().equals(userId)) {
            return;
        }

        boolean isParticipant =
                settlementParticipantRepository.existsActiveParticipant(
                        settlement.getSettlementId(),
                        userId,
                        SettlementParticipantStatus.ACTIVE
                );

        if (!isParticipant) {
            throw SettlementErrorCode
                    .SETTLEMENT_ACCESS_DENIED
                    .toException();
        }
    }

    private void validateCustomAmounts(SharedSettlementCreateRequest request){
        BigDecimal ownerAmount = request.getOwnerAmount();

        if (ownerAmount == null || ownerAmount.compareTo(BigDecimal.ZERO) < 0){
            throw SettlementErrorCode.INVALID_SETTLEMENT_AMOUNT.toException();
        }

        BigDecimal total = ownerAmount;
        for (SettlementParticipantCreateRequest participant : request.getParticipants()){
            BigDecimal amount = participant.getAmount();

            if(amount == null || amount.compareTo(BigDecimal.ZERO) <= 0){
                throw SettlementErrorCode.INVALID_SETTLEMENT_AMOUNT.toException();
            }

            total = total.add(amount);
        }
        if (total.compareTo(request.getTotalAmount())!=0){
            throw SettlementErrorCode.INVALID_SETTLEMENT_AMOUNT.toException();
        }
    }
}
