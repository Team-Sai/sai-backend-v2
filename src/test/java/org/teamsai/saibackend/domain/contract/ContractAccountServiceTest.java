package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.account.dto.response.LinkedBankAccountResponse;
import org.teamsai.saibackend.domain.account.dto.type.ConnectionStatus;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.contract.dto.ContractAccountStatus;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.entity.ContractAccount;
import org.teamsai.saibackend.domain.contract.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.ContractAccountRepository;
import org.teamsai.saibackend.domain.contract.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.service.ContractAccountService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ContractAccountService 단위 테스트")
class ContractAccountServiceTest {

    private static final Long CONTRACT_ID = 1L;
    private static final Long CREDITOR_ID = 1L;
    private static final Long OTHER_USER_ID = 999L;
    private static final Long LINKED_ACCOUNT_ID = 10L;
    private static final Long OTHER_LINKED_ACCOUNT_ID = 20L;

    @Mock
    private ContractAccountRepository contractAccountRepository;

    @Mock
    private LoanContractRepository loanContractRepository;

    @Mock
    private LinkedBankAccountRepository linkedBankAccountRepository;

    @Mock
    private LinkedBankAccountService linkedBankAccountService;

    @InjectMocks
    private ContractAccountService contractAccountService;

    @Nested
    @DisplayName("선택 가능한 계좌 조회")
    class GetSelectableAccounts {

        @Test
        @DisplayName("연동 계좌 중 AVAILABLE 상태인 계좌만 반환한다")
        void getSelectableAccountsFiltersAvailableOnly() {
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of(
                    createLinkedAccount(LINKED_ACCOUNT_ID, ConnectionStatus.AVAILABLE),
                    createLinkedAccount(OTHER_LINKED_ACCOUNT_ID, ConnectionStatus.UNAVAILABLE)
            ));

            List<LinkedBankAccountResponse> result = contractAccountService.getSelectableAccounts(CREDITOR_ID);

            assertThat(result).extracting(LinkedBankAccountResponse::linkedAccountId)
                    .containsExactly(LINKED_ACCOUNT_ID);
        }

        @Test
        @DisplayName("연동 계좌가 없으면 빈 목록을 반환한다")
        void getSelectableAccountsReturnsEmptyWhenNoLinkedAccounts() {
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of());

            List<LinkedBankAccountResponse> result = contractAccountService.getSelectableAccounts(CREDITOR_ID);

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("계약 계좌 최초 설정")
    class SetupContractAccount {

        @Test
        @DisplayName("연동 계좌를 선택하면 활성 계좌로 등록한다")
        void setupContractAccountSuccess() {
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of(
                    createLinkedAccount(LINKED_ACCOUNT_ID, ConnectionStatus.AVAILABLE)
            ));
            given(linkedBankAccountRepository.getReferenceById(LINKED_ACCOUNT_ID))
                    .willReturn(LinkedBankAccount.builder().linkedAccountId(LINKED_ACCOUNT_ID).build());
            given(loanContractRepository.getReferenceById(CONTRACT_ID))
                    .willReturn(createContract(ContractStatus.PENDING));

            contractAccountService.createContractAccount(CONTRACT_ID, CREDITOR_ID, LINKED_ACCOUNT_ID);

            ArgumentCaptor<ContractAccount> captor = ArgumentCaptor.forClass(ContractAccount.class);
            verify(contractAccountRepository).save(captor.capture());
            ContractAccount saved = captor.getValue();
            assertThat(saved.getAccountStatus()).isEqualTo(ContractAccountStatus.ACTIVE);
            assertThat(saved.getLinkedAccount().getLinkedAccountId()).isEqualTo(LINKED_ACCOUNT_ID);
        }

