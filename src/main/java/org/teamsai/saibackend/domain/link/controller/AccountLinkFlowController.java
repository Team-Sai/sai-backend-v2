package org.teamsai.saibackend.domain.link.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.UriComponentsBuilder;
import org.teamsai.saibackend.domain.link.dto.response.AccountLinkCallbackResult;
import org.teamsai.saibackend.domain.link.service.AccountLinkFlowService;
import org.teamsai.saibackend.global.security.CustomUserDetails;

import java.util.Map;

@Controller
@RequiredArgsConstructor
@Tag(
        name = "계좌 연동",
        description = "사이은행(mock-bank) 계좌 연동 시작/콜백 처리 API"
)
public class AccountLinkFlowController {

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    private final AccountLinkFlowService accountLinkFlowService;

    @Operation(
            summary = "계좌 연동 시작",
            description = "본인확인 정보를 검증하고 사이은행 연동 페이지로 이동할 redirectUrl을 발급"
    )
    @PostMapping("/api/accounts/link/start")
    public ResponseEntity<Map<String, String>> startLink(
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        String redirectUrl =
                accountLinkFlowService.startLink(userDetails.getUserId());

        return ResponseEntity.ok(Map.of("redirectUrl", redirectUrl));
    }

    @Operation(
            summary = "계좌 연동 콜백",
            description = "사이은행에서 계좌 선택을 마친 사용자가 리다이렉트되어 도달"
    )
    @GetMapping("/accounts/link/callback")
    public String linkCallback(
            @RequestParam String state,
            @RequestParam String userKey,
            @RequestParam(required = false) String accountIds
    ) {
        AccountLinkCallbackResult result =
                accountLinkFlowService.completeCallback(
                        state,
                        userKey,
                        accountIds
                );

        return buildCompleteRedirect(
                result.success(),
                result.errorMessage(),
                state
        );
    }

    private String buildCompleteRedirect(
            boolean success,
            String errorMessage,
            String state
    ) {
        UriComponentsBuilder builder = UriComponentsBuilder
                .fromUriString(frontendBaseUrl + "/link/complete")
                .queryParam("success", success)
                .queryParam("state", state);

        if (!success && errorMessage != null && !errorMessage.isBlank()) {
            builder.queryParam("errorMessage", errorMessage);
        }

        return "redirect:" + builder.build().encode().toUriString();
    }
}