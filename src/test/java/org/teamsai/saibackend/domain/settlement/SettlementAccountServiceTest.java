package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.settlement.dto.request.SelectSettlementAccountRequest;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementAccountResponse;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementAccount;
import org.teamsai.saibackend.domain.settlement.exception.SettlementErrorCode;
import org.teamsai.saibackend.domain.settlement.repository.SettlementAccountRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.SettlementAccountService;
import org.teamsai.saibackend.domain.settlement.service.SettlementValidator;
import org.teamsai.saibackend.domain.settlement.type.SettlementAccountStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementAccountService 단위 테스트")
class SettlementAccountServiceTest {

    private static final Long OWNER_ID = 3L;
    private static final Long OTHER_USER_ID = 4L;

    private static final Long SETTLEMENT_ID = 6L;

    private static final Long FIRST_LINKED_ACCOUNT_ID = 2L;
    private static final Long SECOND_LINKED_ACCOUNT_ID = 3L;

    private static final Long FIRST_SETTLEMENT_ACCOUNT_ID = 1L;
    private static final Long SECOND_SETTLEMENT_ACCOUNT_ID = 2L;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementAccountRepository settlementAccountRepository;

    @Mock
    private LinkedBankAccountService linkedBankAccountService;

    @Mock
    private SettlementValidator settlementValidator;

    @InjectMocks
    private SettlementAccountService settlementAccountService;


    @Nested
    @DisplayName("정산 수취 계좌 설정")
    class SelectAccount {

        @Test
        @DisplayName("수취 계좌가 없으면 선택한 연동 계좌를 ACTIVE 상태로 등록한다")
        void selectAccountSuccessWhenCurrentAccountDoesNotExist() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SelectSettlementAccountRequest request =
                    createRequest(FIRST_LINKED_ACCOUNT_ID);

