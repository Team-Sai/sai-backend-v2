package org.teamsai.saibackend.domain.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionResponse;
import org.teamsai.saibackend.domain.transaction.exception.RetryableBankTransactionFetchException;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionPersistenceService;
import org.teamsai.saibackend.domain.transaction.service.TransactionSyncService;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.client.MockBankClient;
import org.teamsai.saibackend.global.exception.DomainException;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TransactionSyncService 단위 테스트.
 *
 * 소유권 검증, 커서 조회, 사이은행 API 호출 조율만 담당하므로
 * 실제 DB 저장 로직은 목으로 대체하고,
 * "저장 서비스가 올바른 인자로 호출되는지"까지만 검증한다.
 * 저장/커서 갱신 로직 자체의 검증은 BankTransactionPersistenceServiceTest에서 다룬다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionSyncService 단위 테스트")
class TransactionSyncServiceTest {

    static Stream<RestClientException> retryableBankFailures() {
        return Stream.of(
                new ResourceAccessException("timeout", new SocketTimeoutException()),
                new ResourceAccessException("connection refused", new ConnectException()),
                new ResourceAccessException("nested timeout", new IOException(new SocketTimeoutException())),
                httpFailure(502), httpFailure(503), httpFailure(504)
        );
    }

    static Stream<RestClientException> nonRetryableBankFailures() {
        return Stream.of(
                httpFailure(401), httpFailure(403), httpFailure(429), httpFailure(500),
                new RestClientException("response conversion failed"),
                new ResourceAccessException("TLS failure", new SSLHandshakeException("invalid certificate")),
                new ResourceAccessException("DNS failure", new UnknownHostException()),
                new ResourceAccessException("unknown IO failure")
        );
    }

    private static RestClientResponseException httpFailure(int status) {
        return new RestClientResponseException("bank response", status, "bank error", null, null, null);
    }

