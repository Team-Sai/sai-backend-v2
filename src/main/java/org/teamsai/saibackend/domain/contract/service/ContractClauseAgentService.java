package org.teamsai.saibackend.domain.contract.service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.client.RagSseReader;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContractClauseAgentService {

    /**
     * 스트리밍 연결 최대 유지 시간.
     * 특약 생성은 보통 15~35초이고, 검증 LLM이 필요한 경우까지 여유를 둔다.
     */
    private static final long STREAM_TIMEOUT_MILLIS = 120_000L;

    /**
     * AI 스트림을 읽어 프론트로 넘기는 작업용 스레드.
     * 요청 스레드(Tomcat)를 붙잡지 않도록 별도 스레드에서 중계한다.
     */
    private static final int STREAM_THREADS = 8;

    private final RagApiClient ragApiClient;

    private final ExecutorService streamExecutor =
            Executors.newFixedThreadPool(STREAM_THREADS);

    public ContractClauseResponse suggestClause(
            ContractClauseRequest request
    ) {
        return ragApiClient.suggestClause(
                toRagRequest(request)
        );
    }

    /**
     * AI 특약 생성 진행 상황을 SSE로 중계한다.
     *
     * progress → draft(생성 직후 초안) → final(최종 결과) 순서로 전달하고,
     * 오류가 나면 error 이벤트를 보낸 뒤 연결을 닫는다.
     */
    public SseEmitter streamClause(
            ContractClauseRequest request
    ) {
        // 요청 검증은 스트림을 열기 전에 해서, 잘못된 요청은 기존처럼 400으로 응답한다.
        RagClauseSuggestionRequest ragRequest =
                toRagRequest(request);

        SseEmitter emitter =
                new SseEmitter(STREAM_TIMEOUT_MILLIS);

        streamExecutor.execute(() ->
                relay(ragRequest, emitter)
        );

        return emitter;
    }

    private void relay(
            RagClauseSuggestionRequest ragRequest,
            SseEmitter emitter
    ) {
        try {
            ragApiClient.streamClause(
                    ragRequest,
                    new RagSseReader.Listener() {
                        @Override
                        public void onEvent(String event, String data)
                                throws IOException {
                            emitter.send(
                                    SseEmitter.event()
                                            .name(event)
                                            .data(data)
                            );
                        }

                        @Override
                        public void onComment(String comment)
                                throws IOException {
                            emitter.send(
                                    SseEmitter.event()
                                            .comment(comment)
                            );
                        }
                    }
            );

            emitter.complete();

        } catch (ResponseStatusException e) {
            sendError(
                    emitter,
                    e.getStatusCode().value(),
                    Objects.requireNonNullElse(
                            e.getReason(),
                            "AI 특약 생성 중 오류가 발생했습니다."
                    )
            );

        } catch (Exception e) {
            log.error(
                    "AI 특약 스트리밍 중계 오류 - exceptionType={}",
                    e.getClass().getName(),
                    e
            );

            sendError(
                    emitter,
                    502,
                    "AI 특약 생성 중 오류가 발생했습니다."
            );
        }
    }

    private void sendError(
            SseEmitter emitter,
            int status,
            String message
    ) {
        try {
            emitter.send(
                    SseEmitter.event()
                            .name("error")
                            .data(
                                    Map.of(
                                            "status", status,
                                            "message", message
                                    ),
                                    MediaType.APPLICATION_JSON
                            )
            );
            emitter.complete();

        } catch (Exception sendFailed) {
            // 브라우저가 이미 연결을 끊은 경우: 보낼 곳이 없으므로 정리만 한다.
            emitter.completeWithError(sendFailed);
        }
    }

    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdown();
    }

    private RagClauseSuggestionRequest toRagRequest(
            ContractClauseRequest request
    ) {
        if (!StringUtils.hasText(request.getMessage())) {
            throw new IllegalArgumentException(
                    "AI에게 요청할 내용을 입력해 주세요."
            );
        }

        RagClauseSuggestionRequest.ContractContext context =
                RagClauseSuggestionRequest.ContractContext.builder()
                        .principal(
                                request.getPrincipalAmount()
                        )
                        .interestRate(
                                request.getInterestRate()
                        )
                        .lateInterestRate(
                                request.getLateInterestRate()
                        )
                        .startDate(
                                request.getStartDate() != null
                                        ? request.getStartDate().toString()
                                        : null
                        )
                        .repaymentDate(
                                request.getMaturityDate() != null
                                        ? request.getMaturityDate().toString()
                                        : null
                        )
                        .repaymentDay(
                                request.getRepaymentDay()
                        )
                        .repaymentType(
                                toRagRepaymentType(
                                        request.getRepaymentType()
                                )
                        )
                        .contractAlias(
                                request.getContractAlias()
                        )
                        .terms(
                                request.getTerms()
                        )
                        .build();

        return RagClauseSuggestionRequest.builder()
                .contract(context)
                .message(
                        request.getMessage().trim()
                )
                .build();
    }

    private String toRagRepaymentType(String repaymentType) {
        if (repaymentType == null) {
            return null;
        }

        return switch (repaymentType) {
            case "BULLET_REPAYMENT", "LUMP_SUM", "BULLET"
                    -> "BULLET";
            default -> repaymentType;
        };
    }
}