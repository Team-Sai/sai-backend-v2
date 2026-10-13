package org.teamsai.saibackend.domain.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpClientErrorException;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RestClient가 내부에서 생성되므로 실제 외부 RAG 서버 대신
 * JDK 내장 HTTP 서버를 localhost 임의 포트에서 실행한다.
 */
@DisplayName("RagApiClient HTTP 통신 테스트")
class RagApiClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private RagApiClient client;

    private final AtomicReference<String> receivedMethod = new AtomicReference<>();
    private final AtomicReference<String> receivedBody = new AtomicReference<>();

    private volatile int responseStatus;
    private volatile String responseBody;

    @BeforeEach
    void setUp() throws IOException {
        responseStatus = 200;
        responseBody = "{}";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rag/contracts/review", this::handleRequest);
        server.createContext("/rag/contracts/clause-suggestions", this::handleRequest);
        server.start();
        client = new RagApiClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void handleRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            receivedMethod.set(exchange.getRequestMethod());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(responseStatus, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Nested
    @DisplayName("정상 응답")
    class SuccessfulResponse {

        @Test
        @DisplayName("법률 검토 요청을 POST로 전송하고 JSON 응답을 변환한다")
        void reviewContract_sendsPostJsonAndParsesResponse() throws Exception {
            responseBody = "{\"review\":{\"disclaimer\":\"참고용입니다\"}}";
            RagContractReviewRequest request = RagContractReviewRequest.builder()
                    .principal(new BigDecimal("25000000"))
                    .interestRate(new BigDecimal("0.5"))
                    .repaymentDate("2027-01-01")
                    .build();

            ContractLegalReviewResponse result = client.reviewContract(request);

            assertEquals("POST", receivedMethod.get());
            JsonNode body = mapper.readTree(receivedBody.get());
            assertEquals(25000000, body.get("principal").asInt());
            assertEquals("0.5", body.get("interestRate").asText());
            assertEquals("2027-01-01", body.get("repaymentDate").asText());
            assertEquals("참고용입니다", result.getReview().getDisclaimer());
        }

        @Test
        @DisplayName("특약 생성 요청을 POST로 전송하고 JSON 응답을 변환한다")
        void suggestClause_sendsPostJsonAndParsesResponse() throws Exception {
            responseBody = "{\"suggestedClause\":\"상환 특약\",\"warnings\":[\"확인 필요\"]}";
            RagClauseSuggestionRequest request = RagClauseSuggestionRequest.builder()
                    .message("상환 특약을 작성해 줘")
                    .contract(RagClauseSuggestionRequest.ContractContext.builder()
                            .principal(new BigDecimal("1000000"))
                            .repaymentDay(1)
                            .build())
                    .build();

            ContractClauseResponse result = client.suggestClause(request);

            assertEquals("POST", receivedMethod.get());
            JsonNode body = mapper.readTree(receivedBody.get());
            assertEquals("상환 특약을 작성해 줘", body.get("message").asText());
            assertEquals(1000000, body.get("contract").get("principal").asInt());
            assertEquals(1, body.get("contract").get("repaymentDay").asInt());
            assertEquals("상환 특약", result.getSuggestedClause());
            assertEquals("확인 필요", result.getWarnings().get(0));
        }
    }

    @Nested
    @DisplayName("빈 응답")
    class EmptyResponse {

        @Test
        @DisplayName("법률 검토 응답이 비어 있으면 예외를 발생시킨다")
        void reviewContract_emptyResponse_throwsIllegalStateException() {
            responseStatus = 204;
            responseBody = "";

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.reviewContract(RagContractReviewRequest.builder().build()));
            assertEquals("RAG 서버로부터 법률 검토 응답을 받지 못했습니다.", ex.getMessage());
        }

        @Test
        @DisplayName("특약 생성 응답이 비어 있으면 예외를 발생시킨다")
        void suggestClause_emptyResponse_throwsIllegalStateException() {
            responseStatus = 204;
            responseBody = "";

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> client.suggestClause(RagClauseSuggestionRequest.builder().message("특약").build()));
            assertEquals("RAG 서버로부터 특약 생성 응답을 받지 못했습니다.", ex.getMessage());
        }
    }

    @Nested
    @DisplayName("HTTP 오류")
    class HttpError {

        @Test
        @DisplayName("법률 검토 서버가 500을 반환하면 HTTP 예외를 전파한다")
        void reviewContract_serverError_propagatesHttpException() {
            responseStatus = 500;
            responseBody = "{\"error\":\"internal error\"}";

            assertThrows(HttpServerErrorException.class,
                    () -> client.reviewContract(RagContractReviewRequest.builder().build()));
        }

        @Test
        @DisplayName("특약 생성 서버가 400을 반환하면 HTTP 예외를 전파한다")
        void suggestClause_badRequest_propagatesHttpException() {
            responseStatus = 400;
            responseBody = "{\"error\":\"invalid request\"}";

            assertThrows(HttpClientErrorException.class,
                    () -> client.suggestClause(RagClauseSuggestionRequest.builder().message("특약").build()));
        }
    }
}
