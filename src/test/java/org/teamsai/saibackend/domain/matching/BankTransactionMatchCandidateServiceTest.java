package org.teamsai.saibackend.domain.matching;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.teamsai.saibackend.domain.matching.model.EvaluatedMatchingCandidate;
import org.teamsai.saibackend.domain.matching.model.MatchingCandidate;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchCandidateQueryRepository;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.matching.entity.BankTransactionMatchCandidateEntity;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.exception.MatchingErrorCode;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchCandidateRepository;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchCandidateValidationQueryRepository;
import org.teamsai.saibackend.domain.matching.service.BankTransactionMatchCandidateService;
import org.teamsai.saibackend.domain.matching.type.MatchingAmountType;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateInvalidationReason;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateStatus;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BankTransactionMatchCandidateServiceTest {

    @Mock
    private BankTransactionMatchCandidateRepository candidateRepository;

    @Mock
    private BankTransactionMatchCandidateQueryRepository candidateQueryRepository;

    @Mock
    private BankTransactionMatchCandidateValidationQueryRepository candidateValidationQueryRepository;

    private BankTransactionMatchCandidateService candidateService;

    @BeforeEach
    void setUp() {
        candidateService = new BankTransactionMatchCandidateService(
                candidateRepository,
                candidateQueryRepository,
                candidateValidationQueryRepository
        );
    }

    @Test
    void savesEvaluatedCandidatesAsEntities() {
        Long bankTransactionId = 100L;
        EvaluatedMatchingCandidate settlementCandidate =
                evaluatedCandidate(
                        MatchingTargetType.SETTLEMENT,
                        10L,
                        "10000",
                        MatchingAmountType.PARTIAL
                );
        EvaluatedMatchingCandidate loanCandidate =
                evaluatedCandidate(
                        MatchingTargetType.LOAN,
                        20L,
                        "20000",
                        MatchingAmountType.EXACT
                );

        candidateService.saveAll(
                bankTransactionId,
                List.of(settlementCandidate, loanCandidate)
        );

        ArgumentCaptor<List<BankTransactionMatchCandidateEntity>> captor =
                ArgumentCaptor.forClass(List.class);
        verify(candidateRepository).saveAllAndFlush(captor.capture());

        List<BankTransactionMatchCandidateEntity> savedCandidates =
                captor.getValue();

        assertThat(savedCandidates).hasSize(2);
        assertThat(savedCandidates)
                .extracting(
                        BankTransactionMatchCandidateEntity::getBankTransactionId,
                        BankTransactionMatchCandidateEntity::getTargetType,
                        BankTransactionMatchCandidateEntity::getTargetId,
                        BankTransactionMatchCandidateEntity::getExpectedRemainingAmount,
                        BankTransactionMatchCandidateEntity::getAmountMatchType,
                        BankTransactionMatchCandidateEntity::getCandidateStatus
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                100L,
                                MatchingTargetType.SETTLEMENT,
                                10L,
                                new BigDecimal("10000"),
                                MatchingAmountType.PARTIAL,
                                MatchingCandidateStatus.AVAILABLE
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                100L,
                                MatchingTargetType.LOAN,
                                20L,
                                new BigDecimal("20000"),
                                MatchingAmountType.EXACT,
                                MatchingCandidateStatus.AVAILABLE
                        )
                );
        assertThat(savedCandidates)
                .allSatisfy(candidate -> {
                    assertThat(candidate.getInvalidatedAt()).isNull();
                    assertThat(candidate.getInvalidationReason()).isNull();
                });
        assertThat(savedCandidates)
                .extracting(BankTransactionMatchCandidateEntity::getCreatedAt)
                .doesNotContainNull()
                .allMatch(createdAt -> createdAt.equals(
                        savedCandidates.get(0).getCreatedAt()
                ));
    }

    @Test
    void doesNotCallRepositoryWhenCandidatesAreEmpty() {
        candidateService.saveAll(100L, List.of());

        verify(candidateRepository, never()).saveAllAndFlush(
                org.mockito.ArgumentMatchers.anyList()
        );
    }

    @Test
    void failsWhenPersistenceFails() {
        EvaluatedMatchingCandidate candidate = evaluatedCandidate(
                MatchingTargetType.SETTLEMENT,
                10L,
                "10000",
                MatchingAmountType.EXACT
        );

        when(candidateRepository.saveAllAndFlush(org.mockito.ArgumentMatchers.anyList()))
                .thenThrow(new DataIntegrityViolationException("duplicate candidate"));

        assertThatThrownBy(() -> candidateService.saveAll(
                100L,
                List.of(candidate)
        )).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                MatchingErrorCode
                                        .MATCHING_CANDIDATE_SAVE_FAILED
                        )
        );
    }

    @Test
    void failsWhenSaveRequestContainsNullCandidate() {
        assertThatThrownBy(() -> candidateService.saveAll(
                100L,
                java.util.Arrays.asList(
                        (EvaluatedMatchingCandidate) null
                )
        )).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(MatchingErrorCode.INVALID_MATCHING_REQUEST)
        );
    }

    @Test
    void returnsCandidatesForBankTransaction() {
        BankTransactionMatchCandidateEntity candidate = dto(1L, 100L);

        when(candidateRepository.findAllByBankTransactionIdOrderByMatchCandidateIdAsc(100L))
                .thenReturn(List.of(candidate));

        List<BankTransactionMatchCandidateEntity> result =
                candidateService.findAllByBankTransactionId(100L);

        assertThat(result).containsExactly(candidate);
    }

    @Test
    void returnsCandidatesWithParticipantNameForReview() {
        BankTransactionMatchCandidateQueryDTO candidate =
                BankTransactionMatchCandidateQueryDTO.builder()
                        .matchCandidateId(1L)
                        .bankTransactionId(100L)
                        .targetType(MatchingTargetType.SETTLEMENT)
                        .targetId(10L)
                        .participantName("홍길동")
                        .expectedRemainingAmount(
                                new BigDecimal("10000")
                        )
                        .amountMatchType(MatchingAmountType.EXACT)
                        .createdAt(LocalDateTime.now())
                        .build();

        when(candidateQueryRepository.findAllForReviewByBankTransactionId(100L))
                .thenReturn(List.of(candidate));

        List<BankTransactionMatchCandidateQueryDTO> result =
                candidateService.findAllForReviewByBankTransactionId(100L);

        assertThat(result).containsExactly(candidate);
    }

    @Test
    void returnsCandidatesForTransactionPage() {
        BankTransactionMatchCandidateQueryDTO candidate =
                BankTransactionMatchCandidateQueryDTO.builder()
                        .matchCandidateId(1L)
                        .bankTransactionId(100L)
                        .targetType(MatchingTargetType.SETTLEMENT)
                        .targetId(10L)
                        .aggregateId(20L)
                        .build();

        when(candidateQueryRepository.findAllForReviewByBankTransactionIds(
                List.of(100L, 101L),
                MatchingTargetType.SETTLEMENT,
                20L
        )).thenReturn(List.of(candidate));

        List<BankTransactionMatchCandidateQueryDTO> result =
                candidateService.findAllForReviewByBankTransactionIds(
                        List.of(100L, 101L),
                        MatchingTargetType.SETTLEMENT,
                        20L
                );

        assertThat(result).containsExactly(candidate);
    }

    @Test
    void rejectsEmptyTransactionPage() {
        assertThatThrownBy(() ->
                candidateService.findAllForReviewByBankTransactionIds(
                        List.of(),
                        null,
                        null
                )
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(MatchingErrorCode.INVALID_MATCHING_REQUEST)
        );

        verify(candidateQueryRepository, never())
                .findAllForReviewByBankTransactionIds(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any()
                );
    }

    @Test
    void returnsCandidateBelongingToBankTransaction() {
        BankTransactionMatchCandidateEntity candidate = dto(1L, 100L);

        when(candidateValidationQueryRepository.findByIdAndBankTransactionId(1L, 100L))
                .thenReturn(Optional.of(candidate));

        BankTransactionMatchCandidateEntity result =
                candidateService.findByIdAndBankTransactionId(1L, 100L);

        assertThat(result).isSameAs(candidate);
    }

    @Test
    void failsWhenCandidateDoesNotBelongToBankTransaction() {
        when(candidateValidationQueryRepository.findByIdAndBankTransactionId(1L, 100L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                candidateService.findByIdAndBankTransactionId(1L, 100L)
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                MatchingErrorCode.MATCHING_CANDIDATE_NOT_FOUND
                        )
        );
    }

    @Test
    void invalidatesAvailableCandidate() {
        BankTransactionMatchCandidateEntity candidate = dto(1L, 100L);
        when(candidateRepository.findAvailableForUpdate(
                1L, 100L, MatchingCandidateStatus.AVAILABLE
        )).thenReturn(Optional.of(candidate));

        candidateService.invalidateCandidate(
                1L, 100L, MatchingCandidateInvalidationReason.TARGET_NOT_AVAILABLE
        );

        // UPDATE 호출 횟수 대신 실제 Entity의 상태 변경을 검증한다.
        assertThat(candidate.getCandidateStatus()).isEqualTo(MatchingCandidateStatus.INVALIDATED);
        assertThat(candidate.getInvalidationReason())
                .isEqualTo(MatchingCandidateInvalidationReason.TARGET_NOT_AVAILABLE);
        assertThat(candidate.getInvalidatedAt()).isNotNull();
    }

    @Test
    void failsWhenCandidateCannotBeInvalidated() {
        when(candidateRepository.findAvailableForUpdate(
                1L, 100L, MatchingCandidateStatus.AVAILABLE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> candidateService.invalidateCandidate(
                1L, 100L, MatchingCandidateInvalidationReason.TARGET_NOT_FOUND
        )).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(MatchingErrorCode.MATCHING_CANDIDATE_INVALIDATION_FAILED)
        );
    }

    @Test
    void failsWhenInvalidationReasonIsNull() {
        assertThatThrownBy(() -> candidateService.invalidateCandidate(
                1L, 100L, null
        )).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(MatchingErrorCode.INVALID_MATCHING_REQUEST)
        );
        verify(candidateRepository, never()).findAvailableForUpdate(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void deletesOnlyRequestedTransactionCandidates() {
        candidateService.deleteAllByBankTransactionId(100L);
        verify(candidateRepository).deleteAllByBankTransactionId(100L);
    }

    @Test
    void rejectsInvalidTransactionIdBeforeDeleting() {
        assertThatThrownBy(() -> candidateService.deleteAllByBankTransactionId(0L))
                .isInstanceOf(DomainException.class);
        verify(candidateRepository, never()).deleteAllByBankTransactionId(
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void returnsAvailableCandidateCount() {
        when(candidateRepository.countByBankTransactionIdAndCandidateStatus(100L, MatchingCandidateStatus.AVAILABLE))
                .thenReturn(2L);

        int result = candidateService.countAvailableCandidates(100L);

        assertThat(result).isEqualTo(2);
    }

    private EvaluatedMatchingCandidate evaluatedCandidate(
            MatchingTargetType targetType,
            Long targetId,
            String remainingAmount,
            MatchingAmountType amountMatchType
    ) {
        MatchingCandidate candidate = new MatchingCandidate(
                targetType,
                targetId,
                targetId,
                "HongGilDong",
                new BigDecimal(remainingAmount)
        );

        return new EvaluatedMatchingCandidate(candidate, amountMatchType);
    }

    private BankTransactionMatchCandidateEntity dto(
            Long matchCandidateId,
            Long bankTransactionId
    ) {
        return BankTransactionMatchCandidateEntity.builder()
                .matchCandidateId(matchCandidateId)
                .bankTransactionId(bankTransactionId)
                .targetType(MatchingTargetType.SETTLEMENT)
                .targetId(10L)
                .expectedRemainingAmount(new BigDecimal("10000"))
                .amountMatchType(MatchingAmountType.EXACT)
                .candidateStatus(MatchingCandidateStatus.AVAILABLE)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
