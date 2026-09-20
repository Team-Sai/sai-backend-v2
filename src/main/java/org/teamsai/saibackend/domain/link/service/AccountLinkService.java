package org.teamsai.saibackend.domain.link.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.service.LinkedAccountWriter;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AccountLinkService {
    private final UserRepository userRepository;
    private final LinkedAccountWriter linkedAccountWriter;
    private final LinkOperationStore operations;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completeLink(Long userId, String userKey, String expectedPreviousKey,
                             List<LinkedBankAccount> accounts, String operationId) {
        int updated = userRepository.updateUserKeyByUserId(userId, userKey, expectedPreviousKey);
        if (updated == 0) {
            throw UserErrorCode.LINK_KEY_UPDATE_CONFLICT.toException();
        }
        linkedAccountWriter.insertAll(accounts);
        operations.complete(operationId);
    }
}
