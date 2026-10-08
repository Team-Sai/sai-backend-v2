package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;

@Tag(
        name = "차용증 AI 특약 Agent API",
        description = "사용자의 상황을 분석하여 관련 법령 기반의 특약 문구를 제안합니다."
)
@RestController
@RequiredArgsConstructor
public class ContractClauseAgentController {

    private final ContractClauseAgentService contractClauseAgentService;

    @Operation(
            summary = "AI 특약 문구 생성",
            description = """
                    현재 차용증의 기본정보와 사용자의 자연어 요청을
                    AI Agent에 전달하여 관련 법령 기반 특약 문구를 제안합니다.
                    """
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "특약 생성 성공"
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "잘못된 요청"
            ),
            @ApiResponse(
                    responseCode = "500",
                    description = "AI Agent 호출 실패"
            )
    })
    @PostMapping("/api/contracts/clause-suggestions")
    public ContractClauseResponse suggestClause(
            @RequestBody ContractClauseRequest request
    ) {

        return contractClauseAgentService.suggestClause(
                request
        );
    }
}