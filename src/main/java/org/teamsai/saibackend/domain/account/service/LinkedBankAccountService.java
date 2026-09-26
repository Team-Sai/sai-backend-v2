package org.teamsai.saibackend.domain.account.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.teamsai.saibackend.domain.account.dto.request.LinkAccountRequest;
import org.teamsai.saibackend.domain.account.dto.response.AccountDetailResponse;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.client.MockBankClient;

import java.util.*;
import java.util.function.Predicate;

import static org.teamsai.saibackend.domain.account.type.ConnectionStatus.AVAILABLE;

@Slf4j
@Service
@RequiredArgsConstructor
public class LinkedBankAccountService {

    private final LinkedBankAccountRepository linkedBankAccountRepository;
    private final UserService userService;
    private final MockBankClient mockBankClient;
    private final EntityManager entityManager;
    private final LinkedAccountWriter linkedAccountWriter;

    public List<LinkedBankAccount> linkSelectedAccounts(
            Long userId,
            LinkAccountRequest request
    ) {
        String userKey = userService.getUserKeyByUserId(userId);

        Set<Long> alreadyLinkedIds =
                new HashSet<>(getLinkedAccountIds(userId));

        List<LinkedBankAccount> candidates = request.selectedAccounts().stream()
                .filter(selected -> !alreadyLinkedIds.contains(selected.accountId()))
                .filter(distinctByAccountId())
                .map(selected -> toLinkedAccount(
                        userId,
                        selected.accountId(),
                        userKey,
                        selected.accountAlias()
                ))
                .toList();

        return linkedAccountWriter.insertAll(candidates);
    }

    public List<LinkedBankAccount> linkAccountsByIds(
            Long userId,
            String userKey,
            List<Long> accountIds
    ) {
        return linkedAccountWriter.insertAll(prepareAccountsByIds(userId, userKey, accountIds));
    }

    public List<LinkedBankAccount> prepareAccountsByIds(Long userId, String userKey, List<Long> accountIds) {
        Set<Long> alreadyLinkedIds =
                new HashSet<>(getLinkedAccountIds(userId));

        List<Long> newAccountIds = accountIds.stream()
                .distinct()
                .filter(accountId -> !alreadyLinkedIds.contains(accountId))
                .toList();

        List<LinkedBankAccount> candidates = newAccountIds.stream()
                .map(accountId -> {
                    AccountDetailResponse detail =
                            fetchAccountDetail(accountId, userKey);

                    validateAccountDetail(detail, accountId);
                    return toLinkedAccount(
                            userId,
                            accountId,
                            detail,
                            detail.accountName()
                    );
                })
                .toList();

        return candidates;
    }

    @Transactional(readOnly = true)
    public List<LinkedBankAccountResponse> getLinkedAccounts(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }

        List<LinkedBankAccount> linkedAccounts =
                linkedBankAccountRepository
                        .findAllByUserIdAndConnectionStatus(
                                userId,
                                AVAILABLE
                        );

        if (linkedAccounts.isEmpty()) {
            return Collections.emptyList();
        }

        return linkedAccounts.stream()
                .map(LinkedBankAccountResponse::from)
                .toList();
    }

    public LinkedBankAccount getReferenceById(Long linkedAccountId) {
        return entityManager.getReference(
                LinkedBankAccount.class,
                linkedAccountId
        );
    }

    public List<Long> getLinkedAccountIds(Long userId) {
        if (userId == null) {
            return Collections.emptyList();
        }

        List<LinkedBankAccount> linkedAccounts =
                linkedBankAccountRepository.findAllByUserId(userId);

        if (linkedAccounts.isEmpty()) {
            return Collections.emptyList();
        }

        return linkedAccounts.stream()
                .map(LinkedBankAccount::getAccountId)
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean isOwnedLinkedAccount(
            Long userId,
            Long linkedAccountId
    ) {
        if (userId == null || linkedAccountId == null) {
            return false;
        }

        List<LinkedBankAccount> linkedAccounts =
                linkedBankAccountRepository.findAllByUserId(userId);

        return linkedAccounts.stream()
                .anyMatch(account ->
                        Objects.equals(
                                account.getLinkedAccountId(),
                                linkedAccountId
                        )
                                && account.getConnectionStatus() == AVAILABLE
                );
    }

    private AccountDetailResponse fetchAccountDetail(
            Long accountId,
            String userKey
    ) {
        try {
            return mockBankClient.getAccountDetail(
                    accountId,
                    userKey
            );
        } catch (RestClientException e) {
            log.warn(
                    "[LinkedBankAccountService] 계좌 상세 조회 실패 - accountId: {}",
                    accountId,
                    e
            );

            throw AccountErrorCode.BANK_SERVER_UNAVAILABLE.toException();
        }
    }

    private LinkedBankAccount toLinkedAccount(
            Long userId,
            Long accountId,
            String userKey,
            String accountAlias
    ) {
        AccountDetailResponse detail =
                fetchAccountDetail(accountId, userKey);

        return toLinkedAccount(
                userId,
                accountId,
                detail,
                accountAlias
        );
    }

    private LinkedBankAccount toLinkedAccount(
            Long userId,
            Long accountId,
            AccountDetailResponse detail,
            String accountAlias
    ) {
        validateAccountDetail(detail, accountId);

        return LinkedBankAccount.builder()
                .userId(userId)
                .accountId(accountId)
                .bankCode(detail.bankCode())
                .accountNumber(detail.maskedAccountNumber())
                .accountAlias(accountAlias)
                .accountHolderName(detail.accountHolderName())
                .balance(detail.balance())
                .connectionStatus(AVAILABLE)
                .build();
    }

    private void validateAccountDetail(
            AccountDetailResponse detail,
            Long accountId
    ) {
        if (detail == null
                || !Objects.equals(detail.accountId(), accountId)
                || isBlank(detail.bankCode())
                || isBlank(detail.maskedAccountNumber())
                || isBlank(detail.accountHolderName())
                || detail.balance() == null) {

            log.error(
                    "[LinkedBankAccountService] 사이은행 응답에 필수 필드가 누락됨 - accountId: {}, detail: {}",
                    accountId,
                    detail
            );

            throw AccountErrorCode.INVALID_BANK_RESPONSE.toException();
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Predicate<LinkAccountRequest.SelectedAccount> distinctByAccountId() {
        Set<Long> seen = new HashSet<>();
        return selected -> seen.add(selected.accountId());
    }
}