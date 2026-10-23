package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.client.RagSseReader;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.service.ContractClauseAgentService;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractClauseAgentService 스트리밍 테스트")
class ContractClauseAgentServiceStreamTest {

    /** 서비스의 STREAM_THREADS(8) + STREAM_QUEUE_CAPACITY(16) */
    private static final int ACCEPTABLE_REQUESTS = 8 + 16;

    @Mock
    private RagApiClient ragApiClient;

    private static ContractClauseRequest request(String message) {
        return ContractClauseRequest.builder()
                .principalAmount(new BigDecimal("1000000"))
                .interestRate(new BigDecimal("5"))
                .message(message)
                .build();
    }

    @Test
    @DisplayName("요청 내용이 비어 있으면 스트림을 열기 전에 예외를 던진다 (기존처럼 400)")
    void emptyMessageIsRejectedBeforeStreaming() {
        ContractClauseAgentService service = new ContractClauseAgentService(ragApiClient);

        assertThrows(IllegalArgumentException.class, () -> service.streamClause(request("   ")));
        verifyNoInteractions(ragApiClient);
    }

    @Test
    @DisplayName("스레드와 대기열이 모두 차면 줄을 세우지 않고 503을 던진다")
    void rejectsWhenQueueIsFull() throws Exception {
        ContractClauseAgentService service = new ContractClauseAgentService(ragApiClient);
        CountDownLatch release = new CountDownLatch(1);

        // AI 응답이 오래 걸리는 상황: release 될 때까지 중계 스레드를 붙잡아 둔다.
        doAnswer(invocation -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(ragApiClient).streamClause(any(), any());

        try {
            for (int i = 0; i < ACCEPTABLE_REQUESTS; i++) {
                service.streamClause(request("이자 5% 조항 " + i));
            }

            ResponseStatusException rejected = assertThrows(
                    ResponseStatusException.class,
                    () -> service.streamClause(request("한 번 더"))
            );

            assertEquals(503, rejected.getStatusCode().value());

        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("브라우저 연결이 끊기면 중계를 멈추고, RAG 서버 오류(IOException)로 취급하지 않는다")
    void stopsRelayWhenClientIsGone() throws Exception {
        ContractClauseAgentService service = new ContractClauseAgentService(ragApiClient);
        CountDownLatch clientGone = new CountDownLatch(1);
        CountDownLatch relayFinished = new CountDownLatch(1);
        AtomicReference<Throwable> thrownToReader = new AtomicReference<>();

        doAnswer(invocation -> {
            RagSseReader.Listener listener = invocation.getArgument(1);

            try {
                clientGone.await(5, TimeUnit.SECONDS);
                listener.onEvent("progress", "{\"step\":\"analysis\"}");

            } catch (Throwable e) {
                thrownToReader.set(e);
                throw e;

            } finally {
                relayFinished.countDown();
            }

            return null;
        }).when(ragApiClient).streamClause(any(), any());

        SseEmitter emitter = service.streamClause(request("이자 5% 조항"));

        // 브라우저가 떠난 상황: 이미 끝난 emitter에 보내면 IllegalStateException이 난다.
        emitter.complete();
        clientGone.countDown();

        assertTrue(relayFinished.await(5, TimeUnit.SECONDS));

        Throwable thrown = thrownToReader.get();
        assertNotNull(thrown, "끊긴 뒤 이벤트를 보내면 읽기를 멈추도록 예외가 나야 한다");
        assertEquals("ClientDisconnectedException", thrown.getClass().getSimpleName());
    }
}