            LinkedBankAccountResponse linkedAccount =
                    linkedAccount(FIRST_LINKED_ACCOUNT_ID);

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement));

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatusForUpdate(
                                    SETTLEMENT_ID,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(Optional.empty());

            when(
                    settlementAccountRepository.save(
                            any(SettlementAccount.class)
                    )
            ).thenAnswer(invocation -> {

                SettlementAccount account =
                        invocation.getArgument(0);

                ReflectionTestUtils.setField(
                        account,
                        "settlementAccountId",
                        FIRST_SETTLEMENT_ACCOUNT_ID
                );

                return account;
            });

            when(
                    linkedBankAccountService
                            .getLinkedAccounts(OWNER_ID)
            ).thenReturn(
                    List.of(linkedAccount)
            );


            SettlementAccountResponse response =
                    settlementAccountService.selectAccount(
                            OWNER_ID,
                            SETTLEMENT_ID,
                            request.getLinkedAccountId()
                    );


            ArgumentCaptor<SettlementAccount> captor =
                    ArgumentCaptor.forClass(
                            SettlementAccount.class
                    );

            verify(settlementAccountRepository)
                    .save(captor.capture());

            SettlementAccount savedAccount =
                    captor.getValue();

            assertThat(savedAccount.getSettlement().getSettlementId())
                    .isEqualTo(SETTLEMENT_ID);

            assertThat(savedAccount.getLinkedAccountId())
                    .isEqualTo(FIRST_LINKED_ACCOUNT_ID);

            assertThat(savedAccount.getAccountStatus())
                    .isEqualTo(
                            SettlementAccountStatus.ACTIVE
                    );

            assertThat(savedAccount.getSelectedAt())
                    .isNotNull();

            assertThat(savedAccount.getEndedAt())
                    .isNull();

            assertThat(response.getSettlementAccountId())
                    .isEqualTo(
                            FIRST_SETTLEMENT_ACCOUNT_ID
                    );

            assertThat(response.getSettlementId())
                    .isEqualTo(SETTLEMENT_ID);

            assertThat(response.getLinkedAccountId())
                    .isEqualTo(
                            FIRST_LINKED_ACCOUNT_ID
                    );

            assertThat(response.getAccountStatus())
                    .isEqualTo(
                            SettlementAccountStatus.ACTIVE
                    );

            verify(settlementValidator)
                    .validateOwner(
                            settlement,
                            OWNER_ID
                    );

            verify(settlementValidator)
                    .validateLinkedAccountOwner(
                            OWNER_ID,
                            FIRST_LINKED_ACCOUNT_ID
                    );
        }


        @Test
        @DisplayName("현재 수취 계좌와 같은 계좌를 다시 선택하면 새 행을 생성하지 않는다")
        void selectSameAccountDoesNotInsertAgain() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SettlementAccount currentAccount =
                    createActiveSettlementAccount(
                            FIRST_SETTLEMENT_ACCOUNT_ID,
                            FIRST_LINKED_ACCOUNT_ID
                    );

            SelectSettlementAccountRequest request =
                    createRequest(FIRST_LINKED_ACCOUNT_ID);

            LinkedBankAccountResponse linkedAccount =
                    linkedAccount(FIRST_LINKED_ACCOUNT_ID);

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(Optional.of(settlement));

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatusForUpdate(
                                    SETTLEMENT_ID,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(
                    Optional.of(currentAccount)
            );

            when(
                    linkedBankAccountService
                            .getLinkedAccounts(OWNER_ID)
            ).thenReturn(
                    List.of(linkedAccount)
            );


            SettlementAccountResponse response =
                    settlementAccountService.selectAccount(
                            OWNER_ID,
                            SETTLEMENT_ID,
                            request.getLinkedAccountId()
                    );


            assertThat(response.getSettlementAccountId())
                    .isEqualTo(
                            FIRST_SETTLEMENT_ACCOUNT_ID
                    );

            assertThat(response.getLinkedAccountId())
                    .isEqualTo(
                            FIRST_LINKED_ACCOUNT_ID
                    );

            assertThat(currentAccount.getAccountStatus())
                    .isEqualTo(
                            SettlementAccountStatus.ACTIVE
                    );

            assertThat(currentAccount.getEndedAt())
                    .isNull();

            verify(settlementValidator)
                    .validateOwner(
                            settlement,
                            OWNER_ID
                    );

            verify(settlementValidator)
                    .validateLinkedAccountOwner(
                            OWNER_ID,
                            FIRST_LINKED_ACCOUNT_ID
                    );

            verify(settlementAccountRepository, never())
                    .save(any());
        }


        @Test
        @DisplayName("다른 계좌로 변경하면 기존 계좌를 REPLACED로 변경하고 새 ACTIVE 계좌를 등록한다")
        void replaceSettlementAccountSuccess() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SettlementAccount currentAccount =
                    createActiveSettlementAccount(
                            FIRST_SETTLEMENT_ACCOUNT_ID,
                            FIRST_LINKED_ACCOUNT_ID
                    );

            SelectSettlementAccountRequest request =
                    createRequest(
                            SECOND_LINKED_ACCOUNT_ID
                    );

            LinkedBankAccountResponse secondLinkedAccount =
                    linkedAccount(
                            SECOND_LINKED_ACCOUNT_ID
                    );

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatusForUpdate(
                                    SETTLEMENT_ID,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(
                    Optional.of(currentAccount)
            );

            when(
                    settlementAccountRepository.save(
                            any(SettlementAccount.class)
                    )
            ).thenAnswer(invocation -> {

                SettlementAccount account =
                        invocation.getArgument(0);

                ReflectionTestUtils.setField(
                        account,
                        "settlementAccountId",
                        SECOND_SETTLEMENT_ACCOUNT_ID
                );

                return account;
            });

            when(
                    linkedBankAccountService
                            .getLinkedAccounts(OWNER_ID)
            ).thenReturn(
                    List.of(
                            secondLinkedAccount
                    )
            );


            SettlementAccountResponse response =
                    settlementAccountService.selectAccount(
                            OWNER_ID,
                            SETTLEMENT_ID,
                            request.getLinkedAccountId()
                    );


            assertThat(currentAccount.getAccountStatus())
                    .isEqualTo(
                            SettlementAccountStatus.REPLACED
                    );

            assertThat(currentAccount.getEndedAt())
                    .isNotNull();

            ArgumentCaptor<SettlementAccount> accountCaptor =
                    ArgumentCaptor.forClass(
                            SettlementAccount.class
                    );

            verify(settlementAccountRepository)
                    .save(
                            accountCaptor.capture()
                    );

            SettlementAccount newAccount =
                    accountCaptor.getValue();

            assertThat(newAccount.getLinkedAccountId())
                    .isEqualTo(
                            SECOND_LINKED_ACCOUNT_ID
                    );

            assertThat(newAccount.getAccountStatus())
                    .isEqualTo(
                            SettlementAccountStatus.ACTIVE
                    );

            assertThat(newAccount.getEndedAt())
                    .isNull();

            assertThat(newAccount.getSettlement())
                    .isEqualTo(settlement);

            assertThat(response.getLinkedAccountId())
                    .isEqualTo(
                            SECOND_LINKED_ACCOUNT_ID
                    );

            verify(settlementValidator)
                    .validateOwner(
                            settlement,
                            OWNER_ID
                    );

            verify(settlementValidator)
                    .validateLinkedAccountOwner(
                            OWNER_ID,
                            SECOND_LINKED_ACCOUNT_ID
                    );
        }

        @Test
        @DisplayName("정산 생성자가 아니면 수취 계좌를 설정할 수 없다")
        void selectAccountFailsWhenUserIsNotOwner() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SelectSettlementAccountRequest request =
                    createRequest(
                            FIRST_LINKED_ACCOUNT_ID
                    );

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            doThrow(
                    SettlementErrorCode
                            .SETTLEMENT_ACCESS_DENIED
                            .toException()
            ).when(settlementValidator)
                    .validateOwner(
                            settlement,
                            OTHER_USER_ID
                    );


            assertThatThrownBy(
                    () ->
                            settlementAccountService
                                    .selectAccount(
                                            OTHER_USER_ID,
                                            SETTLEMENT_ID,
                                            request.getLinkedAccountId()
                                    )
            ).isInstanceOf(
                    DomainException.class
            );


            verify(settlementValidator)
                    .validateOwner(
                            settlement,
                            OTHER_USER_ID
                    );

            verify(
                    settlementValidator,
                    never()
            ).validateLinkedAccountOwner(
                    any(),
                    any()
            );

            verifyNoInteractions(
                    settlementAccountRepository
            );
        }


        @Test
        @DisplayName("본인에게 연동되지 않은 계좌는 정산 수취 계좌로 설정할 수 없다")
        void selectAccountFailsWhenLinkedAccountDoesNotBelongToUser() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SelectSettlementAccountRequest request =
                    createRequest(
                            SECOND_LINKED_ACCOUNT_ID
                    );

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            doThrow(
                    SettlementErrorCode
                            .INVALID_SETTLEMENT_ACCOUNT
                            .toException()
            ).when(settlementValidator)
                    .validateLinkedAccountOwner(
                            OWNER_ID,
                            SECOND_LINKED_ACCOUNT_ID
                    );


            assertThatThrownBy(
                    () ->
                            settlementAccountService
                                    .selectAccount(
                                            OWNER_ID,
                                            SETTLEMENT_ID,
                                            request.getLinkedAccountId()
                                    )
            ).isInstanceOf(
                    DomainException.class
            );


            verify(settlementValidator)
                    .validateOwner(
                            settlement,
                            OWNER_ID
                    );

            verify(settlementValidator)
                    .validateLinkedAccountOwner(
                            OWNER_ID,
                            SECOND_LINKED_ACCOUNT_ID
                    );

            verifyNoInteractions(
                    settlementAccountRepository
            );
        }


        @Test
        @DisplayName("존재하지 않는 정산에는 수취 계좌를 설정할 수 없다")
        void selectAccountFailsWhenSettlementDoesNotExist() {

            SelectSettlementAccountRequest request =
                    createRequest(
                            FIRST_LINKED_ACCOUNT_ID
                    );

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.empty()
                    );


            assertThatThrownBy(
                    () ->
                            settlementAccountService
                                    .selectAccount(
                                            OWNER_ID,
                                            SETTLEMENT_ID,
                                            request.getLinkedAccountId()
                                    )
            ).isInstanceOf(
                    DomainException.class
            );


            verifyNoInteractions(
                    settlementValidator,
                    settlementAccountRepository
            );
        }


        @Test
        @DisplayName("새 수취 계좌 저장에 실패하면 예외가 발생한다")
        void selectAccountFailsWhenInsertFails() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SelectSettlementAccountRequest request =
                    createRequest(
                            FIRST_LINKED_ACCOUNT_ID
                    );

            when(settlementRepository.findByIdForUpdate(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatusForUpdate(
                                    SETTLEMENT_ID,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(
                    Optional.empty()
            );

            when(
                    settlementAccountRepository.save(
                            any(SettlementAccount.class)
                    )
            ).thenThrow(
                    new RuntimeException("save failed")
            );


            assertThatThrownBy(
                    () ->
                            settlementAccountService
                                    .selectAccount(
                                            OWNER_ID,
                                            SETTLEMENT_ID,
                                            request.getLinkedAccountId()
                                    )
            ).isInstanceOf(
                    RuntimeException.class
            );


            verify(settlementValidator)
                    .validateOwner(
                            settlement,
                            OWNER_ID
                    );

            verify(settlementValidator)
                    .validateLinkedAccountOwner(
                            OWNER_ID,
                            FIRST_LINKED_ACCOUNT_ID
                    );
        }
    }


    @Nested
    @DisplayName("현재 정산 수취 계좌 조회")
    class FindCurrentAccount {

        @Test
        @DisplayName("현재 ACTIVE 상태의 수취 계좌를 조회한다")
        void findCurrentAccountSuccess() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            SettlementAccount account =
                    createActiveSettlementAccount(
                            SECOND_SETTLEMENT_ACCOUNT_ID,
                            SECOND_LINKED_ACCOUNT_ID
                    );

            LinkedBankAccountResponse linkedAccount =
                    linkedAccount(SECOND_LINKED_ACCOUNT_ID);

            when(settlementRepository.findById(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatus(
                                    SETTLEMENT_ID,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(
                    Optional.of(account)
            );

            when(
                    linkedBankAccountService
                            .getLinkedAccounts(OWNER_ID)
            ).thenReturn(
                    List.of(linkedAccount)
            );


            SettlementAccountResponse response =
                    settlementAccountService
                            .findCurrentAccount(
                                    OWNER_ID,
                                    SETTLEMENT_ID
                            );


            assertThat(response.getSettlementAccountId())
                    .isEqualTo(
                            SECOND_SETTLEMENT_ACCOUNT_ID
                    );

            assertThat(response.getSettlementId())
                    .isEqualTo(
                            SETTLEMENT_ID
                    );

            assertThat(response.getLinkedAccountId())
                    .isEqualTo(
                            SECOND_LINKED_ACCOUNT_ID
                    );

            assertThat(response.getAccountStatus())
                    .isEqualTo(
                            SettlementAccountStatus.ACTIVE
                    );

            verify(settlementValidator)
                    .validateAccessibleUser(
                            settlement,
                            OWNER_ID
                    );
        }

        @Test
        @DisplayName("참여자는 정산 소유자의 현재 수취 계좌를 조회한다")
        void participantFindsOwnersCurrentAccount() {
            Settlement settlement = createSettlement(OWNER_ID);
            SettlementAccount account = createActiveSettlementAccount(
                    SECOND_SETTLEMENT_ACCOUNT_ID,
                    SECOND_LINKED_ACCOUNT_ID
            );
            LinkedBankAccountResponse linkedAccount = linkedAccount(SECOND_LINKED_ACCOUNT_ID);
            when(settlementRepository.findById(SETTLEMENT_ID)).thenReturn(Optional.of(settlement));
            when(
                    settlementAccountRepository.findBySettlementIdAndStatus(
                            SETTLEMENT_ID,
                            SettlementAccountStatus.ACTIVE
                    )
            ).thenReturn(Optional.of(account));
            when(linkedBankAccountService.getLinkedAccounts(OWNER_ID))
                    .thenReturn(List.of(linkedAccount));

            SettlementAccountResponse response = settlementAccountService.findCurrentAccount(
                    OTHER_USER_ID,
                    SETTLEMENT_ID
            );

            assertThat(response.getLinkedAccountId()).isEqualTo(SECOND_LINKED_ACCOUNT_ID);
            verify(settlementValidator).validateAccessibleUser(settlement, OTHER_USER_ID);
            verify(linkedBankAccountService).getLinkedAccounts(OWNER_ID);
        }


        @Test
        @DisplayName("현재 설정된 수취 계좌가 없으면 예외가 발생한다")
        void findCurrentAccountFailsWhenAccountDoesNotExist() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            when(settlementRepository.findById(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            when(
                    settlementAccountRepository
                            .findBySettlementIdAndStatus(
                                    SETTLEMENT_ID,
                                    SettlementAccountStatus.ACTIVE
                            )
            ).thenReturn(
                    Optional.empty()
            );


            assertThatThrownBy(
                    () ->
                            settlementAccountService
                                    .findCurrentAccount(
                                            OWNER_ID,
                                            SETTLEMENT_ID
                                    )
            ).isInstanceOf(
                    DomainException.class
            );


            verify(settlementValidator)
                    .validateAccessibleUser(
                            settlement,
                            OWNER_ID
                    );
        }


        @Test
        @DisplayName("정산 생성자가 아니면 현재 수취 계좌를 조회할 수 없다")
        void findCurrentAccountFailsWhenUserIsNotOwner() {

            Settlement settlement =
                    createSettlement(OWNER_ID);

            when(settlementRepository.findById(SETTLEMENT_ID))
                    .thenReturn(
                            Optional.of(settlement)
                    );

            doThrow(
                    SettlementErrorCode
                            .SETTLEMENT_ACCESS_DENIED
                            .toException()
            ).when(settlementValidator)
                    .validateAccessibleUser(
                            settlement,
                            OTHER_USER_ID
                    );


            assertThatThrownBy(
                    () ->
                            settlementAccountService
                                    .findCurrentAccount(
                                            OTHER_USER_ID,
                                            SETTLEMENT_ID
                                    )
            ).isInstanceOf(
                    DomainException.class
            );


            verify(settlementValidator)
                    .validateAccessibleUser(
                            settlement,
                            OTHER_USER_ID
                    );

            verify(
                    settlementAccountRepository,
                    never()
            ).findBySettlementIdAndStatus(
                    SETTLEMENT_ID,
                    SettlementAccountStatus.ACTIVE
            );
        }
    }


    private Settlement createSettlement(
            Long ownerId
    ) {

        return Settlement.builder()
                .settlementId(SETTLEMENT_ID)
                .owner(
                        User.builder()
                                .userId(ownerId)
                                .build()
                )
                .build();
    }


    private SelectSettlementAccountRequest createRequest(
            Long linkedAccountId
    ) {

        return SelectSettlementAccountRequest.builder()
                .linkedAccountId(linkedAccountId)
                .build();
    }


    private SettlementAccount createActiveSettlementAccount(
            Long settlementAccountId,
            Long linkedAccountId
    ) {

        SettlementAccount account =
                SettlementAccount.create(
                        createSettlement(OWNER_ID),
                        linkedAccountId,
                        LocalDateTime.now()
                                .minusHours(1)
                );

        ReflectionTestUtils.setField(
                account,
                "settlementAccountId",
                settlementAccountId
        );

        return account;
    }


    private LinkedBankAccountResponse linkedAccount(
            Long linkedAccountId
    ) {

        LinkedBankAccountResponse linkedAccount =
                mock(LinkedBankAccountResponse.class);

        when(linkedAccount.linkedAccountId())
                .thenReturn(linkedAccountId);

        when(linkedAccount.bankName())
                .thenReturn("테스트은행");

        when(linkedAccount.maskedAccountNumber())
                .thenReturn("123-****-4567");

        when(linkedAccount.accountHolderName())
                .thenReturn("홍길동");

        return linkedAccount;
    }
}