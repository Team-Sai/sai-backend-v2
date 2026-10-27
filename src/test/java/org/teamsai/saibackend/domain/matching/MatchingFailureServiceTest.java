package org.teamsai.saibackend.domain.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingTransactionResult;
import org.teamsai.saibackend.domain.matching.service.BankTransactionMatchCandidateService;
import org.teamsai.saibackend.domain.matching.service.MatchingFailureService;
import org.teamsai.saibackend.domain.matching.service.MatchingReviewNotificationService;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingProcessStatus;
import org.teamsai.saibackend.domain.payment.entity.PaymentRecord;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.service.LoanPaymentService;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.RecordStatus;
import org.teamsai.saibackend.domain.payment.type.SourceType;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("MatchingFailureService 단위 테스트")
class MatchingFailureServiceTest {

    private static final Long USER_ID = 10L;
    private static final Long LINKED_ACCOUNT_ID = 1L;
    private static final Long BANK_TRANSACTION_ID = 101L;

    @Mock
    private BankTransactionService bankTransactionService;
    @Mock
    private PaymentRecordService paymentRecordService;
    @Mock
    private BankTransactionMatchCandidateService candidateService;
    @Mock
    private SettlementPaymentService settlementPaymentService;
    @Mock
    private LoanPaymentService loanPaymentService;
    @Mock
    private MatchingReviewNotificationService reviewNotificationService;

    @InjectMocks
    private MatchingFailureService matchingFailureService;

    @Test
    @DisplayName("일치하는 자동 납부 기록이 있으면 PENDING 거래를 APPLIED로 복구한다")
    void recoversPendingTransactionWhenConfirmedAutoPaymentIsConsistent() {
        BankTransaction transaction = pendingTransaction();
        PaymentRecord paymentRecord = new PaymentRecord(
                BANK_TRANSACTION_ID,
                PaymentTargetType.SETTLEMENT,
                501L,
                new BigDecimal("10000.00"),
                SourceType.AUTO_MATCH,
                RecordStatus.CONFIRMED,
                LocalDateTime.of(2026, 10, 26, 10, 0)
        );
        given(bankTransactionService.findByIdAndLinkedAccountIdForUpdate(
                BANK_TRANSACTION_ID,
                LINKED_ACCOUNT_ID
        )).willReturn(transaction);
        given(paymentRecordService.findByBankTransactionId(BANK_TRANSACTION_ID))
                .willReturn(Optional.of(paymentRecord));
        given(settlementPaymentService.isPaymentConsistent(501L)).willReturn(true);

        AutoMatchingTransactionResult result = matchingFailureService.recordFailure(
                USER_ID,
                LINKED_ACCOUNT_ID,
                BANK_TRANSACTION_ID,
                new IllegalStateException("processing result failed after payment"),
                false
        );

        assertThat(result.processStatus()).isEqualTo(AutoMatchingProcessStatus.APPLIED);
        verify(bankTransactionService).updateStatus(
                BANK_TRANSACTION_ID,
                BankTransactionProcessingStatus.PENDING,
                BankTransactionProcessingStatus.APPLIED
        );
    }

    @Test
    @DisplayName("납부 기록이 없으면 실패 상태로 저장해 PENDING에 남기지 않는다")
    void recordsFailureWhenNoPaymentRecordExists() {
        given(bankTransactionService.findByIdAndLinkedAccountIdForUpdate(
                BANK_TRANSACTION_ID,
                LINKED_ACCOUNT_ID
        )).willReturn(pendingTransaction());
        given(paymentRecordService.findByBankTransactionId(BANK_TRANSACTION_ID))
                .willReturn(Optional.empty());

        AutoMatchingTransactionResult result = matchingFailureService.recordFailure(
                USER_ID,
                LINKED_ACCOUNT_ID,
                BANK_TRANSACTION_ID,
                new IllegalStateException("matching failed"),
                false
        );

        assertThat(result.processStatus()).isEqualTo(AutoMatchingProcessStatus.FAILED);
        verify(bankTransactionService).updateStatus(
                BANK_TRANSACTION_ID,
                BankTransactionProcessingStatus.PENDING,
                BankTransactionProcessingStatus.FAILED
        );
    }

    private BankTransaction pendingTransaction() {
        BankTransaction transaction = new BankTransaction(
                LINKED_ACCOUNT_ID,
                "external-101",
                new BigDecimal("10000.00"),
                BankTransactionType.DEPOSIT,
                LocalDateTime.of(2026, 10, 26, 9, 0),
                "Hong Gil Dong",
                null,
                LocalDateTime.of(2026, 10, 26, 9, 5)
        );
        ReflectionTestUtils.setField(
                transaction,
                "bankTransactionId",
                BANK_TRANSACTION_ID
        );
        return transaction;
    }
}
