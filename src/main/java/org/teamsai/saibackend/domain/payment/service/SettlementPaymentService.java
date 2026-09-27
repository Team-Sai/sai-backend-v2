package org.teamsai.saibackend.domain.payment.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementPaymentService {

    private static final int WRITE_OFF_CHUNK_SIZE = 500;

    private final PaymentObligationRepository paymentObligationRepository;
    private final PaymentRecordService paymentRecordService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyAutoMatchedPayment(
            Long paymentObligationId,
            Long bankTransactionId,
            BigDecimal amount
    ) {
        applyPayment(
                paymentObligationId,
                bankTransactionId,
                amount,
                SourceType.AUTO_MATCH,
                false
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void applyManuallyMatchedPayment(
            Long paymentObligationId,
            Long bankTransactionId,
            BigDecimal amount
    ) {
        applyPayment(
                paymentObligationId,
                bankTransactionId,
                amount,
                SourceType.MANUAL,
                true
        );
    }

    private void applyPayment(
            Long paymentObligationId,
            Long bankTransactionId,
            BigDecimal amount,
            SourceType sourceType,
            boolean limitToRemainingAmount
    ) {
        validatePaymentAmount(amount);
        validateBankTransactionId(bankTransactionId);
        validateNotDuplicatePaymentRecord(bankTransactionId);

        PaymentObligationEntity obligation =
                paymentObligationRepository.findByIdForUpdate(paymentObligationId)
                        .orElseThrow(PaymentErrorCode.PAYMENT_OBLIGATION_NOT_FOUND::toException);

        validateActiveObligation(obligation);

        BigDecimal paidAmount = paymentRecordService
                .sumConfirmedAmountByTarget(PaymentTargetType.SETTLEMENT,
                        paymentObligationId);

        BigDecimal remainingAmount =
                obligation.getExpectedAmount().subtract(paidAmount);

        if (!limitToRemainingAmount
                && amount.compareTo(remainingAmount) > 0) {
            throw PaymentErrorCode.PAYMENT_AMOUNT_EXCEEDS_REMAINING_AMOUNT.toException();
        }

        BigDecimal paymentAmount = limitToRemainingAmount
                ? amount.min(remainingAmount)
                : amount;

        validatePaymentAmount(paymentAmount);

        paymentRecordService.createConfirmedRecord(
                bankTransactionId,
                PaymentTargetType.SETTLEMENT,
                paymentObligationId,
                paymentAmount,
                sourceType
        );

        BigDecimal newPaidAmount = paidAmount.add(paymentAmount);
        PaymentStatus newPaymentStatus = calculatePaymentStatus(
                obligation.getExpectedAmount(),
                newPaidAmount
        );

        obligation.changePaymentStatus(newPaymentStatus);

        if (newPaymentStatus == PaymentStatus.PAID){
            obligation.clearOverdue();
        }
    }

    public Long createObligation(Long participantId, BigDecimal expectedAmount){
        validateObligationCreation(participantId,expectedAmount);

        PaymentObligationEntity paymentObligation =
               new PaymentObligationEntity(
                       participantId,
                       expectedAmount
               );

        PaymentObligationEntity savedPaymentObligation =
                paymentObligationRepository.saveAndFlush(
                        paymentObligation
                );

        return savedPaymentObligation.getPaymentObligationId();
    }

    @Transactional
    public int markOverdueByParticipantIds(List<Long> participantIds, LocalDateTime overdueSince) {
        if (participantIds == null || participantIds.isEmpty()) {
            return 0;
        }

        var obligations = paymentObligationRepository.findUnpaidByParticipantIds(
                participantIds,
                List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID),
                ObligationStatus.ACTIVE
        );
        obligations.forEach(obligation -> obligation.markOverdue(overdueSince));
        return obligations.size();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int writeOffOneBatch(List<Long> obligationIds) {
        int total = 0;
        for (int i = 0; i < obligationIds.size(); i += WRITE_OFF_CHUNK_SIZE) {
            List<Long> chunk = obligationIds.subList(i, Math.min(i + WRITE_OFF_CHUNK_SIZE, obligationIds.size()));
            List<PaymentObligationEntity> obligations = paymentObligationRepository.findWriteOffTargetsForUpdate(
                    chunk,
                    ObligationStatus.ACTIVE,
                    List.of(PaymentStatus.UNPAID, PaymentStatus.PARTIALLY_PAID)
            );
            obligations.forEach(PaymentObligationEntity::writeOff);
            total += obligations.size();
        }
        return total;
    }

    private void validateActiveObligation(PaymentObligationEntity obligation) {
        if (obligation.getObligationStatus() != ObligationStatus.ACTIVE
                || obligation.getPaymentStatus() == PaymentStatus.PAID) {
            throw PaymentErrorCode.PAYMENT_OBLIGATION_NOT_ACTIVE.toException();
        }
    }

    private void validatePaymentAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw PaymentErrorCode.INVALID_PAYMENT_AMOUNT.toException();
        }
    }

    private void validateBankTransactionId(
            Long bankTransactionId
    ) {
        if (bankTransactionId == null) {
            throw PaymentErrorCode.INVALID_BANK_TRANSACTION_ID.toException();
        }
    }

    private void validateNotDuplicatePaymentRecord(
            Long bankTransactionId
    ) {
        if (paymentRecordService.existsByBankTransactionId(
                bankTransactionId
        )) {
            throw PaymentErrorCode
                    .DUPLICATE_PAYMENT_RECORD
                    .toException();
        }
    }

    private PaymentStatus calculatePaymentStatus(
            BigDecimal expectedAmount,
            BigDecimal paidAmount
    ) {
        if (paidAmount.compareTo(BigDecimal.ZERO) == 0) {
            return PaymentStatus.UNPAID;
        }

        if (paidAmount.compareTo(expectedAmount) < 0) {
            return PaymentStatus.PARTIALLY_PAID;
        }

        return PaymentStatus.PAID;
    }

    private void validateObligationCreation(
            Long participantId,
            BigDecimal expectedAmount
    ) {
        if (participantId == null || participantId <= 0) {
            throw PaymentErrorCode
                    .INVALID_PAYMENT_OBLIGATION_REQUEST
                    .toException();
        }
        if (expectedAmount == null
                || expectedAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw PaymentErrorCode
                    .INVALID_PAYMENT_OBLIGATION_REQUEST
                    .toException();
        }

    }
}
