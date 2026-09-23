package org.teamsai.saibackend.domain.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.matching.assembler.MatchingAssembler;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionReviewQueryDTO;
import org.teamsai.saibackend.domain.matching.dto.response.BankTransactionMatchingReviewResponse;
import org.teamsai.saibackend.domain.matching.type.MatchingReviewChannel;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MatchingAssembler 단위 테스트")
class MatchingAssemblerTest {

    private BankTransactionReviewQueryDTO reviewQueryDTO() {
        return new BankTransactionReviewQueryDTO(
                1L, 2L, BigDecimal.valueOf(50_000),
                BankTransactionType.DEPOSIT, BankTransactionProcessingStatus.NEEDS_CHECK,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                "홍길동", "메모", LocalDateTime.of(2026, 8, 1, 0, 5)
        );
    }

    private BankTransactionDetailResponse transactionDetail() {
        return new BankTransactionDetailResponse(
                1L, 2L, BigDecimal.valueOf(50_000),
                BankTransactionType.DEPOSIT, BankTransactionProcessingStatus.NEEDS_CHECK,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                "홍길동", "메모", LocalDateTime.of(2026, 8, 1, 0, 5)
        );
    }

    private BankTransactionMatchCandidateQueryDTO candidate(Long candidateId) {
        return BankTransactionMatchCandidateQueryDTO.builder()
                .matchCandidateId(candidateId)
                .bankTransactionId(1L)
                .targetType(MatchingTargetType.LOAN)
                .targetId(10L)
                .targetName("차용증")
                .expectedRemainingAmount(BigDecimal.valueOf(50_000))
                .build();
    }

    @Nested
    @DisplayName("조회용 DTO 기준 조립")
    class FromReviewQueryDTO {

        @Test
        @DisplayName("거래 정보와 후보 목록을 조합한다")
        void combinesTransactionAndCandidates() {
            BankTransactionMatchingReviewResponse result =
                    MatchingAssembler.toReviewResponse(reviewQueryDTO(), List.of(candidate(1L), candidate(2L)));

            assertThat(result.transaction().bankTransactionId()).isEqualTo(1L);
            assertThat(result.transaction().counterpartyName()).isEqualTo("홍길동");
            assertThat(result.reviewChannel()).isEqualTo(MatchingReviewChannel.TRANSACTION_HISTORY);
            assertThat(result.candidates()).hasSize(2);
            assertThat(result.candidates().get(0).matchCandidateId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("후보가 없어도 빈 목록으로 조립된다")
        void handlesEmptyCandidates() {
            BankTransactionMatchingReviewResponse result =
                    MatchingAssembler.toReviewResponse(reviewQueryDTO(), List.of());

            assertThat(result.candidates()).isEmpty();
        }
    }

    @Nested
    @DisplayName("이미 변환된 거래 상세 기준 조립")
    class FromTransactionDetail {

        @Test
        @DisplayName("거래 상세를 그대로 쓰고 후보만 변환해서 조합한다")
        void reusesTransactionDetailAsIs() {
            BankTransactionDetailResponse transaction = transactionDetail();

            BankTransactionMatchingReviewResponse result =
                    MatchingAssembler.toReviewResponse(transaction, List.of(candidate(5L)));

            assertThat(result.transaction()).isSameAs(transaction);
            assertThat(result.candidates()).hasSize(1);
            assertThat(result.candidates().get(0).matchCandidateId()).isEqualTo(5L);
        }
    }
}
