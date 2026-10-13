package org.teamsai.saibackend.domain.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RagApiClient HTTP 통신 테스트")
class RagApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;
    private RagApiClient client;

    private final AtomicReference<String> receivedMethod =
            new AtomicReference<>();

    private final AtomicReference<String> receivedBody =
            new AtomicReference<>();

    private volatile int responseStatus;
    private volatile String responseBody;
    private volatile long responseDelayMillis;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "{}";
        responseDelayMillis = 0;

        receivedMethod.set(null);
        receivedBody.set(null);

        server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );

        server.createContext(
                "/rag/contracts/review",
                this::handleRequest
        );

        server.createContext(
                "/rag/contracts/clause-suggestions",
                this::handleRequest
        );

        server.start();

        client = new RagApiClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(5),
                Duration.ofSeconds(60)
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange)
            throws IOException {

        try (exchange) {
            receivedMethod.set(exchange.getRequestMethod());

            receivedBody.set(
                    new String(
                            exchange.getRequestBody().readAllBytes(),
                            StandardCharsets.UTF_8
                    )
            );

            if (responseDelayMillis > 0) {
                try {
                    Thread.sleep(responseDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }

            if (responseStatus == 204) {
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            byte[] bytes = responseBody.getBytes(
                    StandardCharsets.UTF_8
            );

            exchange.getResponseHeaders().set(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            exchange.sendResponseHeaders(
                    responseStatus,
                    bytes.length
            );

            exchange.getResponseBody().write(bytes);
        }
    }

    private void assertResponseStatus(
            HttpStatus expectedStatus,
            Runnable action
    ) {
        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                action::run
        );

        assertEquals(
                expectedStatus,
                exception.getStatusCode()
        );
    }

    @Nested
    @DisplayName("정상 응답")
    class SuccessfulResponse {

        @Test
        @DisplayName("법률 검토 요청을 POST로 전송하고 JSON 응답을 변환한다")
        void reviewContract_sendsPostJsonAndParsesResponse()
                throws Exception {

            responseBody = """
                    {
                      "review": {
                        "disclaimer": "참고용입니다"
                      }
                    }
                    """;

            RagContractReviewRequest request =
                    RagContractReviewRequest.builder()
                            .principal(new BigDecimal("25000000"))
                            .interestRate(new BigDecimal("0.5"))
                            .repaymentDate("2027-01-01")
                            .build();

            ContractLegalReviewResponse result =
                    client.reviewContract(request);

            assertEquals("POST", receivedMethod.get());

            JsonNode body = mapper.readTree(
                    receivedBody.get()
            );

            assertEquals(
                    25000000,
                    body.get("principal").asInt()
            );

            assertEquals(
                    "0.5",
                    body.get("interestRate").asText()
            );

            assertEquals(
                    "2027-01-01",
                    body.get("repaymentDate").asText()
            );

            assertNotNull(result);
            assertNotNull(result.getReview());

            assertEquals(
                    "참고용입니다",
                    result.getReview().getDisclaimer()
            );
        }

        @Test
        @DisplayName("특약 생성 요청을 POST로 전송하고 JSON 응답을 변환한다")
        void suggestClause_sendsPostJsonAndParsesResponse()
                throws Exception {

            responseBody = """
                    {
                      "suggestedClause": "상환 특약",
                      "warnings": ["확인 필요"]
                    }
                    """;

            RagClauseSuggestionRequest request =
                    RagClauseSuggestionRequest.builder()
                            .message("상환 특약을 작성해 줘")
                            .contract(
                                    RagClauseSuggestionRequest
                                            .ContractContext.builder()
                                            .principal(
                                                    new BigDecimal("1000000")
                                            )
                                            .repaymentDay(1)
                                            .build()
                            )
                            .build();

            ContractClauseResponse result =
                    client.suggestClause(request);

            assertEquals("POST", receivedMethod.get());

            JsonNode body = mapper.readTree(
                    receivedBody.get()
            );

            assertEquals(
                    "상환 특약을 작성해 줘",
                    body.get("message").asText()
            );

            assertEquals(
                    1000000,
                    body.get("contract")
                            .get("principal")
                            .asInt()
            );

            assertEquals(
                    1,
                    body.get("contract")
                            .get("repaymentDay")
                            .asInt()
            );

            assertNotNull(result);

            assertEquals(
                    "상환 특약",
                    result.getSuggestedClause()
            );

            assertEquals(
                    "확인 필요",
                    result.getWarnings().get(0)
            );
        }
    }

    @Nested
    @DisplayName("빈 응답")
    class EmptyResponse {

        @Test
        @DisplayName("법률 검토 응답이 비어 있으면 502 오류를 반환한다")
        void reviewContract_emptyResponse_throwsBadGateway() {
            responseStatus = 204;
            responseBody = "";

            assertResponseStatus(
                    HttpStatus.BAD_GATEWAY,
                    () -> client.reviewContract(
                            RagContractReviewRequest.builder()
                                    .build()
                    )
            );
        }

        @Test
        @DisplayName("특약 생성 응답이 비어 있으면 502 오류를 반환한다")
        void suggestClause_emptyResponse_throwsBadGateway() {
            responseStatus = 204;
            responseBody = "";

            assertResponseStatus(
                    HttpStatus.BAD_GATEWAY,
                    () -> client.suggestClause(
                            RagClauseSuggestionRequest.builder()
                                    .message("특약")
                                    .build()
                    )
            );
        }
    }

    @Nested
    @DisplayName("HTTP 오류")
    class HttpError {

        @Test
        @DisplayName("법률 검토 서버가 500을 반환하면 502 오류로 변환한다")
        void reviewContract_serverError_throwsBadGateway() {
            responseStatus = 500;
            responseBody = """
                    {"error":"internal error"}
                    """;

            assertResponseStatus(
                    HttpStatus.BAD_GATEWAY,
                    () -> client.reviewContract(
                            RagContractReviewRequest.builder()
                                    .build()
                    )
            );
        }

        @Test
        @DisplayName("특약 생성 서버가 400을 반환하면 502 오류로 변환한다")
        void suggestClause_badRequest_throwsBadGateway() {
            responseStatus = 400;
            responseBody = """
                    {"error":"invalid request"}
                    """;

            assertResponseStatus(
                    HttpStatus.BAD_GATEWAY,
                    () -> client.suggestClause(
                            RagClauseSuggestionRequest.builder()
                                    .message("특약")
                                    .build()
                    )
            );
        }
    }

    @Nested
    @DisplayName("연결 및 타임아웃")
    class ConnectionError {

        @Test
        @DisplayName("RAG 서버에 연결할 수 없으면 503 오류를 반환한다")
        void reviewContract_connectionFailed_throwsServiceUnavailable()
                throws IOException {

            int unusedPort;

            try (ServerSocket socket = new ServerSocket(
                    0,
                    0,
                    java.net.InetAddress.getByName("127.0.0.1")
            )) {
                unusedPort = socket.getLocalPort();
            }

            RagApiClient unavailableClient =
                    new RagApiClient(
                            "http://127.0.0.1:" + unusedPort,
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(1)
                    );

            assertResponseStatus(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    () -> unavailableClient.reviewContract(
                            RagContractReviewRequest.builder()
                                    .build()
                    )
            );
        }

        @Test
        @DisplayName("RAG 서버 응답 시간이 초과되면 504 오류를 반환한다")
        void reviewContract_timeout_throwsGatewayTimeout() {

            responseDelayMillis = 1000;

            RagApiClient timeoutClient =
                    new RagApiClient(
                            "http://127.0.0.1:"
                                    + server.getAddress().getPort(),
                            Duration.ofSeconds(1),
                            Duration.ofMillis(100)
                    );

            assertResponseStatus(
                    HttpStatus.GATEWAY_TIMEOUT,
                    () -> timeoutClient.reviewContract(
                            RagContractReviewRequest.builder()
                                    .build()
                    )
            );
        }
    }
}
