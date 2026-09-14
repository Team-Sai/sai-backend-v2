package org.teamsai.saibackend.domain.transaction.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankTransactionService {

    private final BankTransactionRepository bankTransactionRepository;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public Long saveIfNotExists(BankTransactionEntity bankTransaction) {
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

    public Optional<BankTransactionEntity> findById(Long bankTransactionId) {
        return bankTransactionRepository.findById(bankTransactionId);
    }

    public List<BankTransactionEntity> findPendingDeposits() {
        return bankTransactionRepository.findPendingDeposits(
                BankTransactionProcessingStatus.PENDING,
                BankTransactionType.DEPOSIT
        );
    }

    public List<BankTransactionEntity> findPendingDepositsByLinkedAccountId(
            Long linkedAccountId
    ) {
        return bankTransactionRepository.findPendingDepositsByLinkedAccountId(
                linkedAccountId,
                BankTransactionProcessingStatus.PENDING,
                BankTransactionType.DEPOSIT
        );
    }

    @Transactional
    public BankTransactionEntity findByIdAndLinkedAccountIdForUpdate(
            Long bankTransactionId,
            Long linkedAccountId
    ) {
        BankTransactionEntity bankTransaction = bankTransactionRepository
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

        BankTransactionEntity bankTransaction =
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

    public List<BankTransactionEntity> findRetryCandidates(
            Long linkedAccountId
    ) {
        return bankTransactionRepository.findRetryCandidates(linkedAccountId);
    }

    @Transactional
    public int resetToPendingForRetry(
            Long bankTransactionId,
            BankTransactionProcessingStatus currentStatus
    ) {
        Optional<BankTransactionEntity> transactionOptional =
                bankTransactionRepository.findLockedByBankTransactionId(
                        bankTransactionId
                );

        if (transactionOptional.isEmpty()) {
            return 0;
        }

        BankTransactionEntity bankTransaction = transactionOptional.get();

        entityManager.refresh(bankTransaction, LockModeType.PESSIMISTIC_WRITE);

        if (bankTransaction.getProcessingStatus() != currentStatus) {
            return 0;
        }

        bankTransaction.resetToPendingForRetry();
        bankTransactionRepository.flush();

        return 1;
    }

    private void validateNewBankTransaction(
            BankTransactionEntity bankTransaction
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
            BankTransactionEntity bankTransaction
    ) {
        return bankTransaction.getLinkedAccountId() == null
                || isBlank(bankTransaction.getExternalTransactionId())
                || bankTransaction.getTransactionType() == null
                || bankTransaction.getTransactionAt() == null
                || bankTransaction.getSyncedAt() == null;
    }

    private boolean hasInvalidAmount(
            BankTransactionEntity bankTransaction
    ) {
        return bankTransaction.getAmount() == null
                || bankTransaction.getAmount().signum() <= 0;
    }

    private boolean hasInvalidInitialStatus(
            BankTransactionEntity bankTransaction
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
