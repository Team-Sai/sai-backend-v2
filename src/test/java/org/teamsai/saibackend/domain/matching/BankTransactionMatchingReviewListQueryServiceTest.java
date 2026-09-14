package org.teamsai.saibackend.domain.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.request.MatchingReviewSearchCondition;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchingReviewQueryRepository;
import org.teamsai.saibackend.domain.matching.service.BankTransactionMatchCandidateService;
import org.teamsai.saibackend.domain.matching.service.BankTransactionMatchingReviewListQueryService;
import org.teamsai.saibackend.domain.matching.type.MatchingAmountType;
import org.teamsai.saibackend.domain.matching.type.MatchingReviewChannel;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionReviewQueryDTO;
import org.teamsai.saibackend.domain.transaction.dto.response.PageResponse;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BankTransactionMatchingReviewListQueryServiceTest {

    @Mock
    private BankTransactionMatchingReviewQueryRepository reviewQueryRepository;
    @Mock
    private BankTransactionMatchCandidateService candidateService;

    private BankTransactionMatchingReviewListQueryService service;

    @BeforeEach
    void setUp() {
        service = new BankTransactionMatchingReviewListQueryService(
                reviewQueryRepository,
                candidateService
        );
    }

    @Test
    void groupsCandidatesByTransactionAndReturnsPage() {
        MatchingReviewSearchCondition condition =
                new MatchingReviewSearchCondition(
                        MatchingReviewChannel.TRANSACTION_HISTORY,
                        MatchingTargetType.SETTLEMENT,
                        null,
                        0,
                        20
                );
        BankTransactionReviewQueryDTO transaction = transaction(10L);
        BankTransactionMatchCandidateQueryDTO candidate = candidate(10L);

        given(reviewQueryRepository.search(1L, condition))
                .willReturn(List.of(transaction));
        given(reviewQueryRepository.count(1L, condition)).willReturn(1L);
        given(candidateService.findAllForReviewByBankTransactionIds(
                List.of(10L),
                MatchingTargetType.SETTLEMENT,
                null
        )).willReturn(List.of(candidate));

        PageResponse<org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchingReviewResponse> result =
                service.getReviews(1L, condition);

        assertThat(result.totalCount()).isEqualTo(1);
        assertThat(result.content().get(0).transaction()).isEqualTo(
                new org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse(
                        10L, 2L, new BigDecimal("5000"),
                        BankTransactionType.DEPOSIT, BankTransactionProcessingStatus.NEEDS_CHECK,
                        LocalDateTime.of(2026, 8, 17, 10, 0), "sender", null,
                        LocalDateTime.of(2026, 8, 17, 10, 1)
                )
        );
        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).reviewChannel())
                .isEqualTo(MatchingReviewChannel.TRANSACTION_HISTORY);
        assertThat(result.content().get(0).candidates().get(0).aggregateId())
                .isEqualTo(100L);
        assertThat(result.content().get(0).candidates().get(0).targetName())
                .isEqualTo("8월 회식비 정산");
    }

    @Test
    void returnsEmptyPageWithoutCandidateLookup() {
        MatchingReviewSearchCondition condition =
                new MatchingReviewSearchCondition(
                        MatchingReviewChannel.TRANSACTION_HISTORY,
                        null,
                        null,
                        1,
                        20
                );
        given(reviewQueryRepository.search(1L, condition)).willReturn(List.of());
        given(reviewQueryRepository.count(1L, condition)).willReturn(25L);

        PageResponse<org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchingReviewResponse> result =
                service.getReviews(1L, condition);

        assertThat(result.content()).isEmpty();
        assertThat(result.totalCount()).isEqualTo(25);
        assertThat(result.hasPrevious()).isTrue();
        verify(candidateService, never())
                .findAllForReviewByBankTransactionIds(
                        org.mockito.ArgumentMatchers.anyList(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
    }

    @Test
    void keepsCrossDomainCandidatesInTransactionHistoryChannel() {
        MatchingReviewSearchCondition condition =
                new MatchingReviewSearchCondition(
                        MatchingReviewChannel.TRANSACTION_HISTORY,
                        null,
                        null,
                        0,
                        20
                );
        BankTransactionReviewQueryDTO transaction = transaction(11L);
        given(reviewQueryRepository.search(1L, condition))
                .willReturn(List.of(transaction));
        given(reviewQueryRepository.count(1L, condition)).willReturn(1L);
        given(candidateService.findAllForReviewByBankTransactionIds(
                List.of(11L), null, null
        )).willReturn(List.of(
                candidate(11L),
                candidateWithTarget(11L, MatchingTargetType.LOAN)
        ));

        var result = service.getReviews(1L, condition);

        assertThat(result.content()).hasSize(1);
        assertThat(result.content().get(0).reviewChannel())
                .isEqualTo(MatchingReviewChannel.TRANSACTION_HISTORY);
        assertThat(result.content().get(0).candidates()).hasSize(2);
    }

    private BankTransactionReviewQueryDTO transaction(Long id) {
        return new BankTransactionReviewQueryDTO(
                id,
                2L,
                new BigDecimal("5000"),
                BankTransactionType.DEPOSIT,
                BankTransactionProcessingStatus.NEEDS_CHECK,
                LocalDateTime.of(2026, 8, 17, 10, 0),
                "sender",
                null,
                LocalDateTime.of(2026, 8, 17, 10, 1)
        );
    }

    private BankTransactionMatchCandidateQueryDTO candidate(Long transactionId) {
        return BankTransactionMatchCandidateQueryDTO.builder()
                .matchCandidateId(20L)
                .bankTransactionId(transactionId)
                .targetType(MatchingTargetType.SETTLEMENT)
                .targetId(30L)
                .aggregateId(100L)
                .targetName("8월 회식비 정산")
                .participantName("participant")
                .expectedRemainingAmount(new BigDecimal("10000"))
                .amountMatchType(MatchingAmountType.PARTIAL)
                .createdAt(LocalDateTime.of(2026, 8, 17, 10, 1))
                .build();
    }

    private BankTransactionMatchCandidateQueryDTO candidateWithTarget(
            Long transactionId,
            MatchingTargetType targetType
    ) {
        return BankTransactionMatchCandidateQueryDTO.builder()
                .matchCandidateId(21L)
                .bankTransactionId(transactionId)
                .targetType(targetType)
                .targetId(31L)
                .aggregateId(101L)
                .targetName("loan")
                .participantName("participant")
                .expectedRemainingAmount(new BigDecimal("10000"))
                .amountMatchType(MatchingAmountType.PARTIAL)
                .createdAt(LocalDateTime.of(2026, 8, 17, 10, 1))
                .build();
    }
}
