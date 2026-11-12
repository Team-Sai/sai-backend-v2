package org.teamsai.saibackend.domain.account.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.dto.SyncAccountContext;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.account.repository.SyncAccountProjection;

@Service
@RequiredArgsConstructor
public class SyncAccountReadService {

    private final LinkedBankAccountRepository linkedBankAccountRepository;

    @Transactional(readOnly = true)
    public SyncAccountContext getContext(Long linkedAccountId){
        SyncAccountProjection projection =
                linkedBankAccountRepository
                        .findSyncAccountContext(linkedAccountId)
                        .orElseThrow(
                                AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND::toException
                        );

        return new SyncAccountContext(
                projection.getUserId(),
                projection.getBankAccountId(),
                projection.getUserKey(),
                projection.getLastSyncedTransactionId()
        );
    }
}
