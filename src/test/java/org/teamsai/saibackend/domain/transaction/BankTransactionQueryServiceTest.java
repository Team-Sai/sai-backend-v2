package org.teamsai.saibackend.domain.transaction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.transaction.entity.BankTransactionEntity;
import org.teamsai.saibackend.domain.transaction.dto.request.BankTransactionSearchCondition;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionDetailResponse;
import org.teamsai.saibackend.domain.transaction.dto.response.PageResponse;
import org.teamsai.saibackend.domain.transaction.exception.BankTransactionErrorCode;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionQueryRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionQueryService;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionProcessingStatus;
import org.teamsai.saibackend.domain.transaction.type.BankTransactionType;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;


@ExtendWith(MockitoExtension.class)
@DisplayName("BankTransactionQueryService 단위 테스트")
class BankTransactionQueryServiceTest {

    @Mock
    private BankTransactionRepository bankTransactionRepository;

    @Mock
    private BankTransactionQueryRepository bankTransactionQueryRepository;

    @Mock
    private LinkedBankAccountRepository linkedBankAccountRepository;

    @InjectMocks
    private BankTransactionQueryService bankTransactionQueryService;

    private static final Long USER_ID = 10L;
    private static final Long OTHER_USER_ID = 99L;
    private static final Long LINKED_ACCOUNT_ID = 1L;
    private static final Long BANK_TRANSACTION_ID = 100L;

    private LinkedBankAccount createLinkedAccount() {
        return LinkedBankAccount.builder()
                .linkedAccountId(LINKED_ACCOUNT_ID)
                .userId(USER_ID)
                .accountId(5L)
                .build();
    }

    private BankTransactionEntity createTransaction(Long bankTransactionId) {
        BankTransactionEntity transaction = new BankTransactionEntity(
                LINKED_ACCOUNT_ID, "TX-" + bankTransactionId, BigDecimal.valueOf(30_000),
                BankTransactionType.DEPOSIT, LocalDateTime.now(),
                "홍길동", "테스트 입금", LocalDateTime.now()
        );
        ReflectionTestUtils.setField(transaction, "bankTransactionId", bankTransactionId);
        return transaction;
    }

    private BankTransactionSearchCondition defaultCondition() {
        return new BankTransactionSearchCondition(
                null, null, null, null, null, 0, 20
        );
    }

    @Nested
    @DisplayName("getTransactions(userId, linkedAccountId, condition)")
    class GetTransactions {

        @Test
        @DisplayName("본인 소유 계좌면 검색 결과와 총 건수를 담아 페이지 응답을 반환한다")
        void returnsPageResponseForOwnedAccount() {
            LinkedBankAccount linkedAccount = createLinkedAccount();
            BankTransactionSearchCondition condition = defaultCondition();
            List<BankTransactionEntity> transactions = List.of(
                    createTransaction(1L), createTransaction(2L)
            );

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(bankTransactionQueryRepository.search(LINKED_ACCOUNT_ID, condition.processingStatus(), condition.transactionType(),
                    condition.keyword(), null, null,
                    PageRequest.of(Math.toIntExact(condition.page()), Math.toIntExact(condition.size())))).willReturn(new PageImpl<>(transactions));

            PageResponse<?> result = bankTransactionQueryService.getTransactions(USER_ID, LINKED_ACCOUNT_ID, condition);

            assertThat(result.content()).hasSize(2);
            assertThat(result.totalCount()).isEqualTo(2L);
            assertThat(result.page()).isEqualTo(0);
            assertThat(result.size()).isEqualTo(20);
        }

        @Test
        @DisplayName("검색 결과가 없으면 빈 목록과 totalCount 0을 반환한다")
        void returnsEmptyPageWhenNoResults() {
            LinkedBankAccount linkedAccount = createLinkedAccount();
            BankTransactionSearchCondition condition = defaultCondition();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(bankTransactionQueryRepository.search(LINKED_ACCOUNT_ID, condition.processingStatus(), condition.transactionType(),
                    condition.keyword(), null, null,
                    PageRequest.of(Math.toIntExact(condition.page()), Math.toIntExact(condition.size())))).willReturn(new PageImpl<>(List.of()));

            PageResponse<?> result = bankTransactionQueryService.getTransactions(USER_ID, LINKED_ACCOUNT_ID, condition);

            assertThat(result.content()).isEmpty();
            assertThat(result.totalCount()).isZero();
        }

        @Test
        @DisplayName("연동계좌를 찾을 수 없으면 LINKED_ACCOUNT_NOT_FOUND 예외를 던지고 검색/카운트는 실행되지 않는다")
        void throwsWhenLinkedAccountNotFound() {
            BankTransactionSearchCondition condition = defaultCondition();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    bankTransactionQueryService.getTransactions(USER_ID, LINKED_ACCOUNT_ID, condition)
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND);

            verify(bankTransactionQueryRepository, never()).search(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("요청자가 연동계좌의 소유자가 아니면 ACCOUNT_ACCESS_DENIED 예외를 던지고 검색/카운트는 실행되지 않는다")
        void throwsWhenRequesterIsNotOwner() {
            LinkedBankAccount linkedAccount = createLinkedAccount(); // userId = USER_ID(10L) 소유
            BankTransactionSearchCondition condition = defaultCondition();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));

            assertThatThrownBy(() ->
                    bankTransactionQueryService.getTransactions(OTHER_USER_ID, LINKED_ACCOUNT_ID, condition)
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.ACCOUNT_ACCESS_DENIED);

