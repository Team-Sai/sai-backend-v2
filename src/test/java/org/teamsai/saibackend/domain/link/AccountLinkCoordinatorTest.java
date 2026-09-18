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
        verify(bank, never()).revokeUserKey(anyString());
    }

    @Test
    void commitFailureRevokesOnlyAfterPersistenceReturnsFailure() {
        var failure = new IllegalStateException("commit failed");
        doThrow(failure).when(persistence).completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        assertThatThrownBy(() -> coordinator.completeCallback(1L,"state","new",List.of(1L))).isSameAs(failure);
        var order = inOrder(bank, persistence);
        order.verify(bank).confirmUserKey("new");
        order.verify(persistence).completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        order.verify(bank).revokeUserKey("new");
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void failedRestoreRemainsPendingInsteadOfBeingReportedAsCompensated() {
        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(accounts.prepareAccountsByIds(1L,"new",List.of(1L))).thenThrow(new IllegalStateException("failed"));
        doThrow(new RestClientException("offline")).when(bank).restoreUserKey("new","old");
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
        coordinator.completeCallback(1L,"state","new",List.of(1L));
        coordinator.completeCallback(1L,"state","new",List.of(1L));
        verify(bank,times(1)).confirmUserKey("new");
        verify(bank,never()).revokeUserKey(anyString());
    }

    @Test
    void blankIssuedKeyIsRejected() {
        when(bank.requestUserKey("name","token")).thenReturn(" ");
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.INVALID_BANK_RESPONSE);
        verify(bank,never()).confirmUserKey(anyString());
    }

    @Test
    void keyIssueLocalSaveFailureRevokesAndPreservesDomainError() {
        when(bank.requestUserKey("name","token")).thenReturn("new");
        doThrow(new IllegalStateException("commit failed")).when(persistence)
                .completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.LOCAL_KEY_SAVE_FAILED);
        verify(bank).revokeUserKey("new");
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    private void assertError(Runnable action, AccountErrorCode code) {
        assertThatThrownBy(action::run).extracting("errorCode").isEqualTo(code);
    }
}
