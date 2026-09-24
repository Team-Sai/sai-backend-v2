package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.assembler.MatchingAssembler;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchingReviewResponse;
import org.teamsai.saibackend.domain.matching.MatchingReviewValidator;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionQueryService;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankTransactionMatchingReviewQueryService {

    private final BankTransactionQueryService bankTransactionQueryService;
    private final BankTransactionMatchCandidateService candidateService;
    private final MatchingReviewValidator matchingReviewValidator;

    public BankTransactionMatchingReviewResponse getReview(
            Long userId,
            Long linkedAccountId,
            Long bankTransactionId
    ) {
        BankTransactionDetailResponse transaction =
                bankTransactionQueryService.getTransactionDetail(
                        userId,
                        linkedAccountId,
                        bankTransactionId
                );

        matchingReviewValidator.validate(transaction);

        List<BankTransactionMatchCandidateQueryDTO> candidates =
                candidateService.findAllForReviewByBankTransactionId(
                        bankTransactionId
                );

        return MatchingAssembler.toReviewResponse(transaction, candidates);
    }
}
