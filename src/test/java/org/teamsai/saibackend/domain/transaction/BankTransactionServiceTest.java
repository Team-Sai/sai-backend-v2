package org.teamsai.saibackend.domain.transaction;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("BankTransactionService 단위 테스트")
class BankTransactionServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private BankTransactionService bankTransactionService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(bankTransactionService, "entityManager", entityManager);
    }

    @Test
    @DisplayName("연결계좌와 거래 ID로 은행 거래를 잠금 조회한다")
    void findsTransactionByIdAndLinkedAccountIdForUpdate() {
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.PENDING);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(
                101L,
                1L
        )).willReturn(Optional.of(transaction));

        BankTransactionEntity result = bankTransactionService
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
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.NEEDS_CHECK);
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
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.NEEDS_CHECK);
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
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.APPLIED);
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
    @DisplayName("매칭용 잠금 조회는 DB에서 갱신한 최신 상태를 반환한다")
    void refreshesTransactionBeforeMatching() {
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.PENDING);
        given(bankTransactionRepository.findLockedByBankTransactionIdAndLinkedAccountId(101L, 1L))
                .willReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            transaction.changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
            return null;
        }).when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

        BankTransactionEntity result =
                bankTransactionService.findByIdAndLinkedAccountIdForUpdate(101L, 1L);

        assertThat(result.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
    }

    @Test
    @DisplayName("상태 변경 전에 최신 상태를 읽어 이미 처리된 거래의 변경을 거부한다")
    void checksRefreshedStatusBeforeUpdating() {
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.PENDING);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            transaction.changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
            return null;
        }).when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

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
    @DisplayName("재시도 전에 최신 상태를 읽어 이미 처리된 거래의 초기화를 건너뛴다")
    void skipsRetryWhenRefreshedStatusDoesNotMatch() {
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.UNMATCHED);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            transaction.changeProcessingStatus(BankTransactionProcessingStatus.APPLIED);
            return null;
        }).when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

        int result = bankTransactionService.resetToPendingForRetry(
                1L, BankTransactionProcessingStatus.UNMATCHED
        );

        assertThat(result).isZero();
        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
        assertThat(transaction.getRetryCount()).isZero();
        verify(bankTransactionRepository, never()).flush();
    }

    @Test
    @DisplayName("재시도 횟수도 최신 값에서 증가시킨다")
    void incrementsRefreshedRetryCount() {
        BankTransactionEntity transaction = transaction(BankTransactionProcessingStatus.UNMATCHED);
        given(bankTransactionRepository.findLockedByBankTransactionId(1L))
                .willReturn(Optional.of(transaction));
        doAnswer(invocation -> {
            org.springframework.test.util.ReflectionTestUtils.setField(transaction, "retryCount", 3);
            return null;
        }).when(entityManager).refresh(transaction, LockModeType.PESSIMISTIC_WRITE);

        int result = bankTransactionService.resetToPendingForRetry(
                1L, BankTransactionProcessingStatus.UNMATCHED
        );

        assertThat(result).isEqualTo(1);
        assertThat(transaction.getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.PENDING);
        assertThat(transaction.getRetryCount()).isEqualTo(4);
        verify(bankTransactionRepository).flush();
    }

    private BankTransactionEntity transaction(BankTransactionProcessingStatus status) {
        BankTransactionEntity transaction = new BankTransactionEntity(
                1L, "TX-TEST", java.math.BigDecimal.ONE,
                org.teamsai.saibackend.domain.transaction.type.BankTransactionType.DEPOSIT,
                java.time.LocalDateTime.of(2026, 8, 5, 10, 0),
                null, null, java.time.LocalDateTime.of(2026, 8, 5, 10, 1)
        );
        transaction.changeProcessingStatus(status);
        return transaction;
    }
}
