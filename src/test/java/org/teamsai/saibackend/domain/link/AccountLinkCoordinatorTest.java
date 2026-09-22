package org.teamsai.saibackend.domain.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.link.service.*;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.client.MockBankClient;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.teamsai.saibackend.domain.link.service.LinkOperationStore.Status.*;

class AccountLinkCoordinatorTest {
    private final UserLinkLock lock = mock(UserLinkLock.class);
    private final UserRepository users = mock(UserRepository.class);
    private final LinkedBankAccountService accounts = mock(LinkedBankAccountService.class);
    private final AccountLinkService persistence = mock(AccountLinkService.class);
    private final MockBankClient bank = mock(MockBankClient.class);
    private final LinkOperationStore operations = mock(LinkOperationStore.class);
    private final AccountLinkCoordinator coordinator = new AccountLinkCoordinator(
            lock, users, accounts, persistence, bank, operations);
    private LinkOperationStore.Operation receipt;

    @BeforeEach
    void setUp() {
        when(lock.execute(anyLong(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(users.findById(1L)).thenReturn(Optional.of(User.builder().name("name").userToken("token").build()));
        when(operations.find(anyString())).thenAnswer(i -> Optional.ofNullable(receipt));
        doAnswer(i -> { receipt = i.getArgument(0); return null; }).when(operations).begin(any());
        doAnswer(i -> {
            receipt = new LinkOperationStore.Operation(receipt.id(), receipt.userId(), receipt.requestHash(),
                    receipt.previousKey(), receipt.newKey(), i.getArgument(1));
            return null;
        }).when(operations).mark(anyString(), any());
    }

    @Test
    void keyRequestNetworkFailureIsTranslatedBeforeConfirm() {
        when(bank.requestUserKey("name", "token")).thenThrow(new RestClientException("offline"));
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        verify(bank, never()).confirmUserKey(anyString());
        verify(operations, never()).begin(any());
    }

    @Test
    void existingKeyIsReadFreshWithoutCallingBank() {
        when(users.findUserKeyByUserId(1L)).thenReturn("current");
        assertThat(coordinator.issueOrGetUserKey(1L).userKey()).isEqualTo("current");
        verifyNoInteractions(bank);
    }

    @Test
    void confirmFailureIsNotAssumedToHaveRolledBackAtBank() {
        doThrow(new RestClientException("timeout")).when(bank).confirmUserKey("new");
        assertError(() -> coordinator.completeCallback(1L,"state","new",List.of(1L)), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(receipt.status()).isEqualTo(CONFIRM_UNKNOWN);
        verifyNoInteractions(persistence);
        verify(bank, never()).recoverUserKey(anyString(), anyString(), nullable(String.class));
    }

    @Test
    void commitFailureRecoversOnlyAfterPersistenceReturnsFailure() {
        var failure = new IllegalStateException("commit failed");
        doThrow(failure).when(persistence).completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        assertThatThrownBy(() -> coordinator.completeCallback(1L,"state","new",List.of(1L))).isSameAs(failure);
        var order = inOrder(bank, persistence);
        order.verify(bank).confirmUserKey("new");
        order.verify(persistence).completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        order.verify(bank).recoverUserKey("token", "new", null);
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void failedRecoveryRemainsPendingInsteadOfBeingReportedAsCompensated() {
        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(accounts.prepareAccountsByIds(1L,"new",List.of(1L))).thenThrow(new IllegalStateException("failed"));
        doThrow(new RestClientException("offline")).when(bank).recoverUserKey("token", "new", "old");
        assertError(() -> coordinator.completeCallback(1L,"state","new",List.of(1L)), AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        assertThat(receipt.previousKey()).isEqualTo("old");
        assertThat(receipt.status()).isEqualTo(COMPENSATION_PENDING);
    }

    @Test
    void successfulCommitReceiptPreventsCompensationOnAmbiguousCommitError() {
        doAnswer(i -> {
            receipt = new LinkOperationStore.Operation(receipt.id(), receipt.userId(), receipt.requestHash(),
                    receipt.previousKey(), receipt.newKey(), COMPLETED);
            throw new IllegalStateException("commit response lost");
        }).when(persistence).completeLink(anyLong(),anyString(),isNull(),anyList(),anyString());
        coordinator.completeCallback(1L,"state","new",List.of(2L,1L,2L));
        coordinator.completeCallback(1L,"state","new",List.of(1L,2L));
        verify(accounts).prepareAccountsByIds(1L, "new", List.of(1L,2L));
        verify(bank,times(1)).confirmUserKey("new");
        verify(bank,never()).recoverUserKey(anyString(), anyString(), nullable(String.class));
    }

    @Test
    void blankIssuedKeyIsRejected() {
        when(bank.requestUserKey("name","token")).thenReturn(" ");
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.INVALID_BANK_RESPONSE);
        verify(bank,never()).confirmUserKey(anyString());
    }

    @Test
    void keyIssueLocalSaveFailureRecoversAndPreservesDomainError() {
        when(bank.requestUserKey("name","token")).thenReturn("new");
        doThrow(new IllegalStateException("commit failed")).when(persistence)
                .completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.LOCAL_KEY_SAVE_FAILED);
        verify(bank).recoverUserKey("token", "new", null);
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void unknownConfirmIsCancelledOnMatchingCallbackReplay() {
        doThrow(new RestClientException("timeout")).when(bank).confirmUserKey("new");
        assertError(() -> coordinator.completeCallback(1L, "state", "new", List.of(1L)),
                AccountErrorCode.BANK_SERVER_UNAVAILABLE);

        assertError(() -> coordinator.completeCallback(1L, "state", "new", List.of(1L)),
                AccountErrorCode.LINK_REQUEST_CONFLICT);

        verify(bank).recoverUserKey("token", "new", null);
        assertThat(receipt.status()).isEqualTo(FAILED);
        verifyNoInteractions(persistence);
        verify(bank, times(1)).confirmUserKey("new");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = LinkOperationStore.Status.class,
            names = {"PROCESSING", "CONFIRM_UNKNOWN", "COMPENSATION_PENDING"})
    void recoveryDoesNotNeedOriginalStateAndRestoresPreviousKey(LinkOperationStore.Status status) {
        receipt = new LinkOperationStore.Operation("old-state", 1L, "hash", "old", "new", status);
        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(operations.findUnresolved(1L)).thenReturn(List.of(receipt));

        coordinator.recoverUnresolved(1L);

        var order = inOrder(bank, operations);
        order.verify(bank).recoverUserKey("token", "new", "old");
        order.verify(operations).mark("old-state", FAILED);
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void failedRecoveryRemainsUnresolvedAndCanBeRetried() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doThrow(new RestClientException("response lost")).doNothing()
                .when(bank).recoverUserKey("token", "new", null);

        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        assertThat(receipt.status()).isEqualTo(CONFIRM_UNKNOWN);
        verify(operations, never()).mark(anyString(), any());

        coordinator.recoverUnresolved(1L);
        assertThat(receipt.status()).isEqualTo(FAILED);
        verify(bank, times(2)).recoverUserKey("token", "new", null);
    }

    @Test
    void recoveryNeverOverwritesAnUnexpectedLocalKey() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", "old", "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenReturn(List.of(receipt));
        when(users.findUserKeyByUserId(1L)).thenReturn("different");
        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        verifyNoInteractions(bank);
        verify(operations, never()).mark(anyString(), any());
    }

    @Test
    void keyIssueRecoversOldOperationBeforeRequestingAnotherKey() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenReturn(List.of(receipt));
        when(bank.requestUserKey("name", "token")).thenReturn("replacement");

        assertThat(coordinator.issueOrGetUserKey(1L).userKey()).isEqualTo("replacement");

        var order = inOrder(bank);
        order.verify(bank).recoverUserKey("token", "new", null);
        order.verify(bank).requestUserKey("name", "token");
        order.verify(bank).confirmUserKey("replacement");
    }

    private void assertError(Runnable action, AccountErrorCode code) {
        assertThatThrownBy(action::run).extracting("errorCode").isEqualTo(code);
    }
}
