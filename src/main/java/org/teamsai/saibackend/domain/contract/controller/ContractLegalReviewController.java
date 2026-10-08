package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.contract.dto.request.ContractLegalReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;
import org.teamsai.saibackend.domain.contract.service.ContractLegalReviewService;

@Tag(
        name = "차용증 AI 법률 검토 API",
        description = "차용증 작성 중 RAG 기반 법률 및 규정 검토 API"
)
@RestController
@RequiredArgsConstructor
public class ContractLegalReviewController {

    private final ContractLegalReviewService contractLegalReviewService;
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "법률 검토 성공"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "AI 법률 검토 서버 호출 실패"
            )
    })
    @PostMapping("/api/contracts/legal-review")
    public ContractLegalReviewResponse reviewContract(
            @RequestBody ContractLegalReviewRequest request
    ) {
        return contractLegalReviewService.review(
                request
        );
    }
}
