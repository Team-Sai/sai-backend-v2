package org.teamsai.saibackend.domain.account;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.RestClientException;
import org.teamsai.saibackend.domain.account.dto.request.LinkAccountRequest;
import org.teamsai.saibackend.domain.account.dto.response.AccountDetailResponse;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.client.MockBankClient;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("LinkedBankAccountService 단위 테스트")
class LinkedBankAccountServiceTest {

    @Mock
    private LinkedBankAccountRepository linkedBankAccountRepository;

    @Mock
    private MockBankClient mockBankClient;

    @Mock
    private UserService userService;


    @InjectMocks
    private LinkedBankAccountService linkedBankAccountService;

    private static final Long USER_ID = 1L;
    private static final String USER_KEY = "userKey";

    private AccountDetailResponse createDetail(
            Long accountId,
            String bankCode,
            String accountName
    ) {
        return new AccountDetailResponse(
                accountId,
                bankCode,
                "1111111111",
                accountName,
                "홍길동",
                BigDecimal.valueOf(100_000),
                "ACTIVE",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }

    private LinkedBankAccount createLinkedAccount(
            Long linkedAccountId,
            Long accountId,
            String bankCode,
            String accountAlias
    ) {
        return LinkedBankAccount.builder()
                .linkedAccountId(linkedAccountId)
                .userId(USER_ID)
                .accountId(accountId)
                .bankCode(bankCode)
                .accountNumber("1111111111")
                .accountAlias(accountAlias)
                .accountHolderName("홍길동")
                .balance(BigDecimal.valueOf(100_000))
                .connectionStatus(ConnectionStatus.AVAILABLE)
                .build();
    }

    @Nested
    @DisplayName("linkSelectedAccounts(userId, request)")
    class LinkSelectedAccounts {

        @Test
        @DisplayName("선택된 계좌들의 상세 정보를 조회해 저장하고 저장된 Entity를 반환한다")
        void savesSelectedAccountsAndReturnsEntities() {
            LinkAccountRequest.SelectedAccount selected1 =
                    new LinkAccountRequest.SelectedAccount(
                            1L,
                            "생활비통장"
                    );

            LinkAccountRequest.SelectedAccount selected2 =
                    new LinkAccountRequest.SelectedAccount(
                            2L,
                            "비상금통장"
                    );

            LinkAccountRequest request =
                    new LinkAccountRequest(List.of(selected1, selected2));

            AccountDetailResponse detail1 =
                    createDetail(
                            1L,
                            "088",
                            "사이 입출금통장"
                    );

            AccountDetailResponse detail2 =
                    createDetail(
                            2L,
                            "004",
                            "사이 저축통장"
                    );

            LinkedBankAccount saved1 =
                    createLinkedAccount(
                            101L,
                            1L,
                            "088",
                            "생활비통장"
                    );

            LinkedBankAccount saved2 =
                    createLinkedAccount(
                            102L,
                            2L,
                            "004",
                            "비상금통장"
                    );

            given(userService.getUserKeyByUserId(USER_ID))
                    .willReturn(USER_KEY);

            // 이미 연동된 계좌 없음
            given(linkedBankAccountRepository.findAllByUserId(USER_ID))
                    .willReturn(List.of());

            given(mockBankClient.getAccountDetail(1L, USER_KEY))
                    .willReturn(detail1);

            given(mockBankClient.getAccountDetail(2L, USER_KEY))
                    .willReturn(detail2);

            given(linkedBankAccountRepository.findByUserIdAndAccountId(
                    USER_ID,
                    1L
            )).willReturn(Optional.of(saved1));

            given(linkedBankAccountRepository.findByUserIdAndAccountId(
                    USER_ID,
                    2L
            )).willReturn(Optional.of(saved2));

            List<LinkedBankAccount> result =
                    linkedBankAccountService.linkSelectedAccounts(
                            USER_ID,
                            request
                    );

            assertThat(result).hasSize(2);

            assertThat(result)
                    .extracting(LinkedBankAccount::getLinkedAccountId)
                    .containsExactly(101L, 102L);

            assertThat(result)
                    .extracting(LinkedBankAccount::getAccountId)
                    .containsExactly(1L, 2L);

            assertThat(result)
                    .extracting(LinkedBankAccount::getAccountAlias)
                    .containsExactly(
                            "생활비통장",
                            "비상금통장"
                    );

            ArgumentCaptor<LinkedBankAccount> captor =
                    ArgumentCaptor.forClass(
                            LinkedBankAccount.class
                    );

            verify(linkedBankAccountRepository, times(2))
                    .insertOne(captor.capture());

            List<LinkedBankAccount> inserted =
                    captor.getAllValues();

            assertThat(inserted)
                    .extracting(LinkedBankAccount::getAccountId)
                    .containsExactly(1L, 2L);

            assertThat(inserted)
                    .allMatch(account ->
                            account.getUserId().equals(USER_ID)
                    )
                    .allMatch(account ->
                            account.getConnectionStatus()
                                    == ConnectionStatus.AVAILABLE
                    );

            verify(userService)
                    .getUserKeyByUserId(USER_ID);
        }

        @Test
        @DisplayName("이미 연동된 계좌는 상세 조회와 저장 대상에서 제외한다")
        void excludesAlreadyLinkedAccounts() {
            LinkAccountRequest.SelectedAccount selected1 =
                    new LinkAccountRequest.SelectedAccount(
                            1L,
                            "기존계좌"
                    );

            LinkAccountRequest.SelectedAccount selected2 =
                    new LinkAccountRequest.SelectedAccount(
                            2L,
                            "신규계좌"
                    );

            LinkAccountRequest request =
                    new LinkAccountRequest(
                            List.of(selected1, selected2)
                    );

            LinkedBankAccount existing =
                    createLinkedAccount(
                            100L,
                            1L,
                            "088",
                            "기존계좌"
                    );

            AccountDetailResponse detail2 =
                    createDetail(
                            2L,
                            "004",
                            "사이 저축통장"
                    );

            LinkedBankAccount saved2 =
                    createLinkedAccount(
                            102L,
                            2L,
                            "004",
                            "신규계좌"
                    );

            given(userService.getUserKeyByUserId(USER_ID))
                    .willReturn(USER_KEY);

            given(linkedBankAccountRepository.findAllByUserId(USER_ID))
                    .willReturn(List.of(existing));

            given(mockBankClient.getAccountDetail(2L, USER_KEY))
                    .willReturn(detail2);

            given(linkedBankAccountRepository.findByUserIdAndAccountId(
                    USER_ID,
                    2L
            )).willReturn(Optional.of(saved2));

            List<LinkedBankAccount> result =
                    linkedBankAccountService.linkSelectedAccounts(
                            USER_ID,
                            request
                    );

            assertThat(result).hasSize(1);

            assertThat(result.get(0).getAccountId())
                    .isEqualTo(2L);

            verify(mockBankClient, never())
                    .getAccountDetail(1L, USER_KEY);

            verify(mockBankClient)
                    .getAccountDetail(2L, USER_KEY);

            verify(linkedBankAccountRepository, times(1))
                    .insertOne(any(LinkedBankAccount.class));
        }

        @Test
        @DisplayName("같은 계좌가 요청에 중복되면 한 번만 상세 조회하고 저장한다")
        void removesDuplicateAccountsInSameRequest() {
            LinkAccountRequest.SelectedAccount selected1 =
                    new LinkAccountRequest.SelectedAccount(
                            1L,
                            "생활비통장"
                    );

            LinkAccountRequest.SelectedAccount selected2 =
                    new LinkAccountRequest.SelectedAccount(
                            1L,
                            "중복별칭"
                    );

            LinkAccountRequest request =
                    new LinkAccountRequest(
                            List.of(selected1, selected2)
                    );

            AccountDetailResponse detail =
                    createDetail(
                            1L,
                            "088",
                            "사이 입출금통장"
                    );

            LinkedBankAccount saved =
                    createLinkedAccount(
                            101L,
                            1L,
                            "088",
                            "생활비통장"
                    );

            given(userService.getUserKeyByUserId(USER_ID))
                    .willReturn(USER_KEY);

            given(linkedBankAccountRepository.findAllByUserId(USER_ID))
                    .willReturn(List.of());

            given(mockBankClient.getAccountDetail(1L, USER_KEY))
                    .willReturn(detail);

            given(linkedBankAccountRepository.findByUserIdAndAccountId(
                    USER_ID,
                    1L
            )).willReturn(Optional.of(saved));

            List<LinkedBankAccount> result =
                    linkedBankAccountService.linkSelectedAccounts(
                            USER_ID,
                            request
                    );

            assertThat(result).hasSize(1);

            verify(mockBankClient, times(1))
                    .getAccountDetail(1L, USER_KEY);

            verify(linkedBankAccountRepository, times(1))
                    .insertOne(any(LinkedBankAccount.class));
        }

        @Test
        @DisplayName("선택된 계좌가 없으면 상세 조회와 저장 없이 빈 목록을 반환한다")
        void returnsEmptyListWhenNoAccountsSelected() {
            LinkAccountRequest request =
                    new LinkAccountRequest(List.of());

            given(userService.getUserKeyByUserId(USER_ID))
                    .willReturn(USER_KEY);

            given(linkedBankAccountRepository.findAllByUserId(USER_ID))
                    .willReturn(List.of());

            List<LinkedBankAccount> result =
                    linkedBankAccountService.linkSelectedAccounts(
                            USER_ID,
                            request
                    );

            assertThat(result).isEmpty();

            verify(mockBankClient, never())
                    .getAccountDetail(any(), any());

            verify(linkedBankAccountRepository, never())
                    .insertOne(any());
        }

        @Test
        @DisplayName("계좌 상세 조회 실패 시 BANK_SERVER_UNAVAILABLE 예외를 던지고 저장하지 않는다")
        void throwsExceptionWhenBankServerUnavailable() {
            LinkAccountRequest.SelectedAccount selected =
                    new LinkAccountRequest.SelectedAccount(
                            1L,
                            "생활비통장"
                    );

            LinkAccountRequest request =
                    new LinkAccountRequest(List.of(selected));

            given(userService.getUserKeyByUserId(USER_ID))
                    .willReturn(USER_KEY);

            given(linkedBankAccountRepository.findAllByUserId(USER_ID))
                    .willReturn(List.of());

            given(mockBankClient.getAccountDetail(1L, USER_KEY))
                    .willThrow(
                            new RestClientException("연결 실패")
                    );

            assertThatThrownBy(() ->
                    linkedBankAccountService.linkSelectedAccounts(
                            USER_ID,
                            request
                    )
            )
                    .isInstanceOf(DomainException.class)
                    .extracting("errorCode")
                    .isEqualTo(
                            AccountErrorCode.BANK_SERVER_UNAVAILABLE
                    );

            verify(linkedBankAccountRepository, never())
                    .insertOne(any());
        }

        @Test
        @DisplayName("DB에서 중복으로 판정된 계좌는 반환 목록에서 제외한다")
        void skipsDuplicateDetectedByDatabase() {
            LinkAccountRequest.SelectedAccount selected =
                    new LinkAccountRequest.SelectedAccount(
                            1L,
                            "생활비통장"
                    );

            LinkAccountRequest request =
                    new LinkAccountRequest(List.of(selected));

            AccountDetailResponse detail =
                    createDetail(
                            1L,
                            "088",
                            "사이 입출금통장"
                    );

            given(userService.getUserKeyByUserId(USER_ID))
                    .willReturn(USER_KEY);

            given(linkedBankAccountRepository.findAllByUserId(USER_ID))
                    .willReturn(List.of());

            given(mockBankClient.getAccountDetail(1L, USER_KEY))
                    .willReturn(detail);

            doThrow(new DuplicateKeyException("duplicate"))
                    .when(linkedBankAccountRepository)
                    .insertOne(any(LinkedBankAccount.class));

            List<LinkedBankAccount> result =
                    linkedBankAccountService.linkSelectedAccounts(
                            USER_ID,
                            request
                    );

            assertThat(result).isEmpty();

            verify(linkedBankAccountRepository, never())
                    .findByUserIdAndAccountId(
                            anyLong(),
                            anyLong()
                    );
        }
    }
}