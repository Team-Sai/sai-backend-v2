package org.teamsai.saibackend.domain.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionResponse;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionPersistenceService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 순수 DB 저장/커서 갱신 책임만 검증한다.
 * - 커서는 실제 최댓값 기준으로 갱신
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankTransactionPersistenceService 단위 테스트")
class BankTransactionPersistenceServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private LinkedBankAccountRepository linkedBankAccountRepository;

    @InjectMocks
    private BankTransactionPersistenceService bankTransactionPersistenceService;

    private static final Long LINKED_ACCOUNT_ID = 1L;
    private static final Long BANK_ACCOUNT_ID = 3L;

    @Test
    void acceptsUnchangedCursorWhenAccountStillExists() {
        given(linkedBankAccountRepository.advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), any(), any()))
                .willReturn(0);
        given(linkedBankAccountRepository.existsById(LINKED_ACCOUNT_ID)).willReturn(true);

        int result = bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID,
                List.of(createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT")));

        assertThat(result).isEqualTo(1);
    }

    @Test
    void rejectsUnchangedCursorWhenAccountWasDeleted() {
        given(linkedBankAccountRepository.advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), any(), any()))
                .willReturn(0);
        given(linkedBankAccountRepository.existsById(LINKED_ACCOUNT_ID)).willReturn(false);

        assertThatThrownBy(() -> bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID,
                List.of(createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT"))))
                .extracting("errorCode").isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND);
    }

    private BankTransactionResponse createTransactionResponse(
            Long transactionId, String transactionKey, String transactionType
    ) {
        return createTransactionResponse(transactionId, transactionKey, transactionType,
                BigDecimal.valueOf(150_000));
    }

    private BankTransactionResponse createTransactionResponse(
            Long transactionId, String transactionKey, String transactionType,
            BigDecimal balanceAfter
    ) {
        return new BankTransactionResponse(
                transactionId,
                transactionKey,
                BANK_ACCOUNT_ID,
                transactionType,
                BigDecimal.valueOf(50_000),
                balanceAfter,
                "홍길동",
                "110-***-1234",
                "테스트 입금",
                LocalDateTime.now(),
                1L
        );
    }

    @Test
    @DisplayName("빈 리스트가 들어오면 저장/커서 갱신 없이 0을 반환한다")
    void returnsZeroWhenTransactionsEmpty() {
        int result = bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, List.of());

        assertThat(result).isZero();
        verify(bankTransactionRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
        verify(linkedBankAccountRepository, never()).advanceCursorAndBalance(any(), any(), any());
    }

    @Test
    @DisplayName("모든 거래를 저장하고, 응답 순서와 무관하게 실제 최댓값 transactionId로 커서를 갱신한다")
    void savesAllAndAdvancesCursorToMaxTransactionId() {
        given(linkedBankAccountRepository.advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), any(), any()))
                .willReturn(1);
        // 정렬을 일부러 깨서 응답: 리스트 마지막 원소는 12, 실제 최댓값은 13
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT"),
                createTransactionResponse(13L, "MOCK-TX-B", "DEPOSIT"),
                createTransactionResponse(12L, "MOCK-TX-C", "WITHDRAWAL")
        );

        int result = bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions);

        assertThat(result).isEqualTo(3);

        ArgumentCaptor<String> externalIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<LocalDateTime> syncedAtCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(bankTransactionRepository, times(3)).insertIfAbsent(
                eq(LINKED_ACCOUNT_ID), externalIdCaptor.capture(), eq(BigDecimal.valueOf(50_000)),
                any(), any(), eq("홍길동"), eq("테스트 입금"), syncedAtCaptor.capture()
        );

        assertThat(externalIdCaptor.getAllValues())
                .containsExactlyInAnyOrder("MOCK-TX-A", "MOCK-TX-B", "MOCK-TX-C");
        assertThat(syncedAtCaptor.getAllValues()).doesNotContainNull();

        // 마지막 원소(12)가 아니라 실제 최댓값(13)으로 갱신되어야 한다.
        verify(linkedBankAccountRepository).advanceCursorAndBalance(
                eq(LINKED_ACCOUNT_ID), eq(13L), eq(BigDecimal.valueOf(150_000)));
    }

    @Test
    @DisplayName("가장 최근 거래의 balanceAfter를 연결 계좌 잔액으로 갱신한다")
    void updatesBalanceFromLatestTransaction() {
        given(linkedBankAccountRepository.advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), any(), any()))
                .willReturn(1);
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT",
                        BigDecimal.valueOf(120_000)),
                createTransactionResponse(13L, "MOCK-TX-B", "DEPOSIT",
                        BigDecimal.valueOf(180_000)),
                createTransactionResponse(12L, "MOCK-TX-C", "WITHDRAWAL",
                        BigDecimal.valueOf(150_000))
        );

        bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions);

        verify(linkedBankAccountRepository).advanceCursorAndBalance(
                eq(LINKED_ACCOUNT_ID), eq(13L), eq(BigDecimal.valueOf(180_000)));
    }

    @Test
    @DisplayName("가장 최근 거래의 balanceAfter가 null이면 잔액을 갱신하지 않는다")
    void skipsBalanceUpdateWhenLatestBalanceIsNull() {
        given(linkedBankAccountRepository.advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), any(), any()))
                .willReturn(1);
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT",
                        BigDecimal.valueOf(120_000)),
                createTransactionResponse(13L, "MOCK-TX-B", "DEPOSIT", null)
        );

        bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions);

        verify(linkedBankAccountRepository).advanceCursorAndBalance(
                eq(LINKED_ACCOUNT_ID), eq(13L), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("거래유형 문자열이 올바르지 않으면 INVALID_BANK_RESPONSE 예외를 던지고 커서는 갱신하지 않는다")
    void throwsInvalidBankResponseWhenTransactionTypeIsUnknown() {
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT"),
                createTransactionResponse(12L, "MOCK-TX-B", "UNKNOWN_TYPE") // 잘못된 값
        );

        assertThatThrownBy(() ->
                bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions)
        )
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(AccountErrorCode.INVALID_BANK_RESPONSE);

        verify(linkedBankAccountRepository, never()).advanceCursorAndBalance(any(), any(), any());
    }

    @Test
    @DisplayName("정상적으로 정의된 거래유형(DEPOSIT/WITHDRAWAL)은 올바르게 매핑된다")
    void mapsKnownTransactionTypesCorrectly() {
        given(linkedBankAccountRepository.advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), any(), any()))
                .willReturn(1);
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(1L, "MOCK-TX-A", "DEPOSIT"),
                createTransactionResponse(2L, "MOCK-TX-B", "WITHDRAWAL")
        );

        bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions);

        ArgumentCaptor<String> typeCaptor = ArgumentCaptor.forClass(String.class);
        verify(bankTransactionRepository, times(2)).insertIfAbsent(
                eq(LINKED_ACCOUNT_ID), any(), any(), typeCaptor.capture(),
                any(), any(), any(), any()
        );

        assertThat(typeCaptor.getAllValues()).containsExactly("DEPOSIT", "WITHDRAWAL");
    }

    @Test
    @DisplayName("거래 저장(insertIfAbsent) 중 DB 예외가 발생하면 그대로 전파하고 커서는 갱신하지 않는다")
    void propagatesExceptionWhenInsertFails() {
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT"),
                createTransactionResponse(12L, "MOCK-TX-B", "DEPOSIT")
        );
        DataIntegrityViolationException insertFailure =
                new DataIntegrityViolationException("제약 위반");

        willThrow(insertFailure).given(bankTransactionRepository).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());

        assertThatThrownBy(() ->
                bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions)
        )
                .isSameAs(insertFailure);

        // 첫 거래 저장 시점에 이미 실패했으므로, 커서는 절대 갱신되지 않아야 한다.
        verify(linkedBankAccountRepository, never()).advanceCursorAndBalance(any(), any(), any());
    }

    @Test
    @DisplayName("커서·잔액 원자적 갱신 중 DB 예외가 발생하면 그대로 전파한다")
    void propagatesExceptionWhenCursorUpdateFails() {
        List<BankTransactionResponse> transactions = List.of(
                createTransactionResponse(11L, "MOCK-TX-A", "DEPOSIT")
        );
        DataIntegrityViolationException cursorUpdateFailure =
                new DataIntegrityViolationException("커서 갱신 실패");

        willThrow(cursorUpdateFailure)
                .given(linkedBankAccountRepository)
                .advanceCursorAndBalance(eq(LINKED_ACCOUNT_ID), eq(11L), any());

        assertThatThrownBy(() ->
                bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions)
        )
                .isSameAs(cursorUpdateFailure);

        // 거래 저장 자체는 커서 갱신 이전에 이미 시도되었어야 한다.
        verify(bankTransactionRepository).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
