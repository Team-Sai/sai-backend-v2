package org.teamsai.saibackend.domain.link.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.identity.support.IdentityValidator;
import org.teamsai.saibackend.domain.link.service.AccountLinkCoordinator;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.exception.UserErrorCode;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;
import org.teamsai.saibackend.global.jwt.JwtTokenProvider;
import org.teamsai.saibackend.global.security.CustomUserDetails;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Controller
@RequiredArgsConstructor
@Tag(
        name = "계좌 연동",
        description = "사이은행(mock-bank) 계좌 연동 시작/콜백 처리 API"
)
public class AccountLinkFlowController {

    @Value("${sai.mock-bank.base-url}")
    private String mockBankBaseUrl;

    @Value("${sai.backend.base-url}")
    private String backendBaseUrl;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private final JwtTokenProvider jwtTokenProvider;
    private final LinkedBankAccountService linkedBankAccountService;
    private final AccountLinkCoordinator accountLinkCoordinator;
    private final UserService userService;
    private final IdentityValidator identityValidator;

    @Operation(
            summary = "계좌 연동 시작",
            description = "본인확인 정보를 검증하고 사이은행 연동 페이지로 이동할 redirectUrl을 발급"
    )
    @PostMapping("/api/accounts/link/start")
    public ResponseEntity<Map<String, String>> startLink(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        Long userId = userDetails.getUserId();

        User myInfo =
                userService.getUser(userId);

        identityValidator.validateUserInformation(
                myInfo
        );

        accountLinkCoordinator.recoverUnresolved(userId);

        String state =
                jwtTokenProvider.createLinkStateToken(
                        userId,
                        myInfo.getName(),
                        myInfo.getBirthDate()
                );

        List<Long> alreadyLinkedAccountIds =
                linkedBankAccountService
                        .getLinkedAccountIds(userId);

        String linkedIdsParam =
                alreadyLinkedAccountIds.stream()
                        .map(String::valueOf)
                        .collect(
                                Collectors.joining(",")
                        );

        String redirectUrl =
                UriComponentsBuilder
                        .fromUriString(
                                mockBankBaseUrl
                                        + "/link/start"
                        )
                        .queryParam(
                                "returnUrl",
                                backendBaseUrl
                                        + "/accounts/link/callback"
                        )
                        .queryParam(
                                "state",
                                state
                        )
                        .queryParam(
                                "excludeAccountIds",
                                linkedIdsParam
                        )
                        .toUriString();

        return ResponseEntity.ok(
                Map.of(
                        "redirectUrl",
                        redirectUrl
                )
        );
    }

    @Operation(
            summary = "계좌 연동 콜백",
            description = "사이은행에서 계좌 선택을 마친 사용자가 리다이렉트되어 도달"
    )
    @GetMapping("/accounts/link/callback")
    public String linkCallback(
            @RequestParam String state,
            @RequestParam String userKey,
            @RequestParam(required = false)
            String accountIds
    ) {
        Long userId;

        try {
            userId =
                    jwtTokenProvider
                            .getUserIdFromLinkState(
                                    state
                            )
                            .orElseThrow(
                                    UserErrorCode
                                            .INVALID_LINK_STATE
                                            ::toException
                            );
        } catch (DomainException e) {
            log.warn(
                    "[AccountLinkFlowController] 유효하지 않은 state - reason: {}",
                    e.getMessage()
            );

            return errorRedirect(
                    "유효하지 않거나 만료된 요청입니다.",
                    state
            );
        }

        if (
                accountIds == null
                        || accountIds.isBlank()
        ) {
            log.info(
                    "[AccountLinkFlowController] 선택된 계좌 없이 콜백 진입 - userId: {}",
                    userId
            );

            return errorRedirect(
                    "선택된 계좌가 없습니다.",
                    state
            );
        }

        List<Long> ids;

        try {
            ids = Arrays.stream(accountIds.split(","))
                    .map(String::trim)
                    .filter(token -> !token.isEmpty())
                    .map(Long::parseLong)
                    .toList();
        } catch (NumberFormatException e) {
            log.warn(
                    "[AccountLinkFlowController] accountIds 파싱 실패 - userId: {}, accountIds: {}",
                    userId,
                    accountIds
            );

            return errorRedirect(
                    "계좌 연동에 실패했습니다.",
                    state
            );
        }

        if (ids.isEmpty()) {
            return errorRedirect(
                    "선택된 계좌가 없습니다.",
                    state
            );
        }

        if (userKey.isBlank() || ids.stream().anyMatch(id -> id <= 0)) {
            return errorRedirect("계좌 연동 요청이 올바르지 않습니다.", state);
        }
        try {
            accountLinkCoordinator.completeCallback(userId, state, userKey, ids);
        } catch (DomainException e) {
            log.warn("Account link failed - userId: {}, errorCode: {}", userId, e.getErrorCode());
            return errorRedirect(e.getMessage(), state);
        }
        return buildCompleteRedirect(true, null, state);
    }

    private String errorRedirect(
            String message,
            String state
    ) {
        return buildCompleteRedirect(
                false,
                message,
                state
        );
    }

    private String buildCompleteRedirect(
            boolean success,
            String errorMessage,
            String state
    ) {
        UriComponentsBuilder builder =
                UriComponentsBuilder
                        .fromUriString(
                                frontendBaseUrl
                                        + "/link/complete"
                        )
                        .queryParam(
                                "success",
                                success
                        )
                        .queryParam(
                                "state",
                                state
                        );

        if (
                !success
                        && errorMessage != null
                        && !errorMessage.isBlank()
        ) {
            builder.queryParam(
                    "errorMessage",
                    errorMessage
            );
        }

        return "redirect:"
                + builder
                .build()
                .encode()
                .toUriString();
    }
}
