package org.teamsai.saibackend.domain.payment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecordEntity;
import org.teamsai.saibackend.domain.payment.exception.PaymentErrorCode;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PaymentRecordService {

    private final PaymentRecordRepository paymentRecordRepository;

    @Transactional(readOnly = true)
    public BigDecimal sumConfirmedAmountByTarget(
            PaymentTargetType paymentTargetType,
            Long targetId
    ) {
        validateTarget(paymentTargetType, targetId);

        return paymentRecordRepository.sumConfirmedAmountByTarget(
                paymentTargetType,
                targetId,
                RecordStatus.CONFIRMED
        );
    }

    @Transactional
    public Long createConfirmedRecord(
            Long bankTransactionId,
            PaymentTargetType paymentTargetType,
            Long targetId,
            BigDecimal amount,
            SourceType sourceType
    ){
        validateBankTransactionId(bankTransactionId);
        validateTarget(paymentTargetType, targetId);
        validateAmount(amount);
        validateSourceType(sourceType);

        validateNotDuplicateBankTransaction(bankTransactionId);

        PaymentRecordEntity paymentRecord =
                new PaymentRecordEntity(
                        bankTransactionId,
                        paymentTargetType,
                        targetId,
                        amount,
                        sourceType,
                        RecordStatus.CONFIRMED,
                        LocalDateTime.now()
                );

        try {
            PaymentRecordEntity savedPaymentRecord =
                    paymentRecordRepository.saveAndFlush(paymentRecord);

            return savedPaymentRecord.getPaymentRecordId();

        } catch (DataIntegrityViolationException exception) {
            throw PaymentErrorCode.DUPLICATE_PAYMENT_RECORD.toException();
        }
    }

    @Transactional(readOnly = true)
    public List<PaymentRecordEntity> findConfirmedRecordsByTargetIds(
            PaymentTargetType paymentTargetType,
            List<Long> targetIds
    ) {
        if (targetIds == null || targetIds.isEmpty()) {
            return List.of();
        }

        return paymentRecordRepository.findConfirmedByTargetIds(
                paymentTargetType,
                targetIds,
                RecordStatus.CONFIRMED
        );    }

    @Transactional(readOnly = true)
    public boolean existsByBankTransactionId(Long bankTransactionId) {

        validateBankTransactionId(bankTransactionId);

        return paymentRecordRepository.existsByBankTransactionId(bankTransactionId);
    }

    private void validateNotDuplicateBankTransaction(Long bankTransactionId) {
        if (paymentRecordRepository.existsByBankTransactionId(bankTransactionId)) {
            throw PaymentErrorCode.DUPLICATE_PAYMENT_RECORD.toException();
        }
    }

    private void validateTarget(
            PaymentTargetType paymentTargetType,
            Long targetId
    ) {
        if (paymentTargetType == null || targetId == null) {
            throw PaymentErrorCode.INVALID_PAYMENT_TARGET.toException();
        }
    }

    private void validateBankTransactionId(Long bankTransactionId) {
        if (bankTransactionId == null) {
            throw PaymentErrorCode.INVALID_BANK_TRANSACTION_ID.toException();
        }
    }

    private void validateAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw PaymentErrorCode.INVALID_PAYMENT_AMOUNT.toException();
        }
    }

    private void validateSourceType(SourceType sourceType) {
        if (sourceType == null) {
            throw PaymentErrorCode.INVALID_PAYMENT_SOURCE_TYPE.toException();
        }
    }
}
