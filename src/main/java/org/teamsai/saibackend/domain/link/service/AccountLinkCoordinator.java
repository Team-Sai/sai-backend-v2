package org.teamsai.saibackend.domain.link.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.dto.request.LinkAccountRequest;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.link.dto.response.UserKeyResponse;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.client.MockBankClient;
import org.teamsai.saibackend.global.exception.DomainException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.teamsai.saibackend.domain.link.service.LinkOperationStore.Status.*;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class AccountLinkCoordinator {
    private final UserLinkLock lock;
    private final UserRepository users;
    private final LinkedBankAccountService accounts;
    private final AccountLinkService persistence;
    private final MockBankClient bank;
    private final LinkOperationStore operations;

    public List<LinkedBankAccount> linkSelectedAccounts(Long userId, LinkAccountRequest request) {
        return lock.execute(userId, () -> {
            requireResolved(userId);
            return accounts.linkSelectedAccounts(userId, request);
        });
    }

    public void completeCallback(Long userId, String state, String userKey, List<Long> accountIds) {
        if (userKey == null || userKey.isBlank() || userKey.length() > 100 || accountIds == null
                || accountIds.isEmpty() || accountIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw AccountErrorCode.LINK_REQUEST_CONFLICT.toException();
        }
        List<Long> ids = accountIds.stream().distinct().sorted().toList();
        String operationId = hash(state);
        String requestHash = hash(userKey + "\n" + ids);
        lock.execute(userId, () -> {
            var previous = operations.find(operationId);
            if (previous.isPresent()) {
                var operation = previous.get();
                if (!Objects.equals(operation.userId(), userId)
                        || !operation.requestHash().equals(requestHash)) {
                    throw AccountErrorCode.LINK_REQUEST_CONFLICT.toException();
                }
                if (operation.status() == COMPLETED) {
                    return null;
                }
                if (operation.status() != FAILED) {
                    recover(operation);
                    throw AccountErrorCode.LINK_REQUEST_CONFLICT.toException();
                }
                throw AccountErrorCode.LINK_REQUEST_CONFLICT.toException();
            }
            requireResolved(userId);
            users.findById(userId).orElseThrow(UserErrorCode.USER_NOT_FOUND::toException);
            String previousKey = users.findUserKeyByUserId(userId);
            var operation = new LinkOperationStore.Operation(operationId, userId, requestHash,
                    previousKey, userKey, PROCESSING);
            run(operation, ids);
            return null;
        });
    }

    public UserKeyResponse issueOrGetUserKey(Long userId) {
        return lock.execute(userId, () -> {
            requireResolved(userId);
            var user = users.findById(userId).orElseThrow(UserErrorCode.USER_NOT_FOUND::toException);
            String existingKey = users.findUserKeyByUserId(userId);
            if (existingKey != null) {
                return new UserKeyResponse(existingKey);
            }
            String key;
            try {
                key = bank.requestUserKey(user.getName(), user.getUserToken());
            } catch (RestClientException e) {
                throw AccountErrorCode.BANK_SERVER_UNAVAILABLE.toException();
            }
            if (key == null || key.isBlank() || key.length() > 100) {
                throw AccountErrorCode.INVALID_BANK_RESPONSE.toException();
            }
            var operation = new LinkOperationStore.Operation(UUID.randomUUID().toString(), userId,
                    hash(key), null, key, PROCESSING);
            try {
                run(operation, List.of());
            } catch (DomainException e) {
                if (e.getErrorCode() == UserErrorCode.LINK_KEY_UPDATE_CONFLICT) {
                    throw AccountErrorCode.USER_KEY_ALREADY_LINKED.toException();
                }
                throw e;
            } catch (RuntimeException e) {
                throw AccountErrorCode.LOCAL_KEY_SAVE_FAILED.toException();
            }
            return new UserKeyResponse(key);
        });
    }

    public void recoverUnresolved(Long userId) {
        lock.execute(userId, () -> {
            requireResolved(userId);
            return null;
        });
    }

    private void requireResolved(Long userId) {
        for (var operation : operations.findUnresolved(userId)) {
            recover(operation);
        }
        if (operations.hasUnresolved(userId)) {
            throw AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException();
        }
    }

    private void recover(LinkOperationStore.Operation operation) {
        if (operation.status() != CONFIRM_UNKNOWN && operation.status() != COMPENSATION_PENDING) {
            throw AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException();
        }
        try {
            if (!Objects.equals(users.findUserKeyByUserId(operation.userId()), operation.previousKey())) {
                throw AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException();
            }
            if (!Objects.equals(operation.previousKey(), operation.newKey())) {
                var user = users.findById(operation.userId())
                        .orElseThrow(UserErrorCode.USER_NOT_FOUND::toException);
                bank.recoverUserKey(user.getUserToken(), operation.newKey(), operation.previousKey());
            }
            operations.mark(operation.id(), FAILED);
        } catch (RuntimeException e) {
            log.error("계좌 연동 복구 미완료 - userId: {}, operationId: {}",
                    operation.userId(), operation.id());
            throw AccountErrorCode.LINK_RECONCILIATION_REQUIRED.toException();
        }
    }

    private void run(LinkOperationStore.Operation operation, List<Long> ids) {
        operations.begin(operation);
        boolean changesKey = !Objects.equals(operation.previousKey(), operation.newKey());
        if (changesKey) {
            // 외부 호출 전에 커밋하여 confirm 성공 직후 프로세스가 중단되어도 복구 가능하게 합니다.
            operations.mark(operation.id(), CONFIRM_UNKNOWN);
            try {
                bank.confirmUserKey(operation.newKey());
            } catch (RuntimeException e) {
                throw AccountErrorCode.BANK_SERVER_UNAVAILABLE.toException();
            }
        }
        try {
            List<LinkedBankAccount> prepared = accounts.prepareAccountsByIds(
                    operation.userId(), operation.newKey(), ids);
            persistence.completeLink(operation.userId(), operation.newKey(), operation.previousKey(),
                    prepared, operation.id());
        } catch (RuntimeException e) {
            if (operations.find(operation.id()).orElseThrow().status() == COMPLETED) {
                return;
            }
            operations.mark(operation.id(), COMPENSATION_PENDING);
            recover(operations.find(operation.id()).orElseThrow());
            throw e;
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
