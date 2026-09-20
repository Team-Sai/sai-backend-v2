package org.teamsai.saibackend.domain.account.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.link.dto.response.UserKeyResponse;
import org.teamsai.saibackend.domain.link.service.AccountLinkCoordinator;

@Service
@RequiredArgsConstructor
public class AccountService {
    private final AccountLinkCoordinator coordinator;

    public UserKeyResponse issueOrGetUserKey(Long userId) {
        return coordinator.issueOrGetUserKey(userId);
    }
}
