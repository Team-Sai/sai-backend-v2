package org.teamsai.saibackend.domain.contract.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.client.RagApiClient;
import org.teamsai.saibackend.domain.contract.dto.request.ContractLegalReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.request.RagContractReviewRequest;
import org.teamsai.saibackend.domain.contract.dto.response.ContractLegalReviewResponse;

@Service
@RequiredArgsConstructor
public class ContractLegalReviewService {

    private final RagApiClient ragApiClient;

    public ContractLegalReviewResponse review(
            ContractLegalReviewRequest request
    ) {

        RagContractReviewRequest ragRequest =
                RagContractReviewRequest.builder()
                        .principal(request.getPrincipalAmount())
                        .interestRate(request.getInterestRate())
                        .lateInterestRate(request.getLateInterestRate())
                        .repaymentDate(
                                request.getMaturityDate() != null
                                        ? request.getMaturityDate().toString()
                                        : null
                        )
                        .build();

        return ragApiClient.reviewContract(
                ragRequest
        );
    }
}