package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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

    @Operation(
            summary = "AI 법률 검토",
            description = "차용증의 금액, 이자율, 만기일 등을 기반으로 법률 검토를 수행합니다."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "법률 검토 성공"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청"
            ),
            @ApiResponse(
                    responseCode = "502",
                    description = "AI 서버 응답 오류"
            ),
            @ApiResponse(
                    responseCode = "503",
                    description = "AI 서버 연결 실패"
            ),
            @ApiResponse(
                    responseCode = "504",
                    description = "AI 서버 응답 시간 초과"
            )
    })
    @PostMapping("/api/contracts/legal-review")
    public ContractLegalReviewResponse reviewContract(
            @Valid @RequestBody ContractLegalReviewRequest request
    ) {
        return contractLegalReviewService.review(request);
    }
}