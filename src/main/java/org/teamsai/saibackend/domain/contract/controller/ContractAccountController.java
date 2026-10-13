package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.contract.service.ContractAccountService;

import java.util.List;

@Tag(
        name = "차용증 연동 계좌 API",
        description = "차용증 작성 시 선택 가능한 연동 계좌 관련 API"
)
@RestController
@RequestMapping("/api/contracts/accounts")
@RequiredArgsConstructor
public class ContractAccountController {

    private final ContractAccountService contractAccountService;

    @Operation(
            summary = "차용증 작성용 선택 가능 계좌 목록 조회",
            description = "본인이 연동한 계좌 중 현재 활성상태인 계좌 목록을 조회합니다. " +
                    "차용증 작성 화면의 계좌번호/예금주 드롭다운에 사용됩니다."
    )
    @GetMapping
    public ResponseEntity<List<LinkedBankAccountResponse>> getSelectableAccounts(
            @AuthenticationPrincipal(expression = "userId") Long userId
    ) {
        return ResponseEntity.ok(contractAccountService.getSelectableAccounts(userId));
    }
}
