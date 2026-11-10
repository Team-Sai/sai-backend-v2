package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.contract.service.RepaymentSyncService;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingExecutionResult;

@Tag(name = "차용증 API", description = "계약 상환 입금 동기화 API")
@RestController
@RequiredArgsConstructor
public class RepaymentSyncController {

    private final RepaymentSyncService repaymentSyncService;

    @Operation(summary = "계약 상환 입금 동기화")
    @PostMapping("/api/contracts/{contractId}/schedules/sync")
    public AutoMatchingExecutionResult sync(
            @PathVariable Long contractId,
            @AuthenticationPrincipal(expression = "userId") Long userId
    ) {
        return repaymentSyncService.sync(contractId, userId);
    }
}