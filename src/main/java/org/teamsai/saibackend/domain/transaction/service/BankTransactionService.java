package org.teamsai.saibackend.domain.transaction.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionQueryRepository;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankTransactionService {

    private final BankTransactionRepository bankTransactionRepository;
    private final BankTransactionQueryRepository bankTransactionQueryRepository;
    private final LinkedBankAccountService linkedBankAccountService;
    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Long saveIfNotExists(BankTransaction bankTransaction) {
        validateNewBankTransaction(bankTransaction);

        bankTransactionRepository.insertIfAbsent(
                bankTransaction.getLinkedAccountId(),
                bankTransaction.getExternalTransactionId(),
                bankTransaction.getAmount(),
                bankTransaction.getTransactionType().name(),
                bankTransaction.getTransactionAt(),
                bankTransaction.getCounterpartyName(),
                bankTransaction.getMemo(),
                bankTransaction.getSyncedAt()
        );

        return bankTransactionRepository.findIdByExternalKeyForUpdate(
                        bankTransaction.getLinkedAccountId(),
                        bankTransaction.getExternalTransactionId()
                )
                .orElseThrow(
                        BankTransactionErrorCode.BANK_TRANSACTION_CREATE_FAILED::toException
                );
    }

    public Optional<BankTransaction> findById(Long bankTransactionId) {
        return bankTransactionRepository.findById(bankTransactionId);
    }

    public List<BankTransaction> findPendingDeposits() {
        return bankTransactionRepository.findPendingDeposits(
                BankTransactionProcessingStatus.PENDING,
                BankTransactionType.DEPOSIT
        );
    }

    public List<BankTransaction> findPendingDepositsByLinkedAccountId(
            Long linkedAccountId
    ) {
        return bankTransactionRepository.findPendingDepositsByLinkedAccountId(
                linkedAccountId,
                BankTransactionProcessingStatus.PENDING,
                BankTransactionType.DEPOSIT
        );
    }

    @Transactional
    public BankTransaction findByIdAndLinkedAccountIdForUpdate(
            Long bankTransactionId,
            Long linkedAccountId
    ) {
        BankTransaction bankTransaction = bankTransactionRepository
                .findLockedByBankTransactionIdAndLinkedAccountId(
                        bankTransactionId,
                        linkedAccountId
                )
                .orElseThrow(
                        BankTransactionErrorCode
                                .BANK_TRANSACTION_NOT_FOUND::toException
                );
        entityManager.refresh(bankTransaction, LockModeType.PESSIMISTIC_WRITE);
        return bankTransaction;
    }

    @Transactional
    public void updateStatus(
            Long bankTransactionId,
            BankTransactionProcessingStatus currentStatus,
            BankTransactionProcessingStatus nextStatus
    ) {
        validateStatusTransition(currentStatus, nextStatus);

        BankTransaction bankTransaction =
                bankTransactionRepository.findLockedByBankTransactionId(
                                bankTransactionId
                        )
                        .orElseThrow(
                                BankTransactionErrorCode
                                        .BANK_TRANSACTION_STATUS_UPDATE_FAILED
                                        ::toException
                        );

        entityManager.refresh(bankTransaction, LockModeType.PESSIMISTIC_WRITE);

        if (bankTransaction.getProcessingStatus() != currentStatus) {
            throw BankTransactionErrorCode
                    .BANK_TRANSACTION_STATUS_UPDATE_FAILED
                    .toException();
        }

        bankTransaction.changeProcessingStatus(nextStatus);

        bankTransactionRepository.flush();
    }

    public List<BankTransaction> findRetryCandidates(
            Long linkedAccountId
    ) {
        return bankTransactionQueryRepository.findRetryCandidates(linkedAccountId);
    }

    @Transactional
    public int resetToPendingForRetry(
            Long bankTransactionId,
            BankTransactionProcessingStatus currentStatus
    ) {
        Optional<BankTransaction> transactionOptional =
                bankTransactionRepository.findLockedByBankTransactionId(
                        bankTransactionId
                );

        if (transactionOptional.isEmpty()) {
            return 0;
        }

        BankTransaction bankTransaction = transactionOptional.get();

        entityManager.refresh(bankTransaction, LockModeType.PESSIMISTIC_WRITE);

        if (bankTransaction.getProcessingStatus() != currentStatus) {
            return 0;
        }

        bankTransaction.resetToPendingForRetry();
        bankTransactionRepository.flush();

        return 1;
    }

    @Transactional
    public BankTransactionDetailResponse getOwnedTransactionDetailForUpdate(
            Long userId,
            Long linkedAccountId,
            Long bankTransactionId
    ) {
        LinkedBankAccount account = linkedBankAccountService.getLinkedAccount(linkedAccountId);

        if (!account.getUserId().equals(userId)) {
            throw AccountErrorCode.ACCOUNT_ACCESS_DENIED.toException();
        }

        BankTransaction transaction =
                findByIdAndLinkedAccountIdForUpdate(bankTransactionId, linkedAccountId);

        return BankTransactionDetailResponse.from(transaction);
    }
    private void validateNewBankTransaction(
            BankTransaction bankTransaction
    ) {
        if (bankTransaction == null
                || hasInvalidRequiredField(bankTransaction)
                || hasInvalidAmount(bankTransaction)
                || hasInvalidInitialStatus(bankTransaction)) {
            throw BankTransactionErrorCode
                    .INVALID_BANK_TRANSACTION
                    .toException();
        }
    }

    private boolean hasInvalidRequiredField(
            BankTransaction bankTransaction
    ) {
        return bankTransaction.getLinkedAccountId() == null
                || isBlank(bankTransaction.getExternalTransactionId())
                || bankTransaction.getTransactionType() == null
                || bankTransaction.getTransactionAt() == null
                || bankTransaction.getSyncedAt() == null;
    }

    private boolean hasInvalidAmount(
            BankTransaction bankTransaction
    ) {
        return bankTransaction.getAmount() == null
                || bankTransaction.getAmount().signum() <= 0;
    }

    private boolean hasInvalidInitialStatus(
            BankTransaction bankTransaction
    ) {
        return bankTransaction.getProcessingStatus() != null
                && bankTransaction.getProcessingStatus()
                != BankTransactionProcessingStatus.PENDING;
    }

    private void validateStatusTransition(
            BankTransactionProcessingStatus currentStatus,
            BankTransactionProcessingStatus nextStatus
    ) {
        if (!isValidStatusTransition(currentStatus, nextStatus)) {
            throw BankTransactionErrorCode
                    .INVALID_BANK_TRANSACTION_STATUS_TRANSITION
                    .toException();
        }
    }

    private boolean isValidStatusTransition(
            BankTransactionProcessingStatus currentStatus,
            BankTransactionProcessingStatus nextStatus
    ) {
        if (currentStatus == BankTransactionProcessingStatus.PENDING) {
            return isTerminalStatus(nextStatus);
        }

        if (currentStatus == BankTransactionProcessingStatus.NEEDS_CHECK) {
            return nextStatus == BankTransactionProcessingStatus.APPLIED
                    || nextStatus == BankTransactionProcessingStatus.UNMATCHED
                    || nextStatus == BankTransactionProcessingStatus.FAILED
                    || nextStatus == BankTransactionProcessingStatus.PENDING;
        }

        if (currentStatus == BankTransactionProcessingStatus.UNMATCHED
                || currentStatus == BankTransactionProcessingStatus.FAILED) {
            return nextStatus == BankTransactionProcessingStatus.PENDING;
        }

        return false;
    }

    private boolean isTerminalStatus(
            BankTransactionProcessingStatus processingStatus
    ) {
        return processingStatus == BankTransactionProcessingStatus.APPLIED
                || processingStatus == BankTransactionProcessingStatus.UNMATCHED
                || processingStatus == BankTransactionProcessingStatus.NEEDS_CHECK
                || processingStatus == BankTransactionProcessingStatus.FAILED;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
