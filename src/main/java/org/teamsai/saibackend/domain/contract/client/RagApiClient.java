package org.teamsai.saibackend.domain.contract.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;

@Slf4j
@Component
public class RagApiClient {

    private final RestClient restClient;

    public RagApiClient(
            @Value("${ai.rag.base-url}") String ragBaseUrl
    ) {
        this.restClient = RestClient.builder()
                .baseUrl(ragBaseUrl)
                .build();

        log.info(
                "RAG API Client initialized - baseUrl={}",
                ragBaseUrl
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
                restClient.post()
                        .uri("/rag/contracts/review")
                        .body(request)
                        .retrieve()
                        .body(ContractLegalReviewResponse.class);

        if (response == null) {
            throw new IllegalStateException(
                    "RAG 서버로부터 법률 검토 응답을 받지 못했습니다."
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
                restClient.post()
                        .uri("/rag/contracts/clause-suggestions")
                        .body(request)
                        .retrieve()
                        .body(ContractClauseResponse.class);

        if (response == null) {
            throw new IllegalStateException(
                    "RAG 서버로부터 특약 생성 응답을 받지 못했습니다."
            );
        }

        return response;
    }
}