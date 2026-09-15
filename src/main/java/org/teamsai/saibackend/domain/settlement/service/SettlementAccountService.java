package org.teamsai.saibackend.domain.settlement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementAccountResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAccount;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAccountRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.type.SettlementAccountStatus;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementAccountService {
    private final SettlementRepository settlementRepository;
    private final SettlementAccountRepository settlementAccountRepository;
    private final LinkedBankAccountService linkedBankAccountService;
    private final SettlementValidator settlementValidator;

    @Transactional
    public SettlementAccountResponse selectAccount(
            Long userId,
            Long settlementId,
            Long linkedAccountId
    ) {
        log.info(
                "[정산계좌] 설정 시작 userId={}, settlementId={}, linkedAccountId={}",
                userId,
                settlementId,
                linkedAccountId
        );

        Settlement settlement = findSettlement(settlementId);

        settlementValidator.validateOwner(
                settlement,
                userId
        );

        settlementValidator.validateLinkedAccountOwner(
                userId,
                linkedAccountId
        );

        Settlement lockedSettlement = settlementRepository
                .findByIdForUpdate(settlementId)
                .orElseThrow(
                        SettlementErrorCode.SETTLEMENT_NOT_FOUND::toException
                );

        Optional<SettlementAccount> currentAccount =
                settlementAccountRepository
                        .findBySettlementIdAndStatusForUpdate(
                                settlementId,
                                SettlementAccountStatus.ACTIVE
                        );

        if (currentAccount.isPresent()
                && Objects.equals(
                currentAccount.get().getLinkedAccountId(),
                linkedAccountId
        )) {

            log.info(
                    "[정산계좌] 이미 동일 계좌 설정됨 settlementId={}",
                    settlementId
            );

            return toResponse(
                    userId,
                    currentAccount.get()
            );
        }

        LocalDateTime now = LocalDateTime.now();

        currentAccount.ifPresent(
                account -> account.replace(now)
        );

        SettlementAccount newAccount =
                SettlementAccount.create(
                        lockedSettlement,
                        linkedAccountId,
                        now
                );

        SettlementAccount savedAccount =
                settlementAccountRepository.save(newAccount);

        return toResponse(
                userId,
                savedAccount
        );
    }

    private SettlementAccountResponse toResponse(
            Long userId,
            SettlementAccount settlementAccount
    ) {
        LinkedBankAccountResponse linkedAccount =
                linkedBankAccountService
                        .getLinkedAccounts(userId)
                        .stream()
                        .filter(account ->
                                Objects.equals(
                                        account.linkedAccountId(),
                                        settlementAccount.getLinkedAccountId()
                                )
                        )
                        .findFirst()
                        .orElseThrow(
                                SettlementErrorCode
                                        .INVALID_SETTLEMENT_ACCOUNT
                                        ::toException
                        );

        return SettlementAccountResponse.from(
                settlementAccount,
                linkedAccount
        );
    }


    @Transactional(readOnly = true)
    public SettlementAccountResponse findCurrentAccount(
            Long userId,
            Long settlementId
    ) {
        Settlement settlement = findSettlement(settlementId);

        settlementValidator.validateAccessibleUser(
                settlement,
                userId
        );

        SettlementAccount account =
                settlementAccountRepository
                        .findBySettlementIdAndStatus(
                                settlementId,
                                SettlementAccountStatus.ACTIVE
                        )
                        .orElseThrow(
                                SettlementErrorCode
                                        .SETTLEMENT_ACCOUNT_NOT_FOUND
                                        ::toException
                        );

        return toResponse(
                settlement.getOwner().getUserId(),
                account
        );
    }


    private Settlement findSettlement(Long settlementId){
        return settlementRepository.findById(settlementId).orElseThrow(SettlementErrorCode.SETTLEMENT_NOT_FOUND::toException);
    }

}