    @ParameterizedTest
    @MethodSource("retryableBankFailures")
    @DisplayName("일시적 은행 오류는 원인을 보존하는 전용 예외로 변환하고 저장하지 않는다")
    void classifiesRetryableBankFailure(RestClientException failure) {
        stubBankFailure(failure);

        assertThatThrownBy(() -> transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID))
                .isInstanceOf(RetryableBankTransactionFetchException.class)
                .hasCause(failure)
                .satisfies(thrown -> assertThat(((DomainException) thrown).getErrorCode())
                        .isEqualTo(AccountErrorCode.BANK_SERVER_UNAVAILABLE));
        verify(bankTransactionPersistenceService, never()).saveAndAdvanceCursor(any(), any());
    }

    @ParameterizedTest
    @MethodSource("nonRetryableBankFailures")
    @DisplayName("허용하지 않은 은행 오류는 일반 도메인 예외로 유지하고 저장하지 않는다")
    void doesNotClassifyOtherBankFailuresAsRetryable(RestClientException failure) {
        stubBankFailure(failure);

        assertThatThrownBy(() -> transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID))
                .isExactlyInstanceOf(DomainException.class)
                .satisfies(thrown -> assertThat(((DomainException) thrown).getErrorCode())
                        .isEqualTo(AccountErrorCode.BANK_SERVER_UNAVAILABLE));
        verify(bankTransactionPersistenceService, never()).saveAndAdvanceCursor(any(), any());
    }

    @Test
    @DisplayName("은행 클라이언트의 일반 도메인 예외는 재시도 예외로 변환하지 않는다")
    void propagatesBankClientDomainFailure() {
        var failure = AccountErrorCode.BANK_SERVER_UNAVAILABLE.toException();
        stubBankFailure(failure);

        assertThatThrownBy(() -> transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID))
                .isSameAs(failure);
        verify(bankTransactionPersistenceService, never()).saveAndAdvanceCursor(any(), any());
    }

    private void stubBankFailure(RuntimeException failure) {
        given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID))
                .willReturn(Optional.of(createLinkedAccount()));
        given(userService.getUserKeyByUserId(USER_ID)).willReturn(USER_KEY);
        given(linkedBankAccountRepository.findLastSyncedTransactionIdById(LINKED_ACCOUNT_ID)).willReturn(5L);
        given(mockBankClient.getTransactions(BANK_ACCOUNT_ID, USER_KEY, 5L)).willThrow(failure);
    }

    @Mock
    private LinkedBankAccountRepository linkedBankAccountRepository;

    @Mock
    private MockBankClient mockBankClient;

    @Mock
    private UserService userService;

    @Mock
    private BankTransactionPersistenceService bankTransactionPersistenceService;

    @InjectMocks
    private TransactionSyncService transactionSyncService;

    private static final Long LINKED_ACCOUNT_ID = 1L;
    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long BANK_ACCOUNT_ID = 3L;
    private static final String USER_KEY = "mb_rawUserKey1234";

    private LinkedBankAccount createLinkedAccount() {
        return LinkedBankAccount.builder()
                .linkedAccountId(LINKED_ACCOUNT_ID)
                .userId(USER_ID)
                .accountId(BANK_ACCOUNT_ID)
                .build();
    }

    private BankTransactionResponse createTransactionResponse(Long transactionId, String transactionKey) {
        return new BankTransactionResponse(
                transactionId,
                transactionKey,
                BANK_ACCOUNT_ID,
                "DEPOSIT",
                BigDecimal.valueOf(50_000),
                BigDecimal.valueOf(150_000),
                "홍길동",
                "110-***-1234",
                "테스트 입금",
                LocalDateTime.now(),
                1L
        );
    }

    @Nested
    @DisplayName("syncTransactions(userId, linkedAccountId)")
    class SyncTransactions {

        @Test
        @DisplayName("커서가 없으면(null) 0부터 조회하고, 조회 결과를 저장 서비스에 그대로 위임한다")
        void syncsNewTransactionsWhenNoCursorExists() {
            LinkedBankAccount linkedAccount = createLinkedAccount();
            List<BankTransactionResponse> transactions = List.of(
                    createTransactionResponse(6L, "MOCK-TX-0001"),
                    createTransactionResponse(7L, "MOCK-TX-0002")
            );

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(userService.getUserKeyByUserId(USER_ID)).willReturn(USER_KEY);
            given(linkedBankAccountRepository.findLastSyncedTransactionIdById(LINKED_ACCOUNT_ID)).willReturn(null);
            given(mockBankClient.getTransactions(BANK_ACCOUNT_ID, USER_KEY, 0L)).willReturn(transactions);
            given(bankTransactionPersistenceService.saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions))
                    .willReturn(2);

            int result = transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID);

            assertThat(result).isEqualTo(2);
            verify(bankTransactionPersistenceService).saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions);
        }

        @Test
        @DisplayName("이미 커서가 있으면 그 값 이후로만 조회한다")
        void usesExistingCursorAsAfterTransactionId() {
            LinkedBankAccount linkedAccount = createLinkedAccount();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(userService.getUserKeyByUserId(USER_ID)).willReturn(USER_KEY);
            given(linkedBankAccountRepository.findLastSyncedTransactionIdById(LINKED_ACCOUNT_ID)).willReturn(5L);
            given(mockBankClient.getTransactions(BANK_ACCOUNT_ID, USER_KEY, 5L)).willReturn(List.of());
            given(bankTransactionPersistenceService.saveAndAdvanceCursor(eq(LINKED_ACCOUNT_ID), any()))
                    .willReturn(0);

            transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID);

            verify(mockBankClient).getTransactions(BANK_ACCOUNT_ID, USER_KEY, 5L);
        }

        @Test
        @DisplayName("연동계좌를 찾을 수 없으면 LINKED_ACCOUNT_NOT_FOUND 예외를 던지고 이후 로직은 실행되지 않는다")
        void throwsWhenLinkedAccountNotFound() {
            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() -> transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID))
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND);

            verify(userService, never()).getUserKeyByUserId(any());
            verify(mockBankClient, never()).getTransactions(any(), any(), any());
            verify(bankTransactionPersistenceService, never()).saveAndAdvanceCursor(any(), any());
        }

        @Test
        @DisplayName("요청자가 연동계좌의 소유자가 아니면 ACCOUNT_ACCESS_DENIED 예외를 던지고 이후 로직은 실행되지 않는다")
        void throwsWhenRequesterIsNotOwner() {
            LinkedBankAccount linkedAccount = createLinkedAccount(); // userId = USER_ID(10L) 소유

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));

            assertThatThrownBy(() -> transactionSyncService.syncTransactions(OTHER_USER_ID, LINKED_ACCOUNT_ID))
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.ACCOUNT_ACCESS_DENIED);

            verify(userService, never()).getUserKeyByUserId(any());
            verify(mockBankClient, never()).getTransactions(any(), any(), any());
            verify(bankTransactionPersistenceService, never()).saveAndAdvanceCursor(any(), any());
        }

        @Test
        @DisplayName("사이은행 통신 실패 시 BANK_SERVER_UNAVAILABLE 예외로 변환하고 저장은 시도하지 않는다")
        void throwsWhenBankServerUnavailable() {
            LinkedBankAccount linkedAccount = createLinkedAccount();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(userService.getUserKeyByUserId(USER_ID)).willReturn(USER_KEY);
            given(linkedBankAccountRepository.findLastSyncedTransactionIdById(LINKED_ACCOUNT_ID)).willReturn(0L);
            given(mockBankClient.getTransactions(BANK_ACCOUNT_ID, USER_KEY, 0L))
                    .willThrow(new RestClientException("연결 실패"));

            assertThatThrownBy(() -> transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID))
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.BANK_SERVER_UNAVAILABLE);

            verify(bankTransactionPersistenceService, never()).saveAndAdvanceCursor(any(), any());
        }

        @Test
        @DisplayName("저장 단계(BankTransactionPersistenceService)에서 예외가 발생하면 그대로 전파한다")
        void propagatesExceptionWhenPersistenceFails() {
            LinkedBankAccount linkedAccount = createLinkedAccount();
            List<BankTransactionResponse> transactions = List.of(
                    createTransactionResponse(6L, "MOCK-TX-0001")
            );
            DomainException persistenceFailure = mock(DomainException.class);

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(userService.getUserKeyByUserId(USER_ID)).willReturn(USER_KEY);
            given(linkedBankAccountRepository.findLastSyncedTransactionIdById(LINKED_ACCOUNT_ID)).willReturn(5L);
            given(mockBankClient.getTransactions(BANK_ACCOUNT_ID, USER_KEY, 5L)).willReturn(transactions);
            willThrow(persistenceFailure)
                    .given(bankTransactionPersistenceService)
                    .saveAndAdvanceCursor(LINKED_ACCOUNT_ID, transactions);

            assertThatThrownBy(() -> transactionSyncService.syncTransactions(USER_ID, LINKED_ACCOUNT_ID))
                    .isSameAs(persistenceFailure);

            // 사이은행 조회 자체는 이미 성공적으로 끝난 뒤, 저장 단계에서만 실패한 상황임을 확인한다.
            verify(mockBankClient).getTransactions(BANK_ACCOUNT_ID, USER_KEY, 5L);
        }
    }
}
