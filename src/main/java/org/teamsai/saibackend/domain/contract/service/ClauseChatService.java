package org.teamsai.saibackend.domain.contract.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
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
                understood.getGuide(),
                orEmpty(understood.getNotices())
        );
    }

    public ClauseChatReplyResponse reply(
            Long userId,
            String sessionId,
            ClauseChatReplyRequest request
    ) {
        // 같은 대화의 답장이 겹치면 한쪽 답이 사라지므로, 하나씩만 처리한다.
        if (!sessionStore.tryLock(sessionId)) {
            throw ClauseChatErrorCode.CHAT_BUSY.toException();
        }

        try {
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

            // AI를 부르는 사이 대화가 만료됐으면 되살리지 않는다.
            if (!sessionStore.saveIfPresent(sessionId, session)) {
                throw ClauseChatErrorCode.CHAT_NOT_FOUND.toException();
            }

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

        } finally {
            sessionStore.unlock(sessionId);
        }
    }

    /**
     * 확정한 조건으로 특약을 쓴다. (SSE)
     * 저장해 둔 쟁점을 그대로 보내서 AI가 ① 쟁점 분석을 다시 하지 않는다.
     */
    public SseEmitter write(
            Long userId,
            String sessionId
    ) {
        if (!sessionStore.tryLock(sessionId)) {
            throw ClauseChatErrorCode.CHAT_BUSY.toException();
        }

        RagClauseChatRequest chatRequest;

        try {
            ClauseChatSessionDTO session = sessionStore.find(sessionId, userId)
                    .orElseThrow(ClauseChatErrorCode.CHAT_NOT_FOUND::toException);

            if (!session.isReadyToWrite()) {
                throw ClauseChatErrorCode.CHAT_NOT_READY_TO_WRITE.toException();
            }

            // 한 번 확정으로 한 번만 쓴다. (재시도·더블클릭으로 LLM 작성이 중복 실행되지 않게)
            // 다시 쓰려면 사용자가 다시 확정해야 한다.
            session.setReadyToWrite(false);

            if (!sessionStore.saveIfPresent(sessionId, session)) {
                throw ClauseChatErrorCode.CHAT_NOT_FOUND.toException();
            }

            chatRequest = RagClauseChatRequest.builder()
                    .contract(session.getContract())
                    .message(session.getMessage())
                    .issues(orEmpty(session.getIssues()))
                    .slots(orEmpty(session.getSlots()))
                    .questions(orEmpty(session.getQuestions()))
                    .answers(session.getAnswers() != null ? session.getAnswers() : new HashMap<>())
                    .build();

        } finally {
            sessionStore.unlock(sessionId);
        }

        log.info(
                "대화형 특약 작성 시작 - slots={}, answers={}",
                chatRequest.getSlots().size(),
                chatRequest.getAnswers().size()
        );

        try {
            return contractClauseAgentService.relayStream(
                    listener -> ragApiClient.streamWrite(chatRequest, listener)
            );

        } catch (ResponseStatusException e) {
            // 요청이 몰려 작성을 시작조차 못 했으면(503) 다시 [작성하기]를 누를 수 있게 되돌린다.
            restoreReadyToWrite(userId, sessionId);
            throw e;
        }
    }

    private void restoreReadyToWrite(Long userId, String sessionId) {
        sessionStore.find(sessionId, userId).ifPresent(session -> {
            session.setReadyToWrite(true);
            sessionStore.saveIfPresent(sessionId, session);
        });
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