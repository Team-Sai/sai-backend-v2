package org.teamsai.saibackend.domain.link.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.identity.support.IdentityValidator;
import org.teamsai.saibackend.domain.link.dto.response.AccountLinkCallbackResult;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;
import org.teamsai.saibackend.global.jwt.JwtTokenProvider;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountLinkFlowService {

    @Value("${sai.mock-bank.base-url}")
    private String mockBankBaseUrl;

    @Value("${sai.backend.base-url}")
    private String backendBaseUrl;

    private final JwtTokenProvider jwtTokenProvider;
    private final LinkedBankAccountService linkedBankAccountService;
    private final AccountLinkCoordinator accountLinkCoordinator;
    private final UserService userService;
    private final IdentityValidator identityValidator;

    public String startLink(Long userId) {
        User user = userService.getUser(userId);
        identityValidator.validateUserInformation(user);

        // 복구가 성공한 뒤에만 새로운 state를 발급한다.
        accountLinkCoordinator.recoverUnresolved(userId);

        String state = jwtTokenProvider.createLinkStateToken(
                userId,
                user.getName(),
                user.getBirthDate()
        );

        List<Long> linkedAccountIds =
                linkedBankAccountService.getLinkedAccountIds(userId);

        return buildStartUrl(state, linkedAccountIds);
    }

    public AccountLinkCallbackResult completeCallback(
            String state,
            String userKey,
            String accountIds
    ) {
        Long userId;

        try {
            userId = jwtTokenProvider.getUserIdFromLinkState(state)
                    .orElseThrow(UserErrorCode.INVALID_LINK_STATE::toException);
        } catch (DomainException e) {
            log.warn("유효하지 않은 계좌 연동 state - reason: {}", e.getMessage());
            return AccountLinkCallbackResult.failed(
                    "유효하지 않거나 만료된 요청입니다."
            );
        }

        if (accountIds == null || accountIds.isBlank()) {
            log.info("선택된 계좌 없이 콜백 진입 - userId: {}", userId);
            return AccountLinkCallbackResult.failed("선택된 계좌가 없습니다.");
        }

        List<Long> ids;

        try {
            ids = parseAccountIds(accountIds);
        } catch (NumberFormatException e) {
            log.warn(
                    "accountIds 파싱 실패 - userId: {}, accountIds: {}",
                    userId,
                    accountIds
            );
            return AccountLinkCallbackResult.failed("계좌 연동에 실패했습니다.");
        }

        if (ids.isEmpty()) {
            return AccountLinkCallbackResult.failed("선택된 계좌가 없습니다.");
        }

        if (userKey == null || userKey.isBlank()
                || ids.stream().anyMatch(id -> id <= 0)) {
            return AccountLinkCallbackResult.failed(
                    "계좌 연동 요청이 올바르지 않습니다."
            );
        }

        try {
            accountLinkCoordinator.completeCallback(
                    userId,
                    state,
                    userKey,
                    ids
            );
        } catch (DomainException e) {
            log.warn(
                    "Account link failed - userId: {}, errorCode: {}",
                    userId,
                    e.getErrorCode()
            );
            return AccountLinkCallbackResult.failed(e.getMessage());
        }

        return AccountLinkCallbackResult.completed();
    }

    private List<Long> parseAccountIds(String accountIds) {
        return Arrays.stream(accountIds.split(","))
                .map(String::trim)
                .filter(token -> !token.isEmpty())
                .map(Long::parseLong)
                .toList();
    }

    private String buildStartUrl(String state, List<Long> linkedAccountIds) {
        String linkedIdsParam = linkedAccountIds.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        return UriComponentsBuilder
                .fromUriString(mockBankBaseUrl + "/link/start")
                .queryParam(
                        "returnUrl",
                        backendBaseUrl + "/accounts/link/callback"
                )
                .queryParam("state", state)
                .queryParam("excludeAccountIds", linkedIdsParam)
                .toUriString();
    }
}