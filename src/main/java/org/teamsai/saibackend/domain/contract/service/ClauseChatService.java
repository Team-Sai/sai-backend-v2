package org.teamsai.saibackend.domain.contract.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.ClauseChatSessionDTO;
import org.teamsai.saibackend.domain.contract.dto.request.ClauseChatReplyRequest;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseChatRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseChatReplyResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ClauseChatStartResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RagClauseRouteResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RagClauseUnderstandResponse;
import org.teamsai.saibackend.domain.contract.exception.ClauseChatErrorCode;

/**
 * 대화형 AI 특약: 대화 시작(이해 확인)과 답장.
 *
 * AI 서버는 상태를 갖지 않으므로, 대화 상태는 Redis에 두고 턴마다 같이 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClauseChatService {

    private final RagApiClient ragApiClient;

    private final ClauseChatSessionStoreService sessionStore;

    private final ContractClauseAgentService contractClauseAgentService;

    public ClauseChatStartResponse start(
            Long userId,
            ContractClauseRequest request
    ) {
        RagClauseSuggestionRequest ragRequest =
                contractClauseAgentService.toRagRequest(request);

        RagClauseUnderstandResponse understood =
                ragApiClient.understandClause(ragRequest);

        boolean inScope = Boolean.TRUE.equals(understood.getInScope());
        List<Map<String, Object>> slots = slotsOf(understood);
        List<Map<String, Object>> questions = orEmpty(understood.getQuestions());
        List<Map<String, Object>> fieldEdits = orEmpty(understood.getFieldEdits());

        // 범위 밖이거나 "계약서 칸을 고치세요" 안내면 이어갈 대화가 없다.
        String sessionId = null;

        if (inScope && fieldEdits.isEmpty()) {
            sessionId = sessionStore.create(
                    ClauseChatSessionDTO.builder()
                            .ownerUserId(userId)
                            .contract(ragRequest.getContract())
                            .message(ragRequest.getMessage())
                            .issues(orEmpty(understood.getIssues()))
                            .slots(slots)
                            .questions(questions)
                            .answers(new HashMap<>())
                            .build()
            );
        }

        // 사용자 원문은 남기지 않는다.
        log.info(
                "대화형 특약 시작 - inScope={}, sessionCreated={}, questions={}, fieldEdits={}",
                inScope,
                sessionId != null,
                questions.size(),
                fieldEdits.size()
        );

        return new ClauseChatStartResponse(
                sessionId,
                inScope,
                summaryOf(understood),
                slots,
                questions,
                fieldEdits,
                understood.getGuide()
        );
    }

    public ClauseChatReplyResponse reply(
            Long userId,
            String sessionId,
            ClauseChatReplyRequest request
    ) {
        ClauseChatSessionDTO session = sessionStore.find(sessionId, userId)
                .orElseThrow(ClauseChatErrorCode.CHAT_NOT_FOUND::toException);

        RagClauseRouteResponse routed = ragApiClient.routeReply(
                RagClauseChatRequest.builder()
                        .contract(session.getContract())
                        .message(request.message().trim())
                        .slots(session.getSlots())
                        .questions(session.getQuestions())
                        .answers(session.getAnswers())
                        .build()
        );

        session.setSlots(orEmpty(routed.getSlots()));
        session.setAnswers(
                routed.getAnswers() != null ? routed.getAnswers() : new HashMap<>()
        );
        // 가장 최근 답이 "확정(CONFIRM)"일 때만 작성할 수 있다.
        session.setReadyToWrite(Boolean.TRUE.equals(routed.getReadyToWrite()));

        sessionStore.save(sessionId, session);

        log.info(
                "대화형 특약 답장 - kind={}, pendingQuestions={}, readyToWrite={}",
                routed.getKind(),
                orEmpty(routed.getPendingQuestions()).size(),
                session.isReadyToWrite()
        );

        return new ClauseChatReplyResponse(
                routed.getKind(),
                routed.getEcho(),
                session.getSlots(),
                orEmpty(routed.getPendingQuestions()),
                session.isReadyToWrite()
        );
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values != null ? values : List.of();
    }

    private static String summaryOf(RagClauseUnderstandResponse understood) {
        Map<String, Object> understanding = understood.getUnderstanding();

        if (understanding == null || !(understanding.get("summary") instanceof String summary)) {
            return null;
        }

        return summary;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> slotsOf(RagClauseUnderstandResponse understood) {
        Map<String, Object> understanding = understood.getUnderstanding();

        if (understanding == null || !(understanding.get("slots") instanceof List<?> slots)) {
            return List.of();
        }

        return (List<Map<String, Object>>) slots;
    }
}