        @Test
        @DisplayName("연동 계좌 ID가 null이면 아무 것도 하지 않는다")
        void setupContractAccountDoesNothingWhenLinkedAccountIdIsNull() {
            contractAccountService.createContractAccount(CONTRACT_ID, CREDITOR_ID, null);

            verify(linkedBankAccountService, never()).getLinkedAccounts(any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("선택 불가능한 계좌면 예외가 발생하고 등록하지 않는다")
        void setupContractAccountFailsWhenAccountNotSelectable() {
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of(
                    createLinkedAccount(LINKED_ACCOUNT_ID, ConnectionStatus.UNAVAILABLE)
            ));

            assertThatThrownBy(() ->
                    contractAccountService.createContractAccount(CONTRACT_ID, CREDITOR_ID, LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.INVALID_LINKED_ACCOUNT)
                    );

            verify(contractAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("계약 계좌 변경")
    class ChangeContractAccount {

        @Test
        @DisplayName("기존 계좌를 REPLACED 처리하고 새 계좌를 활성 계좌로 등록한다")
        void changeContractAccountSuccess() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of(
                    createLinkedAccount(OTHER_LINKED_ACCOUNT_ID, ConnectionStatus.AVAILABLE)
            ));
            ContractAccount existingActive = createActiveAccount();
            given(contractAccountRepository.findLatestByContractIdAndStatus(CONTRACT_ID, ContractAccountStatus.ACTIVE))
                    .willReturn(Optional.of(existingActive));

            contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID);

            assertThat(existingActive.getAccountStatus()).isEqualTo(ContractAccountStatus.REPLACED);

            ArgumentCaptor<ContractAccount> captor = ArgumentCaptor.forClass(ContractAccount.class);
            verify(contractAccountRepository).save(captor.capture());
            assertThat(captor.getValue().getAccountStatus()).isEqualTo(ContractAccountStatus.ACTIVE);
        }

        @Test
        @DisplayName("계약서를 찾을 수 없으면 예외가 발생하고 변경하지 않는다")
        void changeContractAccountFailsWhenContractNotFound() {
            given(loanContractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("계약 당사자가 아니면 예외가 발생하고 변경하지 않는다")
        void changeContractAccountFailsWhenUserIsNotOwner() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, OTHER_USER_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("계약이 이미 완료 상태면 예외가 발생하고 변경하지 않는다")
        void changeContractAccountFailsWhenContractCompleted() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.COMPLETED)));

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("계약이 SUPERSEDED 상태면 예외가 발생하고 변경하지 않는다")
        void changeContractAccountFailsWhenContractSuperseded() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.SUPERSEDED)));

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("계약이 TERMINATED 상태면 예외가 발생하고 변경하지 않는다")
        void changeContractAccountFailsWhenContractTerminated() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.TERMINATED)));

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("새로 선택한 계좌가 선택 불가능하면 예외가 발생하고 변경하지 않는다")
        void changeContractAccountFailsWhenNewAccountNotSelectable() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of(
                    createLinkedAccount(OTHER_LINKED_ACCOUNT_ID, ConnectionStatus.UNAVAILABLE)
            ));

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.INVALID_LINKED_ACCOUNT)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
            verify(contractAccountRepository, never()).save(any());
        }

        @Test
        @DisplayName("교체 대상 활성 계좌가 없으면 예외가 발생하고 새 계좌를 등록하지 않는다")
        void changeContractAccountFailsWhenNoActiveAccountToReplace() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID)).willReturn(List.of(
                    createLinkedAccount(OTHER_LINKED_ACCOUNT_ID, ConnectionStatus.AVAILABLE)
            ));
            given(contractAccountRepository.findLatestByContractIdAndStatus(CONTRACT_ID, ContractAccountStatus.ACTIVE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    contractAccountService.changeContractAccount(CONTRACT_ID, CREDITOR_ID, OTHER_LINKED_ACCOUNT_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCOUNT_NOT_FOUND)
                    );

            verify(contractAccountRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("계약 계좌 비활성화")
    class deactivateContractAccount {

        @Test
        @DisplayName("계약 소유자가 요청하면 계좌 상태를 DISABLED로 변경한다")
        void deactivateContractAccountSuccess() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));
            ContractAccount existingActive = createActiveAccount();
            given(contractAccountRepository.findLatestByContractIdAndStatus(CONTRACT_ID, ContractAccountStatus.ACTIVE))
                    .willReturn(Optional.of(existingActive));

            contractAccountService.deactivateContractAccount(CONTRACT_ID, CREDITOR_ID);

            assertThat(existingActive.getAccountStatus()).isEqualTo(ContractAccountStatus.DISABLED);
        }

        @Test
        @DisplayName("계약서를 찾을 수 없으면 예외가 발생하고 비활성화하지 않는다")
        void deactivateContractAccountFailsWhenContractNotFound() {
            given(loanContractRepository.findById(CONTRACT_ID)).willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    contractAccountService.deactivateContractAccount(CONTRACT_ID, CREDITOR_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_NOT_FOUND)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("계약 당사자가 아니면 예외가 발생하고 비활성화하지 않는다")
        void deactivateContractAccountFailsWhenUserIsNotOwner() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));

            assertThatThrownBy(() ->
                    contractAccountService.deactivateContractAccount(CONTRACT_ID, OTHER_USER_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("계약이 이미 완료 상태면 예외가 발생하고 비활성화하지 않는다")
        void deactivateContractAccountFailsWhenContractCompleted() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.COMPLETED)));

            assertThatThrownBy(() ->
                    contractAccountService.deactivateContractAccount(CONTRACT_ID, CREDITOR_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("계약이 SUPERSEDED 상태면 예외가 발생하고 비활성화하지 않는다")
        void deactivateContractAccountFailsWhenContractSuperseded() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.SUPERSEDED)));

            assertThatThrownBy(() ->
                    contractAccountService.deactivateContractAccount(CONTRACT_ID, CREDITOR_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("계약이 TERMINATED 상태면 예외가 발생하고 비활성화하지 않는다")
        void deactivateContractAccountFailsWhenContractTerminated() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.TERMINATED)));

            assertThatThrownBy(() ->
                    contractAccountService.deactivateContractAccount(CONTRACT_ID, CREDITOR_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
                    );

            verify(contractAccountRepository, never()).findLatestByContractIdAndStatus(any(), any());
        }

        @Test
        @DisplayName("활성화된 계좌가 없으면 예외가 발생한다")
        void deactivateContractAccountFailsWhenNoActiveAccount() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));
            given(contractAccountRepository.findLatestByContractIdAndStatus(CONTRACT_ID, ContractAccountStatus.ACTIVE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    contractAccountService.deactivateContractAccount(CONTRACT_ID, CREDITOR_ID)
            )
                    .isInstanceOfSatisfying(
                            DomainException.class,
                            exception -> assertThat(exception.getErrorCode())
                                    .isEqualTo(LoanContractErrorCode.CONTRACT_ACCOUNT_NOT_FOUND)
                    );
        }
    }

    @Nested
    class GetCurrentContractAccount {

        @Test
        void returnsActiveLinkedAccountForCreditor() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.COMPLETED)));
            ContractAccount activeAccount = ContractAccount.builder()
                    .linkedAccount(LinkedBankAccount.builder().linkedAccountId(LINKED_ACCOUNT_ID).build())
                    .accountStatus(ContractAccountStatus.ACTIVE)
                    .build();
            given(contractAccountRepository.findLatestByContractIdAndStatus(CONTRACT_ID, ContractAccountStatus.ACTIVE))
                    .willReturn(Optional.of(activeAccount));
            given(linkedBankAccountService.getLinkedAccounts(CREDITOR_ID))
                    .willReturn(List.of(createLinkedAccount(
                            LINKED_ACCOUNT_ID,
                            ConnectionStatus.AVAILABLE
                    )));

            LinkedBankAccountResponse result =
                    contractAccountService.getCurrentAccount(
                            CONTRACT_ID,
                            CREDITOR_ID
                    );

            assertThat(result.linkedAccountId()).isEqualTo(LINKED_ACCOUNT_ID);
        }

        @Test
        void rejectsNonCreditor() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));

            assertThatThrownBy(() -> contractAccountService.getCurrentAccount(
                    CONTRACT_ID,
                    OTHER_USER_ID
            )).isInstanceOfSatisfying(
                    DomainException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(LoanContractErrorCode.CONTRACT_ACCESS_DENIED)
            );

            verify(contractAccountRepository, never())
                    .findLatestByContractIdAndStatus(any(), any());
        }

        @Test
        void rejectsMissingActiveAccount() {
            given(loanContractRepository.findById(CONTRACT_ID))
                    .willReturn(Optional.of(createContract(ContractStatus.PENDING)));
            given(contractAccountRepository.findLatestByContractIdAndStatus(CONTRACT_ID, ContractAccountStatus.ACTIVE))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() -> contractAccountService.getCurrentAccount(
                    CONTRACT_ID,
                    CREDITOR_ID
            )).isInstanceOfSatisfying(
                    DomainException.class,
                    exception -> assertThat(exception.getErrorCode())
                            .isEqualTo(LoanContractErrorCode.CONTRACT_ACCOUNT_NOT_FOUND)
            );
        }
    }

    private LinkedBankAccountResponse createLinkedAccount(Long linkedAccountId, ConnectionStatus status) {
        return LinkedBankAccountResponse.builder()
                .linkedAccountId(linkedAccountId)
                .bankCode("088")
                .bankName("신한은행")
                .maskedAccountNumber("110-***-******")
                .accountAlias("생활비 통장")
                .accountHolderName("김채권")
                .connectionStatus(status.name())
                .build();
    }

    private LoanContract createContract(ContractStatus status) {
        return LoanContract.builder()
                .creditorId(CREDITOR_ID)
                .status(status)
                .build();
    }

    private ContractAccount createActiveAccount() {
        return ContractAccount.builder()
                .accountStatus(ContractAccountStatus.ACTIVE)
                .build();
    }
}