            verify(bankTransactionQueryRepository, never()).search(any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("검색 조건과 페이지 정보를 repository에 전달한다")
        void passesSearchConditionToRepository() {
            LinkedBankAccount linkedAccount = createLinkedAccount();
            BankTransactionSearchCondition condition = new BankTransactionSearchCondition(
                    BankTransactionProcessingStatus.APPLIED,
                    BankTransactionType.DEPOSIT,
                    "홍길동",
                    null,
                    null,
                    1,
                    10
            );

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(bankTransactionQueryRepository.search(LINKED_ACCOUNT_ID, condition.processingStatus(), condition.transactionType(),
                    condition.keyword(), null, null,
                    PageRequest.of(Math.toIntExact(condition.page()), Math.toIntExact(condition.size())))).willReturn(new PageImpl<>(List.of()));

            bankTransactionQueryService.getTransactions(USER_ID, LINKED_ACCOUNT_ID, condition);

            verify(bankTransactionQueryRepository).search(
                    eq(LINKED_ACCOUNT_ID), eq(BankTransactionProcessingStatus.APPLIED),
                    eq(BankTransactionType.DEPOSIT), eq("홍길동"), eq(null), eq(null),
                    eq(PageRequest.of(1, 10))
            );
        }
    }

    @Nested
    @DisplayName("getTransactionDetail(userId, linkedAccountId, bankTransactionId)")
    class GetTransactionDetail {

        @Test
        @DisplayName("본인 소유 계좌의 거래면 상세 정보를 반환한다")
        void returnsDetailForOwnedAccount() {
            LinkedBankAccount linkedAccount = createLinkedAccount();
            BankTransactionEntity transaction = createTransaction(BANK_TRANSACTION_ID);

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(bankTransactionRepository.findByBankTransactionIdAndLinkedAccountId(BANK_TRANSACTION_ID, LINKED_ACCOUNT_ID))
                    .willReturn(Optional.of(transaction));

            BankTransactionDetailResponse response = bankTransactionQueryService.getTransactionDetail(
                    USER_ID, LINKED_ACCOUNT_ID, BANK_TRANSACTION_ID
            );

            assertThat(response.bankTransactionId()).isEqualTo(BANK_TRANSACTION_ID);
            assertThat(response.linkedAccountId()).isEqualTo(LINKED_ACCOUNT_ID);
            assertThat(response.counterpartyName()).isEqualTo("홍길동");
        }

        @Test
        @DisplayName("해당 연동계좌에 그 거래가 없으면 BANK_TRANSACTION_NOT_FOUND 예외를 던진다")
        void throwsWhenTransactionNotFound() {
            LinkedBankAccount linkedAccount = createLinkedAccount();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            given(bankTransactionRepository.findByBankTransactionIdAndLinkedAccountId(BANK_TRANSACTION_ID, LINKED_ACCOUNT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    bankTransactionQueryService.getTransactionDetail(USER_ID, LINKED_ACCOUNT_ID, BANK_TRANSACTION_ID)
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(BankTransactionErrorCode.BANK_TRANSACTION_NOT_FOUND);
        }

        @Test
        @DisplayName("연동계좌를 찾을 수 없으면 LINKED_ACCOUNT_NOT_FOUND 예외를 던지고 거래 조회는 실행되지 않는다")
        void throwsWhenLinkedAccountNotFound() {
            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    bankTransactionQueryService.getTransactionDetail(USER_ID, LINKED_ACCOUNT_ID, BANK_TRANSACTION_ID)
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.LINKED_ACCOUNT_NOT_FOUND);

            verify(bankTransactionRepository, never()).findByBankTransactionIdAndLinkedAccountId(any(), any());
        }

        @Test
        @DisplayName("요청자가 연동계좌의 소유자가 아니면 ACCOUNT_ACCESS_DENIED 예외를 던지고 거래 조회는 실행되지 않는다")
        void throwsWhenRequesterIsNotOwner() {
            LinkedBankAccount linkedAccount = createLinkedAccount(); // userId = USER_ID(10L) 소유

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));

            assertThatThrownBy(() ->
                    bankTransactionQueryService.getTransactionDetail(
                            OTHER_USER_ID, LINKED_ACCOUNT_ID, BANK_TRANSACTION_ID
                    )
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(AccountErrorCode.ACCOUNT_ACCESS_DENIED);

            verify(bankTransactionRepository, never()).findByBankTransactionIdAndLinkedAccountId(any(), any());
        }

        @Test
        @DisplayName("다른 연동계좌 소속의 거래 ID를 넣어도 findByBankTransactionIdAndLinkedAccountId가 함께 걸러낸다")
        void doesNotLeakTransactionFromAnotherLinkedAccount() {
            LinkedBankAccount linkedAccount = createLinkedAccount();

            given(linkedBankAccountRepository.findById(LINKED_ACCOUNT_ID)).willReturn(Optional.of(linkedAccount));
            // bankTransactionId는 존재하지만 다른 linkedAccountId 소속이라 매퍼 조회 결과가 비어있는 상황을 재현
            given(bankTransactionRepository.findByBankTransactionIdAndLinkedAccountId(BANK_TRANSACTION_ID, LINKED_ACCOUNT_ID))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    bankTransactionQueryService.getTransactionDetail(USER_ID, LINKED_ACCOUNT_ID, BANK_TRANSACTION_ID)
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(BankTransactionErrorCode.BANK_TRANSACTION_NOT_FOUND);

            verify(bankTransactionRepository).findByBankTransactionIdAndLinkedAccountId(BANK_TRANSACTION_ID, LINKED_ACCOUNT_ID);
        }
    }
}
