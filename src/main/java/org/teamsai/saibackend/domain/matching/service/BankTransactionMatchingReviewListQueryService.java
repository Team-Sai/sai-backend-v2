package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.assembler.MatchingAssembler;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.request.MatchingReviewSearchCondition;
import org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchingReviewResponse;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchingReviewQueryRepository;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionReviewQueryDTO;
import org.teamsai.saibackend.domain.transaction.dto.response.PageResponse;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankTransactionMatchingReviewListQueryService {

    private final BankTransactionMatchingReviewQueryRepository reviewQueryRepository;
    private final BankTransactionMatchCandidateService candidateService;

    public PageResponse<BankTransactionMatchingReviewResponse> getReviews(
            Long userId,
            MatchingReviewSearchCondition condition
    ) {
        List<BankTransactionReviewQueryDTO> transactions =
                reviewQueryRepository.search(userId, condition);
        long totalCount = reviewQueryRepository.count(userId, condition);

        if (transactions.isEmpty()) {
            return PageResponse.of(
                    List.of(),
                    condition.page(),
                    condition.size(),
                    totalCount
            );
        }

        List<Long> transactionIds = transactions.stream()
                .map(BankTransactionReviewQueryDTO::bankTransactionId)
                .toList();

        Map<Long, List<BankTransactionMatchCandidateQueryDTO>> candidatesByTransaction =
                candidateService.findAllForReviewByBankTransactionIds(
                                transactionIds,
                                condition.targetType(),
                                condition.aggregateId()
                        ).stream()
                        .collect(Collectors.groupingBy(
                                BankTransactionMatchCandidateQueryDTO::getBankTransactionId
                        ));

        List<BankTransactionMatchingReviewResponse> content = transactions.stream()
                .map(transaction -> MatchingAssembler.toReviewResponse(
                        transaction,
                        candidatesByTransaction.getOrDefault(
                                transaction.bankTransactionId(),
                                List.of()
                        )
                ))
                .toList();

        return PageResponse.of(
                content,
                condition.page(),
                condition.size(),
                totalCount
        );
    }
}
