package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.contract.service.EarlyRepaymentService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BankMatchingTransactionService {

    private final MatchingCandidateRepository matchingCandidateRepository;
    private final AutoMatchingService autoMatchingService;
    private final BankTransactionService bankTransactionService;
    private final MatchingReviewNotificationService reviewNotificationService;
    private final EarlyRepaymentService earlyRepaymentService;

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public AutoMatchingTransactionResult process(
            Long userId,
            Long linkedAccountId,
            Long bankTransactionId,
            boolean isBatch
    ) {
        return process(userId, linkedAccountId, bankTransactionId, null, null, isBatch);
    }

    @Transactional(
            propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED
    )
    public AutoMatchingTransactionResult process(
            Long userId,
            Long linkedAccountId,
            Long bankTransactionId,
            MatchingTargetType targetType,
            Long aggregateId,
            boolean isBatch
    ) {
        BankTransaction lockedTransaction =
                bankTransactionService
                        .findByIdAndLinkedAccountIdForUpdate(
                                bankTransactionId,
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
        bankTransactionService.updateStatusForLockedTransaction(
                lockedTransaction,
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
        List<MatchingCandidate> candidates = excludeLoanPayoffCandidates(
                bankTransaction,
                findCandidates(linkedAccountId, matchingTransaction, targetType, aggregateId)
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
        AutoMatchingTransactionResult result = matchingResult.transactionResults().get(0);

        // 계약 단위 동기화에서 안 맞은 입금은 다른 계약 것일 수 있어 PENDING으로 둔다.
        if (targetType != null
                && result.processStatus() == AutoMatchingProcessStatus.UNMATCHED) {
            return null;
        }

        return result;    }

    /**
     * 입금일 기준 완납 금액 이상 들어온 차용증 입금은 회차 일부 납부가 아니라 완납 의도로 본다.
     * 그 차용증 후보를 빼서 입금 카드에서 완납 처리하게 한다.
     * 회차 금액과 딱 같은 입금은 정상 납부라 후보로 남긴다.
     * 메모에 차용증 번호가 있으면 정산 후보도 제외한다.
     */
    private List<MatchingCandidate> excludeLoanPayoffCandidates(
            BankTransaction bankTransaction,
            List<MatchingCandidate> candidates
    ) {
        BigDecimal amount = bankTransaction.getAmount();
        LocalDate paidOn = bankTransaction.getTransactionAt().toLocalDate();

        boolean memoNamesContract = EarlyRepaymentService.hasContractNumber(bankTransaction.getMemo());

        return candidates.stream()
                .filter(candidate -> candidate.targetType() == MatchingTargetType.LOAN
                        ? earlyRepaymentService.memoAllowsSchedule(candidate.targetId(), bankTransaction.getMemo())
                          && (candidate.remainingAmount().compareTo(amount) == 0
                              || !isPayoffOrMore(candidate.targetId(), amount, paidOn))
                        : !memoNamesContract) // 차용증 번호가 적힌 입금은 정산에 반영하지 않는다
                .toList();
    }

    private boolean isPayoffOrMore(Long scheduleId, BigDecimal amount, LocalDate paidOn) {
        BigDecimal payoff = earlyRepaymentService.payoffAmountOfSchedule(scheduleId, paidOn);
        return payoff != null && amount.compareTo(payoff) >= 0;
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
