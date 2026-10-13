
package org.teamsai.saibackend.domain.contract.client;

import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;

@Slf4j
@Component
public class RagApiClient {

    private final RestClient restClient;

    public RagApiClient(
            @Value("${ai.rag.base-url}") String ragBaseUrl,
            @Value("${ai.rag.connect-timeout:5s}") Duration connectTimeout,
            @Value("${ai.rag.read-timeout:30s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory requestFactory =
                new SimpleClientHttpRequestFactory();

        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(ragBaseUrl)
                .requestFactory(requestFactory)
                .build();

        log.info(
                "RAG API Client initialized - baseUrl={}, connectTimeout={}, readTimeout={}",
                ragBaseUrl,
                connectTimeout,
                readTimeout
        );
    }

    public ContractLegalReviewResponse reviewContract(
            RagContractReviewRequest request
    ) {
        log.info(
                "RAG 차용증 검토 요청 - principal={}, interestRate={}",
                request.getPrincipal(),
                request.getInterestRate()
        );

        ContractLegalReviewResponse response =
                executeRequest(
                        "/rag/contracts/review",
                        request,
                        ContractLegalReviewResponse.class
                );

        if (response.getReview() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "RAG 법률 검토 결과가 누락되었습니다."
            );
        }

        return response;
    }

    public ContractClauseResponse suggestClause(
            RagClauseSuggestionRequest request
    ) {
        log.info(
                "RAG 특약 Agent 요청 - message={}",
                request.getMessage()
        );

        ContractClauseResponse response =
                executeRequest(
                        "/rag/contracts/clause-suggestions",
                        request,
                        ContractClauseResponse.class
                );

        boolean hasClause =
                StringUtils.hasText(
                        response.getSuggestedClause()
                );

        boolean isRejected =
                response.getValidation() != null
                        && Boolean.FALSE.equals(
                        response.getValidation()
                                .getIsSafeToSuggest()
                );

        boolean hasReason =
                StringUtils.hasText(response.getMessage())
                        || (response.getIssues() != null
                        && !response.getIssues().isEmpty())
                        || (response.getWarnings() != null
                        && !response.getWarnings().isEmpty());

        if (!hasClause && !(isRejected && hasReason)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "RAG 특약 생성 결과가 누락되었습니다."
            );
        }

        return response;
    }

    private <T> T executeRequest(
            String uri,
            Object request,
            Class<T> responseType
    ) {
        try {
            T response = restClient.post()
                    .uri(uri)
                    .body(request)
                    .retrieve()
                    .body(responseType);

            if (response == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "RAG 서버 응답이 비어 있습니다."
                );
            }

            return response;

        } catch (ResponseStatusException e) {
            throw e;

        } catch (RestClientResponseException e) {
            log.error(
                    "RAG 서버 HTTP 오류 - uri={}, status={}, exceptionType={}",
                    uri,
                    e.getStatusCode(),
                    e.getClass().getName(),
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "RAG 서버에서 요청을 정상적으로 처리하지 못했습니다.",
                    e
            );

        } catch (ResourceAccessException e) {
            if (isTimeout(e)) {
                log.error(
                        "RAG 서버 타임아웃 - uri={}, exceptionType={}",
                        uri,
                        e.getClass().getName(),
                        e
                );

                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "RAG 서버 응답 시간이 초과되었습니다.",
                        e
                );
            }

            log.error(
                    "RAG 서버 연결 실패 - uri={}, exceptionType={}",
                    uri,
                    e.getClass().getName(),
                    e
            );

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "RAG 서버에 연결할 수 없습니다.",
                    e
            );

        } catch (RestClientException e) {
            log.error(
                    "RAG 서버 통신 오류 - uri={}, exceptionType={}, causeType={}",
                    uri,
                    e.getClass().getName(),
                    e.getCause() != null
                            ? e.getCause().getClass().getName()
                            : "none",
                    e
            );

            if (isTimeout(e)) {
                throw new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT,
                        "RAG 서버 응답 시간이 초과되었습니다.",
                        e
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "RAG 서버 통신 중 오류가 발생했습니다.",
                    e
            );
        }
    }

    private boolean isTimeout(Throwable throwable) {
        Throwable cause = throwable;

        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof HttpTimeoutException
                    || cause instanceof TimeoutException) {
                return true;
            }

            cause = cause.getCause();
        }

        return false;
    }
}
