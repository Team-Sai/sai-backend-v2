package org.teamsai.saibackend.domain.contract.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseChatRequest;

/**
 * 대화형 특약: 사용자가 답하는 동안 근거 법령을 뒤에서 미리 찾아 Redis에 둔다.
 *
 * 빠르게 하려는 것일 뿐이라, 바쁘거나 실패하면 건너뛴다. (작성 때 AI가 지금처럼 검색)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClauseChatPrepareService {

    private static final int PREPARE_THREADS = 4;

    /** 이보다 많이 몰리면 줄을 세우지 않고 미리 찾기를 건너뛴다. */
    private static final int PREPARE_QUEUE_CAPACITY = 16;

    private final RagApiClient ragApiClient;

    private final ClauseChatSessionStoreService sessionStore;

    private final ThreadPoolExecutor prepareExecutor = new ThreadPoolExecutor(
            PREPARE_THREADS,
            PREPARE_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(PREPARE_QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "clause-prepare");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy()
    );

    public void prepareInBackground(String sessionId, RagClauseChatRequest request) {
        prepareExecutor.execute(() -> prepare(sessionId, request));
    }

    /** 실제로 미리 찾고 보관하는 일. 뒤에서 도는 prepareInBackground()가 부르고, 테스트에서도 바로 부른다. */
    public void prepare(String sessionId, RagClauseChatRequest request) {
        try {
            List<Map<String, Object>> references =
                    ragApiClient.prepareReferences(request).getReferences();

            if (references == null || references.isEmpty()) {
                return;
            }

            sessionStore.saveReferences(sessionId, references);
            log.info("대화형 특약 근거 법령 미리 찾기 완료 - references={}", references.size());

        } catch (Exception e) {
            log.warn("대화형 특약 근거 법령 미리 찾기 실패 - exceptionType={}", e.getClass().getName());
        }
    }

    @PreDestroy
    void shutdownPrepareExecutor() {
        prepareExecutor.shutdown();
    }
}