package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.teamsai.saibackend.domain.contract.dto.request.EarlyRepaymentRequest;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentDepositResponse;
import org.teamsai.saibackend.domain.contract.dto.response.EarlyRepaymentResponse;
import org.teamsai.saibackend.domain.contract.service.EarlyRepaymentService;

import java.util.List;

@Tag(name = "차용증 API", description = "중도상환 입금 조회와 처리 API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contracts/{contractId}/early-repayments")
public class EarlyRepaymentController {

    private final EarlyRepaymentService earlyRepaymentService;

    @Operation(summary = "중도상환 입금 후보 조회")
    @GetMapping("/deposits")
    public List<EarlyRepaymentDepositResponse> getDeposits(
            @PathVariable Long contractId,
            @AuthenticationPrincipal(expression = "userId") Long userId
    ) {
        return earlyRepaymentService.getDeposits(contractId, userId);
    }

    @Operation(summary = "중도상환 처리")
    @PostMapping
    public EarlyRepaymentResponse process(
            @PathVariable Long contractId,
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @Valid @RequestBody EarlyRepaymentRequest request
    ) {
        return earlyRepaymentService.process(contractId, userId, request);
    }
}