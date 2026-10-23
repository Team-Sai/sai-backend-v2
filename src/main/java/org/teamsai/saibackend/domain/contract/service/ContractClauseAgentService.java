package org.teamsai.saibackend.domain.contract.service;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.client.RagSseReader;
import org.teamsai.saibackend.domain.contract.client.RagStreamEventFilter;
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

    /**
     * 스레드가 모두 바쁠 때 기다릴 수 있는 요청 수.
     * 이보다 많이 몰리면 줄을 세우지 않고 바로 503으로 돌려보낸다.
     * (무한정 쌓이면 기다리는 동안 브라우저가 이미 떠나 AI 호출이 헛돌 수 있다)
     */
    private static final int STREAM_QUEUE_CAPACITY = 16;

    private static final AtomicInteger STREAM_THREAD_NUMBER = new AtomicInteger();

    private final RagApiClient ragApiClient;

    private final ThreadPoolExecutor streamExecutor = new ThreadPoolExecutor(
            STREAM_THREADS,
            STREAM_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(STREAM_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(
                        runnable,
                        "clause-stream-" + STREAM_THREAD_NUMBER.incrementAndGet()
                );
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );

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

        // 브라우저가 떠나거나(onError) 시간이 다 되면(onTimeout) 중계를 멈추도록 표시한다.
        // 중계 스레드는 다음 이벤트(최소 15초마다 오는 keep-alive 포함)를 받을 때 이 표시를 보고 멈춘다.
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));

        try {
            streamExecutor.execute(() ->
                    relay(ragRequest, emitter, closed)
            );

        } catch (RejectedExecutionException e) {
            log.warn(
                    "AI 특약 스트리밍 요청이 몰려 거절 - active={}, queued={}",
                    streamExecutor.getActiveCount(),
                    streamExecutor.getQueue().size()
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "지금 AI 요청이 많아요. 잠시 후 다시 시도해 주세요."
            );
        }

        return emitter;
    }

    private void relay(
            RagClauseSuggestionRequest ragRequest,
            SseEmitter emitter,
            AtomicBoolean closed
    ) {
        // 줄을 서 있는 동안 브라우저가 이미 떠났다면 AI를 부르지 않는다.
        if (closed.get()) {
            return;
        }

        try {
            ragApiClient.streamClause(
                    ragRequest,
                    new RagSseReader.Listener() {
                        @Override
                        public void onEvent(String event, String data) {
                            // 정해진 이벤트·필드만 프론트로 보낸다. (debug 같은 내부 정보 차단)
                            RagStreamEventFilter.filter(event, data)
                                    .ifPresent(safeData -> sendToClient(
                                            emitter,
                                            closed,
                                            SseEmitter.event()
                                                    .name(event)
                                                    .data(safeData)
                                    ));
                        }

                        @Override
                        public void onComment(String comment) {
                            sendToClient(
                                    emitter,
                                    closed,
                                    SseEmitter.event()
                                            .comment(comment)
                            );
                        }
                    }
            );

            emitter.complete();

        } catch (ClientDisconnectedException e) {
            // 사용자가 취소했거나 창을 닫은 경우: 오류가 아니므로 조용히 정리만 한다.
            // 예외가 AI 응답 읽기를 멈추게 하고, AI 서버와의 연결도 함께 닫힌다.
            log.debug("AI 특약 스트리밍 중 클라이언트 연결 종료");

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

    private void sendToClient(
            SseEmitter emitter,
            AtomicBoolean closed,
            SseEmitter.SseEventBuilder event
    ) {
        if (closed.get()) {
            throw new ClientDisconnectedException(null);
        }

        try {
            emitter.send(event);

        } catch (IOException | IllegalStateException e) {
            closed.set(true);
            throw new ClientDisconnectedException(e);
        }
    }

    /**
     * 브라우저 연결이 끊겨 더 보낼 곳이 없음을 알리는 내부 신호.
     * RAG 서버 오류(503/502)와 구분하기 위해 따로 둔다.
     */
    private static final class ClientDisconnectedException
            extends RuntimeException {

        private ClientDisconnectedException(Throwable cause) {
            super("client disconnected", cause, false, false);
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

        } catch (IOException | IllegalStateException sendFailed) {
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