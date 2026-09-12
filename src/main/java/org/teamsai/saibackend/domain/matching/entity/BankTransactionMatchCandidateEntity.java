package org.teamsai.saibackend.domain.matching.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.teamsai.saibackend.domain.matching.exception.MatchingErrorCode;
import org.teamsai.saibackend.domain.matching.type.MatchingAmountType;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateInvalidationReason;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateStatus;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(
        name = "bank_transaction_match_candidate",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_bank_transaction_match_candidate",
                columnNames = {"bank_transaction_id", "target_type", "target_id"}
        )
)
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BankTransactionMatchCandidateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "match_candidate_id")
    private Long matchCandidateId;

    @Column(name = "bank_transaction_id", nullable = false)
    private Long bankTransactionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 30)
    private MatchingTargetType targetType;

    // 정산 납부 의무 또는 대출 상환 회차를 가리키므로 단일 연관관계로 매핑하지 않는다.
    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "expected_remaining_amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal expectedRemainingAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "amount_match_type", nullable = false, length = 30)
    private MatchingAmountType amountMatchType;

    @Enumerated(EnumType.STRING)
    @Column(name = "candidate_status", nullable = false, length = 30)
    private MatchingCandidateStatus candidateStatus;

    @Column(name = "invalidated_at")
    private LocalDateTime invalidatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "invalidation_reason", length = 50)
    private MatchingCandidateInvalidationReason invalidationReason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public void invalidate(
            MatchingCandidateInvalidationReason reason,
            LocalDateTime invalidatedAt
    ) {
        if (reason == null || invalidatedAt == null) {
            throw MatchingErrorCode.INVALID_MATCHING_REQUEST.toException();
        }
        if (candidateStatus != MatchingCandidateStatus.AVAILABLE) {
            throw MatchingErrorCode.MATCHING_CANDIDATE_INVALIDATION_FAILED.toException();
        }

        // DB의 무효화 CHECK 제약에 맞춰 세 필드를 함께 변경한다.
        this.candidateStatus = MatchingCandidateStatus.INVALIDATED;
        this.invalidationReason = reason;
        this.invalidatedAt = invalidatedAt;
    }
}
