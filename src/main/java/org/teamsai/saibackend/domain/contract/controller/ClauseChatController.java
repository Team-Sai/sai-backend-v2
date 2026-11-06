package org.teamsai.saibackend.domain.contract.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.teamsai.saibackend.domain.contract.dto.request.ClauseChatReplyRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseChatReplyResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseChatStartResponse;
import org.teamsai.saibackend.domain.contract.service.ClauseChatService;

@Tag(
        name = "대화형 AI 특약 API",
        description = "특약을 쓰기 전에 AI가 이해한 내용을 확인받고, 답장으로 값을 정한 뒤 작성합니다."
)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/contracts/clause-chat")
public class ClauseChatController {

    private final ClauseChatService clauseChatService;

    @Operation(
            summary = "대화형 AI 특약 시작 (이해 확인)",
            description = "AI가 이해한 내용과 정해야 할 것을 돌려주고, 대화를 이어갈 수 있으면 sessionId를 발급합니다."
    )
    @PostMapping
    public ClauseChatStartResponse start(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @Valid @RequestBody ContractClauseRequest request
    ) {
        return clauseChatService.start(userId, request);
    }

    @Operation(
            summary = "대화형 AI 특약 답장",
            description = "버튼이나 입력으로 답하면 AI가 규칙으로 분류해 값을 확정하거나 바꿉니다."
    )
    @PostMapping("/{sessionId}/reply")
    public ClauseChatReplyResponse reply(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @PathVariable String sessionId,
            @Valid @RequestBody ClauseChatReplyRequest request
    ) {
        return clauseChatService.reply(userId, sessionId, request);
    }

    @Operation(
            summary = "대화형 AI 특약 작성 (진행 상황 스트리밍)",
            description = """
                    확정한 조건으로 특약을 씁니다. 이벤트는 /api/contracts/clause-suggestions/stream과 같습니다.
                    (progress는 retrieval부터 시작)

                    - 404: 대화가 없거나 끝남
                    - 409: 아직 확정 전
                    """
    )
    @PostMapping(
            value = "/{sessionId}/write",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter write(
            @AuthenticationPrincipal(expression = "userId") Long userId,
            @PathVariable String sessionId
    ) {
        return clauseChatService.write(userId, sessionId);
    }
}