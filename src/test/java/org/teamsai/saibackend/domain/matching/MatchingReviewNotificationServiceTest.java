package org.teamsai.saibackend.domain.matching;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.matching.entity.BankTransactionMatchCandidate;
import org.teamsai.saibackend.domain.matching.model.AutoMatchingTransactionResult;
import org.teamsai.saibackend.domain.matching.service.BankTransactionMatchCandidateService;
import org.teamsai.saibackend.domain.matching.service.MatchingReviewNotificationService;
import org.teamsai.saibackend.domain.matching.type.AutoMatchingProcessStatus;
import org.teamsai.saibackend.domain.matching.type.MatchingTargetType;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentStatusChecker;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MatchingReviewNotificationServiceTest {
    @Mock PaymentObligationQueryService obligations;
    @Mock BankTransactionMatchCandidateService candidates;
    @Mock NotificationService notifications;
    @Mock SettlementPaymentStatusChecker checker;
    @InjectMocks MatchingReviewNotificationService service;

    @ParameterizedTest(name = "{0}원을 알림에서 {1}원으로 표시한다")
    @CsvSource(value = {
            "1000.00|1,000",
            "24000.00|24,000",
            "1000.49|1,000",
            "1000.50|1,001",
            "999999.99|1,000,000"
    }, delimiter = '|')
    void formatsAmountInNotificationWithExplicitHalfUpRounding(String amount, String expected) {
        BankTransaction transaction = mock(BankTransaction.class);
        when(transaction.getBankTransactionId()).thenReturn(1L);
        when(transaction.getCounterpartyName()).thenReturn("한민엽");
        when(transaction.getAmount()).thenReturn(new BigDecimal(amount));
        when(candidates.findAllByBankTransactionId(1L)).thenReturn(List.of(
                BankTransactionMatchCandidate.builder().targetType(MatchingTargetType.SETTLEMENT).build(),
                BankTransactionMatchCandidate.builder().targetType(MatchingTargetType.LOAN).build()));

        service.notifyIfRequired(2L, 3L, transaction,
                new AutoMatchingTransactionResult(1L, AutoMatchingProcessStatus.NEEDS_CHECK), true);

        verify(notifications).createIfAbsent(2L, NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                "입금 거래 확인이 필요합니다.",
                "한민엽님의 " + expected + "원 입금에 정산과 차용증 후보가 모두 발견되었습니다.", 1L, 3L);
    }
}
