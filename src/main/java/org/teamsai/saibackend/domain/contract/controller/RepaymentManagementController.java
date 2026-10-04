package org.teamsai.saibackend.domain.contract.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;

@RestController
@RequiredArgsConstructor
public class RepaymentManagementController {

    private final RepaymentAnalysisService repaymentAnalysisService;

    @GetMapping("/api/repayment-management")
    public RepaymentAnalysisContext getRepaymentManagement(
            @AuthenticationPrincipal(expression = "userId") Long userId
    ) {
        return repaymentAnalysisService.analyze(userId);
    }
}