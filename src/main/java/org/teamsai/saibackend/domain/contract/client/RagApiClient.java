package org.teamsai.saibackend.domain.contract.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;

import java.net.SocketTimeoutException;
import java.time.Duration;

@Slf4j
@Component
public class RagApiClient {

    private final RestClient restClient;

    public RagApiClient(
            @Value("${ai.rag.base-url}") String ragBaseUrl,
            @Value("${ai.rag.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${ai.rag.read-timeout:PT60S}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(ragBaseUrl)
                .requestFactory(requestFactory)
                .build();

        log.info("RAG API Client initialized");
    }

    public ContractLegalReviewResponse reviewContract(
            RagContractReviewRequest request
    ) {
        log.info("RAG 차용증 법률 검토 요청");

        return executeRequest(
                "/rag/contracts/review",
                request,
                ContractLegalReviewResponse.class
        );
    }

    public ContractClauseResponse suggestClause(
            RagClauseSuggestionRequest request
    ) {
        log.info(
                "RAG 특약 Agent 요청 - messageLength={}",
                request.getMessage() != null
                        ? request.getMessage().length()
                        : 0
        );

        return executeRequest(
                "/rag/contracts/clause-suggestions",
                request,
                ContractClauseResponse.class
        );
    }

    private <T> T executeRequest(
            String path,
            Object request,
            Class<T> responseType
    ) {
        try {
            T response = restClient.post()
                    .uri(path)
                    .body(request)
                    .retrieve()
                    .body(responseType);

            if (response == null) {
                log.error("RAG 서버 빈 응답 - path={}", path);

                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "AI 서버에서 정상적인 응답을 받지 못했습니다."
                );
            }

            return response;

        } catch (RestClientResponseException e) {
            log.error(
                    "RAG 서버 HTTP 오류 - path={}, status={}",
                    path,
                    e.getStatusCode()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI 서버 요청 처리 중 오류가 발생했습니다.",
                    e
            );

        } catch (RestClientException e) {

            // RestClientException 내부에 타임아웃이 있는지 먼저 확인
            if (isTimeout(e)) {
                log.error(
                        "RAG 서버 응답 시간 초과 - path={}",
                        path,
                        e
                );

                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "AI 서버 응답 시간이 초과되었습니다.",
                        e
                );
            }

            if (e instanceof ResourceAccessException) {
                log.error(
                        "RAG 서버 연결 실패 - path={}",
                        path,
                        e
                );

                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "현재 AI 서버에 연결할 수 없습니다.",
                        e
                );
            }

            log.error(
                    "RAG 서버 응답 처리 실패 - path={}",
                    path,
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "AI 서버 응답을 처리할 수 없습니다.",
                    e
            );
        }
    }

    private boolean isTimeout(Throwable exception) {
        Throwable cause = exception;

        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}