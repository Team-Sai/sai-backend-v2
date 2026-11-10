package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.contract.service.ContractAccountService;
import org.teamsai.saibackend.global.security.CustomUserDetails;

@Tag(
        name = "차용증 연동계좌 조회 API",
        description = "차용증에 연결된 현재 수취 계좌를 조회합니다."
)
@RestController
@RequiredArgsConstructor
public class ContractAccountQueryController {

    private final ContractAccountService contractAccountService;

    @Operation(
            summary = "차용증 현재 연동계좌 조회",
            description = "차용증에 연결된 채권자의 활성 연동계좌를 조회합니다. 채무자에게는 은행명, 마스킹된 계좌번호, 예금주만 제공합니다."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공"),
            @ApiResponse(responseCode = "403", description = "차용증 조회 권한 없음"),
            @ApiResponse(responseCode = "404", description = "차용증 또는 활성 계좌 없음")
    })
    @GetMapping("/api/contracts/{contractId}/account")
    public ResponseEntity<LinkedBankAccountResponse> getCurrentAccount(
            @PathVariable Long contractId,
            @Parameter(hidden = true)
            @AuthenticationPrincipal CustomUserDetails userDetails
    ) {
        return ResponseEntity.ok(
                contractAccountService.getCurrentAccount(
                        contractId,
                        userDetails.getUserId()
                )
        );
    }
}
