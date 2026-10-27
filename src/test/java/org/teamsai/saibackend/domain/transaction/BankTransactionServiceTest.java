package org.teamsai.saibackend.domain.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("BankTransactionService 단위 테스트")
class BankTransactionServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private LinkedBankAccountService linkedBankAccountService;

    @InjectMocks
    private BankTransactionService bankTransactionService;

    @Test
    @DisplayName("본인 계좌의 거래를 잠금 조회해 상세 응답을 반환한다")
    void getsOwnedTransactionDetailForUpdate() {
        LinkedBankAccount account = LinkedBankAccount.builder().userId(7L).build();
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.NEEDS_CHECK);
        given(linkedBankAccountService.getLinkedAccount(1L)).willReturn(account);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(101L, 1L))
                .willReturn(Optional.of(transaction));

        var result = bankTransactionService.getOwnedTransactionDetailForUpdate(7L, 1L, 101L);

        assertThat(result.linkedAccountId()).isEqualTo(1L);
        assertThat(result.amount()).isEqualByComparingTo("1");
        assertThat(result.processingStatus()).isEqualTo(BankTransactionProcessingStatus.NEEDS_CHECK);
    }

    @Test
    @DisplayName("타인 계좌의 거래는 잠금 조회하지 않는다")
    void rejectsOtherUsersTransactionForUpdate() {
        LinkedBankAccount account = LinkedBankAccount.builder().userId(8L).build();
        given(linkedBankAccountService.getLinkedAccount(1L)).willReturn(account);

        assertThatThrownBy(() -> bankTransactionService.getOwnedTransactionDetailForUpdate(7L, 1L, 101L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AccountErrorCode.ACCOUNT_ACCESS_DENIED));
        verifyNoInteractions(bankTransactionRepository);
    }

    @Test
    @DisplayName("연결 계좌가 없으면 거래를 조회하지 않는다")
    void rejectsMissingAccountForUpdate() {
        given(linkedBankAccountService.getLinkedAccount(1L)).willThrow(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND.toException());

        assertThatThrownBy(() -> bankTransactionService.getOwnedTransactionDetailForUpdate(7L, 1L, 101L))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND));
        verifyNoInteractions(bankTransactionRepository);
    }

    @Test
    @DisplayName("연결계좌와 거래 ID로 은행 거래를 잠금 조회한다")
    void findsTransactionByIdAndLinkedAccountIdForUpdate() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.PENDING);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(
                101L,
                1L
        )).willReturn(Optional.of(transaction));

        BankTransaction result = bankTransactionService
                .findByIdAndLinkedAccountIdForUpdate(101L, 1L);

        assertThat(result).isSameAs(transaction);
    }

    @Test
    @DisplayName("잠금 조회할 은행 거래가 없으면 예외가 발생한다")
    void throwsWhenTransactionForUpdateDoesNotExist() {
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(
                101L,
                1L
        )).willReturn(Optional.empty());

        assertThatThrownBy(() -> bankTransactionService
                .findByIdAndLinkedAccountIdForUpdate(101L, 1L)
        ).isInstanceOfSatisfying(
                DomainException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(
                                BankTransactionErrorCode
                                        .BANK_TRANSACTION_NOT_FOUND
                        )
        );
    }

    @Test
    @DisplayName("확인 필요 거래를 반영 완료 상태로 변경할 수 있다")
    void updatesNeedsCheckTransactionToApplied() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.NEEDS_CHECK);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));

        bankTransactionService.updateStatus(
                1L,
                BankTransactionProcessingStatus.NEEDS_CHECK,
                BankTransactionProcessingStatus.APPLIED
        );

        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
        verify(bankTransactionRepository).flush();
    }

    @Test
    @DisplayName("확인 필요 거래를 미매칭 상태로 변경할 수 있다")
    void updatesNeedsCheckTransactionToUnmatched() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.NEEDS_CHECK);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));

        bankTransactionService.updateStatus(
                1L,
                BankTransactionProcessingStatus.NEEDS_CHECK,
                BankTransactionProcessingStatus.UNMATCHED
        );

        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.UNMATCHED);
        verify(bankTransactionRepository).flush();
    }
    @Test
    @DisplayName("현재 상태가 달라진 거래는 덮어쓰지 않는다")
    void rejectsStatusUpdateWhenCurrentStatusDoesNotMatch() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.APPLIED);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));

        assertThatThrownBy(() -> bankTransactionService.updateStatus(
                1L,
                BankTransactionProcessingStatus.PENDING,
                BankTransactionProcessingStatus.FAILED
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BankTransactionErrorCode.BANK_TRANSACTION_STATUS_UPDATE_FAILED));

        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
        verify(bankTransactionRepository, org.mockito.Mockito.never()).flush();
    }

    @Test
    @DisplayName("매칭용 잠금 조회 결과를 반환한다")
    void returnsLockedTransactionForMatching() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.APPLIED);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(101L, 1L))
                .willReturn(Optional.of(transaction));

        BankTransaction result =
                bankTransactionService.findByIdAndLinkedAccountIdForUpdate(101L, 1L);

        assertThat(result.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("잠금 조회에서 현재 상태가 다르면 상태 변경을 거부한다")
    void rejectsStatusUpdateWhenLockedStateDoesNotMatch() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.APPLIED);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));

        assertThatThrownBy(() -> bankTransactionService.updateStatus(
                1L, BankTransactionProcessingStatus.PENDING, BankTransactionProcessingStatus.FAILED
        ))
                .isInstanceOfSatisfying(DomainException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(BankTransactionErrorCode.BANK_TRANSACTION_STATUS_UPDATE_FAILED));

        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
        verify(bankTransactionRepository, never()).flush();
    }

    @Test
    @DisplayName("재시도 상태가 조회 시점과 다르면 초기화를 건너뛴다")
    void skipsRetryWhenExpectedStatusDoesNotMatch() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.UNMATCHED);
        ReflectionTestUtils.setField(transaction, "retryCount", 3);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(1L, 1L))
                .willReturn(Optional.of(transaction));

        int result = bankTransactionService.resetToPendingForRetry(
                1L, 1L, BankTransactionProcessingStatus.UNMATCHED, 2
        );

        assertThat(result).isZero();
        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.UNMATCHED);
        assertThat(transaction.getRetryCount()).isEqualTo(3);
        verify(bankTransactionRepository, never()).flush();
    }

    @Test
    @DisplayName("예상한 재시도 횟수와 일치하면 횟수를 증가시키고 PENDING으로 초기화한다")
    void incrementsRetryCountWhenExpectedCountMatches() {
        BankTransaction transaction = transaction(BankTransactionProcessingStatus.UNMATCHED);
        ReflectionTestUtils.setField(transaction, "retryCount", 3);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(1L, 1L))
                .willReturn(Optional.of(transaction));

        int result = bankTransactionService.resetToPendingForRetry(
                1L, 1L, BankTransactionProcessingStatus.UNMATCHED, 3
        );

        assertThat(result).isEqualTo(1);
        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.PENDING);
        assertThat(transaction.getRetryCount()).isEqualTo(4);
        verify(bankTransactionRepository).flush();
    }

    private BankTransaction transaction(BankTransactionProcessingStatus status) {
        BankTransaction transaction = new BankTransaction(
                1L, "TX-TEST", java.math.BigDecimal.ONE,
                org.teamsai.saibackend.domain.transaction.type.BankTransactionType.DEPOSIT,
                java.time.LocalDateTime.of(2026, 8, 5, 10, 0),
                null, null, java.time.LocalDateTime.of(2026, 8, 5, 10, 1)
        );
        transaction.changeProcessingStatus(status);
        return transaction;
    }
}
