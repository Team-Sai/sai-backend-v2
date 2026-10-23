package org.teamsai.saibackend.domain.contract;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.client.RagSseReader;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RagApiClient 특약 스트리밍 테스트")
class RagApiClientStreamTest {

    private HttpServer server;
    private RagApiClient client;

    private volatile int responseStatus;
    private volatile String responseBody;
    private final AtomicReference<String> receivedAccept = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "";

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/rag/contracts/clause-suggestions/stream", exchange -> {
            try (exchange) {
                receivedAccept.set(exchange.getRequestHeaders().getFirst("Accept"));
                receivedBody.set(new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                ));

                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(responseStatus, bytes.length == 0 ? -1 : bytes.length);

                if (bytes.length > 0) {
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            }
        });

        server.start();

        client = new RagApiClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5),
                Duration.ofSeconds(60)
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private RagClauseSuggestionRequest request() {
        return RagClauseSuggestionRequest.builder()
                .contract(RagClauseSuggestionRequest.ContractContext.builder().build())
                .message("중도상환 수수료 없이")
                .build();
    }

    private List<String> collect() {
        List<String> received = new ArrayList<>();

        client.streamClause(request(), new RagSseReader.Listener() {
            @Override
            public void onEvent(String event, String data) {
                received.add(event + "|" + data);
            }

            @Override
            public void onComment(String comment) {
                received.add("#" + comment);
            }
        });

        return received;
    }

    @Test
    @DisplayName("AI가 보낸 이벤트를 순서대로 전달하고 SSE로 요청한다")
    void relaysEventsInOrder() {
        responseBody = """
                event: progress
                data: {"step": "analysis"}

                : keep-alive

                event: draft
                data: {"suggestedClause": "제1조"}

                event: final
                data: {"clauseRevised": false}

                """;

        List<String> received = collect();

        assertEquals(
                List.of(
                        "progress|{\"step\": \"analysis\"}",
                        "#keep-alive",
                        "draft|{\"suggestedClause\": \"제1조\"}",
                        "final|{\"clauseRevised\": false}"
                ),
                received
        );
        assertTrue(receivedAccept.get().contains("text/event-stream"));
        assertTrue(receivedBody.get().contains("중도상환 수수료 없이"));
    }

    @Test
    @DisplayName("AI 서버가 오류 상태를 보내면 502로 바꾼다")
    void serverErrorBecomesBadGateway() {
        responseStatus = 500;

        ResponseStatusException e = assertThrows(ResponseStatusException.class, this::collect);
        assertEquals(HttpStatus.BAD_GATEWAY, e.getStatusCode());
    }

    @Test
    @DisplayName("AI 서버에 연결할 수 없으면 503으로 바꾼다")
    void connectionFailureBecomesServiceUnavailable() {
        server.stop(0);

        ResponseStatusException e = assertThrows(ResponseStatusException.class, this::collect);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, e.getStatusCode());
    }
}