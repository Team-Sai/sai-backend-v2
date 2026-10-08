package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.ContractClauseRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagClauseSuggestionRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractClauseResponse;

@Service
@RequiredArgsConstructor
public class ContractClauseAgentService {

    private final RagApiClient ragApiClient;

    public ContractClauseResponse suggestClause(
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
                                request.getRepaymentType()
                        )
                        .contractAlias(
                                request.getContractAlias()
                        )
                        .terms(
                                request.getTerms()
                        )
                        .build();

        RagClauseSuggestionRequest ragRequest =
                RagClauseSuggestionRequest.builder()
                        .contract(context)
                        .message(
                                request.getMessage().trim()
                        )
                        .build();

        return ragApiClient.suggestClause(
                ragRequest
        );
    }
}