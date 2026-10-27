package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.exception.MatchingErrorCode;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingExecutionResult;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingTransactionResult;
import org.teamsai.saibackend.domain.matching.model.MatchingCandidate;
import org.teamsai.saibackend.domain.matching.model.MatchingTransaction;
import org.teamsai.saibackend.domain.matching.repository.MatchingCandidateRepository;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingProcessStatus;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingTransactionType;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.util.List;

@Service
@RequiredArgsConstructor
public class BankMatchingTransactionService {

    private final MatchingCandidateRepository matchingCandidateRepository;
    private final AutoMatchingService autoMatchingService;
    private final BankTransactionService bankTransactionService;
    private final MatchingReviewNotificationService reviewNotificationService;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public AutoMatchingTransactionResult process(
            Long userId,
            Long linkedAccountId,
            BankTransaction bankTransaction,
            boolean isBatch
    ) {
        return process(userId, linkedAccountId, bankTransaction, null, null, isBatch);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public AutoMatchingTransactionResult process(
            Long userId,
            Long linkedAccountId,
            BankTransaction bankTransaction,
            MatchingTargetType targetType,
            Long aggregateId,
            boolean isBatch
    ) {
        BankTransaction lockedTransaction =
                bankTransactionService
                        .findByIdAndLinkedAccountIdForUpdate(
                                bankTransaction.getBankTransactionId(),
                                linkedAccountId
                        );
        if (lockedTransaction.getProcessingStatus()
                != BankTransactionProcessingStatus.PENDING) {
            return new AutoMatchingTransactionResult(
                    lockedTransaction.getBankTransactionId(),
                    AutoMatchingProcessStatus.DUPLICATE
            );
        }
        AutoMatchingTransactionResult result =
                processMatching(
                        linkedAccountId,
                        lockedTransaction,
                        targetType,
                        aggregateId
                );
        if (result == null) {
            return null;
        }
        if (isBatch) {
            reviewNotificationService.notifyIfRequired(
                    userId,
                    linkedAccountId,
                    lockedTransaction,
                    result,
                    isBatch
            );
        }
        bankTransactionService.updateStatus(
                lockedTransaction.getBankTransactionId(),
                BankTransactionProcessingStatus.PENDING,
                toBankTransactionProcessingStatus(result.processStatus())
        );
        return result;
    }
    private AutoMatchingTransactionResult processMatching(
            Long linkedAccountId,
            BankTransaction bankTransaction,
            MatchingTargetType targetType,
            Long aggregateId
    ) {
        if (!hasMatchableCounterpartyName(bankTransaction)) {
            if (targetType != null) {
                return null;
            }
            return new AutoMatchingTransactionResult(
                    bankTransaction.getBankTransactionId(),
                    AutoMatchingProcessStatus.UNMATCHED
            );
        }
        MatchingTransaction matchingTransaction =
                toMatchingTransaction(bankTransaction);
        List<MatchingCandidate> candidates = findCandidates(
                linkedAccountId,
                matchingTransaction,
                targetType,
                aggregateId
        );
        if (targetType != null && candidates.isEmpty()) {
            return null;
        }
        AutoMatchingExecutionResult matchingResult =
                autoMatchingService.execute(
                        List.of(matchingTransaction),
                        candidates
                );
        if (matchingResult.transactionResults().size() != 1) {
            throw MatchingErrorCode.INVALID_MATCHING_REQUEST.toException();
        }
        return matchingResult.transactionResults().get(0);
    }
    private List<MatchingCandidate> findCandidates(
            Long linkedAccountId,
            MatchingTransaction transaction,
            MatchingTargetType targetType,
            Long aggregateId
    ) {
        if (targetType == null) {
            return matchingCandidateRepository.findMatchCandidatesByLinkedAccountId(
                    linkedAccountId,
                    transaction.transactionAt()
            );
        }
        return matchingCandidateRepository.findMatchCandidatesByLinkedAccountIdAndTarget(
                linkedAccountId,
                transaction.transactionAt(),
                targetType,
                aggregateId
        );
    }
    private boolean hasMatchableCounterpartyName(
            BankTransaction bankTransaction
    ) {
        String counterpartyName = bankTransaction.getCounterpartyName();
        return counterpartyName != null && !counterpartyName.isBlank();
    }
    private MatchingTransaction toMatchingTransaction(
            BankTransaction bankTransaction
    ) {
        return new MatchingTransaction(
                bankTransaction.getBankTransactionId(),
                toMatchingTransactionType(bankTransaction.getTransactionType()),
                bankTransaction.getAmount(),
                bankTransaction.getCounterpartyName(),
                bankTransaction.getTransactionAt()
        );
    }
    private AutoMatchingTransactionType toMatchingTransactionType(
            BankTransactionType transactionType
    ) {
        return switch (transactionType) {
            case DEPOSIT -> AutoMatchingTransactionType.DEPOSIT;
            case WITHDRAWAL -> AutoMatchingTransactionType.WITHDRAWAL;
        };
    }
    private BankTransactionProcessingStatus toBankTransactionProcessingStatus(
            AutoMatchingProcessStatus processStatus
    ) {
        return switch (processStatus) {
            case APPLIED, DUPLICATE ->
                    BankTransactionProcessingStatus.APPLIED;
            case NEEDS_CHECK ->
                    BankTransactionProcessingStatus.NEEDS_CHECK;
            case UNMATCHED ->
                    BankTransactionProcessingStatus.UNMATCHED;
            case FAILED ->
                    BankTransactionProcessingStatus.FAILED;
        };
    }
}
