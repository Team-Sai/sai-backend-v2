package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.model.EvaluatedMatchingCandidate;
import org.teamsai.saibackend.domain.matching.dto.BankTransactionMatchCandidateQueryDTO;
import org.teamsai.saibackend.domain.matching.entity.BankTransactionMatchCandidateEntity;
import org.teamsai.saibackend.domain.matching.exception.MatchingErrorCode;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchCandidateQueryRepository;
import org.teamsai.saibackend.domain.matching.repository.BankTransactionMatchCandidateRepository;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateInvalidationReason;
import org.teamsai.saibackend.domain.matching.type.MatchingCandidateStatus;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankTransactionMatchCandidateService {

    private final BankTransactionMatchCandidateRepository candidateRepository;
    private final BankTransactionMatchCandidateQueryRepository candidateQueryRepository;

    @Transactional
    public void saveAll(
            Long bankTransactionId,
            List<EvaluatedMatchingCandidate> evaluatedCandidates
    ) {
        validateSaveRequest(bankTransactionId, evaluatedCandidates);

        if (evaluatedCandidates.isEmpty()) {
            return;
        }

        LocalDateTime createdAt = LocalDateTime.now();

        List<BankTransactionMatchCandidateEntity> candidates =
                evaluatedCandidates.stream()
                        .map(candidate -> toEntity(
                                bankTransactionId,
                                candidate,
                                createdAt
                        ))
                        .toList();

        try {
            // 건수 비교 대신 실제 저장 실패를 처리한다. flush는 커밋이 아니며 실패하면 롤백된다.
            candidateRepository.saveAllAndFlush(candidates);
        } catch (DataAccessException exception) {
            DomainException failure = MatchingErrorCode.MATCHING_CANDIDATE_SAVE_FAILED.toException();
            failure.initCause(exception);
            throw failure;
        }
    }

    public List<BankTransactionMatchCandidateEntity>
    findAllByBankTransactionId(Long bankTransactionId) {
        validateBankTransactionId(bankTransactionId);

        return candidateRepository.findAllByBankTransactionIdOrderByMatchCandidateIdAsc(
                bankTransactionId
        );
    }

    public List<BankTransactionMatchCandidateQueryDTO>
    findAllForReviewByBankTransactionId(Long bankTransactionId) {
        validateBankTransactionId(bankTransactionId);

        return candidateQueryRepository.findAllForReviewByBankTransactionId(
                bankTransactionId
        );
    }

    public List<BankTransactionMatchCandidateQueryDTO>
    findAllForReviewByBankTransactionIds(
            List<Long> bankTransactionIds,
            MatchingTargetType targetType,
            Long aggregateId
    ) {
        if (bankTransactionIds == null
                || bankTransactionIds.isEmpty()
                || bankTransactionIds.stream()
                .anyMatch(id -> id == null || id <= 0)) {
            throw MatchingErrorCode.INVALID_MATCHING_REQUEST.toException();
        }

        return candidateQueryRepository.findAllForReviewByBankTransactionIds(
                bankTransactionIds,
                targetType,
                aggregateId
        );
    }

    public BankTransactionMatchCandidateEntity
    findByIdAndBankTransactionId(
            Long matchCandidateId,
            Long bankTransactionId
    ) {
        validateMatchCandidateId(matchCandidateId);
        validateBankTransactionId(bankTransactionId);

        return candidateRepository.findByIdAndBankTransactionId(
                        matchCandidateId,
                        bankTransactionId
                )
                .orElseThrow(
                        MatchingErrorCode
                                .MATCHING_CANDIDATE_NOT_FOUND
                                ::toException
                );
    }

    @Transactional
    public void invalidateCandidate(
            Long matchCandidateId,
            Long bankTransactionId,
            MatchingCandidateInvalidationReason invalidationReason
    ) {
        validateMatchCandidateId(matchCandidateId);
        validateBankTransactionId(bankTransactionId);

        if (invalidationReason == null) {
            throw MatchingErrorCode
                    .INVALID_MATCHING_REQUEST
                    .toException();
        }

        BankTransactionMatchCandidateEntity candidate = candidateRepository.findAvailableForUpdate(
                matchCandidateId,
                bankTransactionId,
                MatchingCandidateStatus.AVAILABLE
        ).orElseThrow(MatchingErrorCode.MATCHING_CANDIDATE_INVALIDATION_FAILED::toException);

        // 잠금을 보유한 트랜잭션에서 변경한다. UPDATE는 JPA 변경 감지가 처리한다.
        candidate.invalidate(invalidationReason, LocalDateTime.now());
    }

    public int countAvailableCandidates(Long bankTransactionId) {
        validateBankTransactionId(bankTransactionId);

        return Math.toIntExact(candidateRepository.countByBankTransactionIdAndCandidateStatus(
                bankTransactionId,
                MatchingCandidateStatus.AVAILABLE
        ));
    }

    @Transactional
    public void deleteAllByBankTransactionId(Long bankTransactionId) {
        validateBankTransactionId(bankTransactionId);
        // 재시도 서비스가 트랜잭션 없이 호출하더라도 삭제는 쓰기 트랜잭션 안에서 수행한다.
        candidateRepository.deleteAllByBankTransactionId(bankTransactionId);
    }

    private BankTransactionMatchCandidateEntity toEntity(
            Long bankTransactionId,
            EvaluatedMatchingCandidate evaluatedCandidate,
            LocalDateTime createdAt
    ) {
        return BankTransactionMatchCandidateEntity.builder()
                .bankTransactionId(bankTransactionId)
                .targetType(
                        evaluatedCandidate.candidate().targetType()
                )
                .targetId(
                        evaluatedCandidate.candidate().targetId()
                )
                .expectedRemainingAmount(
                        evaluatedCandidate.candidate().remainingAmount()
                )
                .amountMatchType(
                        evaluatedCandidate.amountMatchType()
                )
                .candidateStatus(MatchingCandidateStatus.AVAILABLE)
                .createdAt(createdAt)
                .build();
    }

    private void validateSaveRequest(
            Long bankTransactionId,
            List<EvaluatedMatchingCandidate> evaluatedCandidates
    ) {
        validateBankTransactionId(bankTransactionId);

        if (evaluatedCandidates == null
                || evaluatedCandidates.stream()
                .anyMatch(candidate -> candidate == null)) {
            throw MatchingErrorCode
                    .INVALID_MATCHING_REQUEST
                    .toException();
        }
    }

    private void validateBankTransactionId(Long bankTransactionId) {
        if (bankTransactionId == null || bankTransactionId <= 0) {
            throw MatchingErrorCode
                    .INVALID_MATCHING_REQUEST
                    .toException();
        }
    }

    private void validateMatchCandidateId(Long matchCandidateId) {
        if (matchCandidateId == null || matchCandidateId <= 0) {
            throw MatchingErrorCode
                    .INVALID_MATCHING_REQUEST
                    .toException();
        }
    }
}
