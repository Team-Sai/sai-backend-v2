package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
    @PostMapping("/api/contracts/clause-suggestions")
    public ContractClauseResponse suggestClause(
            @Valid @RequestBody ContractClauseRequest request
    ) {
        return contractClauseAgentService.suggestClause(request);
    }


    @Operation(
            summary = "AI 특약 문구 생성 (진행 상황 스트리밍)",
            description = """
                    특약 생성 진행 상황을 SSE(text/event-stream)로 전달합니다.

                    - progress: 단계 시작 (analysis / retrieval / generation / validation)
                    - draft: 생성 직후 조항 초안 (검증 전, 계약서 반영 불가)
                    - final: 최종 결과 (기존 API 응답과 같은 형식 + clauseRevised)
                    - error: 오류 ({"status": 502, "message": "..."})
                    """
    )
    @PostMapping(
            value = "/api/contracts/clause-suggestions/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter streamClause(
            @Valid @RequestBody ContractClauseRequest request
    ) {
        return contractClauseAgentService.streamClause(request);
    }
}