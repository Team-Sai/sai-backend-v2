package org.teamsai.saibackend.domain.matching.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.matching.exception.AutoMatchingPaymentException;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingTransactionResult;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingProcessStatus;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.service.LoanPaymentService;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MatchingFailureService {

    private final BankTransactionService bankTransactionService;
    private final PaymentRecordService paymentRecordService;
    private final BankTransactionMatchCandidateService candidateService;
    private final SettlementPaymentService settlementPaymentService;
    private final LoanPaymentService loanPaymentService;
    private final MatchingReviewNotificationService reviewNotificationService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoMatchingTransactionResult recordFailure(
            Long userId,
            Long linkedAccountId,
            Long bankTransactionId,
            RuntimeException failure,
            boolean isBatch
    ) {
        var transaction =
                bankTransactionService.findByIdAndLinkedAccountIdForUpdate(
                        bankTransactionId,
                        linkedAccountId
                );
        if (transaction.getProcessingStatus()
                != BankTransactionProcessingStatus.PENDING
        ) {
            return null;
        }

        var existingPayment =
                paymentRecordService.findByBankTransactionId(bankTransactionId);

        if (existingPayment.isPresent()
                && isConsistentAutoPayment(existingPayment.get(), transaction)) {
            bankTransactionService.updateStatus(
                    bankTransactionId,
                    BankTransactionProcessingStatus.PENDING,
                    BankTransactionProcessingStatus.APPLIED
            );
            return new AutoMatchingTransactionResult(
                    bankTransactionId,
                    AutoMatchingProcessStatus.APPLIED
            );
        }

        AutoMatchingProcessStatus status = classify(failure);

        if (status == AutoMatchingProcessStatus.DUPLICATE) {
            status = AutoMatchingProcessStatus.FAILED;
        }

        if (status == AutoMatchingProcessStatus.NEEDS_CHECK) {
            var paymentFailure = (AutoMatchingPaymentException) failure;

            candidateService.saveAll(
                    bankTransactionId,
                    List.of(paymentFailure.getCandidate())
            );
        }

        BankTransactionProcessingStatus nextStatus =
                status == AutoMatchingProcessStatus.NEEDS_CHECK
                        ? BankTransactionProcessingStatus.NEEDS_CHECK
                        : BankTransactionProcessingStatus.FAILED;

        bankTransactionService.updateStatus(
                bankTransactionId,
                BankTransactionProcessingStatus.PENDING,
                nextStatus
        );

        AutoMatchingTransactionResult result =
                new AutoMatchingTransactionResult(bankTransactionId, status);
        if (isBatch && status == AutoMatchingProcessStatus.NEEDS_CHECK) {
            reviewNotificationService.notifyIfRequired(
                    userId,
                    linkedAccountId,
                    transaction,
                    result,
                    true
            );
        }

        return result;
    }

    private AutoMatchingProcessStatus classify(RuntimeException failure) {
        if (!(failure instanceof AutoMatchingPaymentException paymentException)) {
            return AutoMatchingProcessStatus.FAILED;
        }

        DomainException cause = paymentException.getPaymentFailure();
        if (cause.getErrorCode() == PaymentErrorCode.DUPLICATE_PAYMENT_RECORD) {
            return AutoMatchingProcessStatus.DUPLICATE;
        }

        if (cause.getHttpStatus().is5xxServerError()) {
            return AutoMatchingProcessStatus.FAILED;
        }

        return AutoMatchingProcessStatus.NEEDS_CHECK;
    }

    private boolean isConsistentAutoPayment(
            PaymentRecord paymentRecord,
            BankTransaction bankTransaction
    ) {
        if (paymentRecord.getRecordStatus() != RecordStatus.CONFIRMED
                || paymentRecord.getSourceType() != SourceType.AUTO_MATCH) {
            return false;
        }

        if (!paymentRecord.getBankTransactionId()
                .equals(bankTransaction.getBankTransactionId())) {
            return false;
        }

        if (paymentRecord.getAmount().compareTo(bankTransaction.getAmount()) != 0) {
            return false;
        }

        return switch (paymentRecord.getPaymentTargetType()) {
            case SETTLEMENT -> settlementPaymentService
                    .isPaymentConsistent(paymentRecord.getTargetId());
            case LOAN -> loanPaymentService
                    .isPaymentConsistent(paymentRecord.getTargetId());
        };
    }
}
