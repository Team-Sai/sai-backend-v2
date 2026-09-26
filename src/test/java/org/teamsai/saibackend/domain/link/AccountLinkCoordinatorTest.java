package org.teamsai.saibackend.domain.link;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.link.service.*;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.global.client.MockBankClient;
import org.teamsai.saibackend.global.client.BankKeyRecoveryException;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
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
            receipt = new LinkOperationStore.Operation(i.getArgument(0), i.getArgument(1), "ISSUE", null, null, ISSUE_PENDING);
            return null;
        }).when(operations).beginIssue(anyString(), anyLong());
        doAnswer(i -> {
            receipt = new LinkOperationStore.Operation(receipt.id(), receipt.userId(), i.getArgument(2),
                    receipt.previousKey(), i.getArgument(1), ISSUED);
            return null;
        }).when(operations).recordIssued(anyString(), anyString(), anyString());
        doAnswer(i -> {
            receipt = new LinkOperationStore.Operation(receipt.id(), receipt.userId(), receipt.requestHash(),
                    receipt.previousKey(), receipt.newKey(), i.getArgument(1));
            return null;
        }).when(operations).mark(anyString(), any());
    }

    @Test
    void keyRequestNetworkFailureIsTranslatedBeforeConfirm() {
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenThrow(new RestClientException("offline"));
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        verify(bank, never()).confirmUserKey(anyString(), anyString());
        verify(operations, never()).begin(any());
        assertThat(receipt.status()).isEqualTo(ISSUE_PENDING);
        var order = inOrder(operations, bank);
        order.verify(operations).beginIssue(receipt.id(), 1L);
        order.verify(bank).requestUserKey("name", "token", receipt.id());
    }

    @Test
    void existingKeyIsReadFreshWithoutCallingBank() {
        when(users.findUserKeyByUserId(1L)).thenReturn("current");
        assertThat(coordinator.issueOrGetUserKey(1L).userKey()).isEqualTo("current");
        verifyNoInteractions(bank);
    }

    @Test
    void confirmFailureIsNotAssumedToHaveRolledBackAtBank() {
        doThrow(new RestClientException("timeout")).when(bank).confirmUserKey(eq("new"), anyString());
        assertError(() -> coordinator.completeCallback(1L,"state","new",List.of(1L)), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(receipt.status()).isEqualTo(CONFIRM_UNKNOWN);
        verifyNoInteractions(persistence);
        verify(bank, never()).recoverUserKey(anyString(), anyString(), nullable(String.class), anyString());
    }

    @Test
    void commitFailureRecoversOnlyAfterPersistenceReturnsFailure() {
        var failure = new IllegalStateException("commit failed");
        doThrow(failure).when(persistence).completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        assertThatThrownBy(() -> coordinator.completeCallback(1L,"state","new",List.of(1L))).isSameAs(failure);
        var order = inOrder(bank, persistence);
        order.verify(bank).confirmUserKey(eq("new"), anyString());
        order.verify(persistence).completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        order.verify(bank).recoverUserKey(eq("token"), eq("new"), isNull(), anyString());
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void failedRecoveryRemainsPendingInsteadOfBeingReportedAsCompensated() {
        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(accounts.prepareAccountsByIds(1L,"new",List.of(1L))).thenThrow(new IllegalStateException("failed"));
        doThrow(new RestClientException("offline")).when(bank).recoverUserKey(eq("token"), eq("new"), eq("old"), anyString());
        assertError(() -> coordinator.completeCallback(1L,"state","new",List.of(1L)), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
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
        verify(bank,times(1)).confirmUserKey(eq("new"), anyString());
        verify(bank,never()).recoverUserKey(anyString(), anyString(), nullable(String.class), anyString());
    }

    @Test
    void blankIssuedKeyIsRejected() {
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenReturn(" ");
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.INVALID_BANK_RESPONSE);
        verify(bank,never()).confirmUserKey(anyString(), anyString());
    }

    @Test
    void keyIssueLocalSaveFailureRecoversAndPreservesDomainError() {
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenReturn("new");
        doThrow(new IllegalStateException("commit failed")).when(persistence)
                .completeLink(eq(1L),eq("new"),isNull(),anyList(),anyString());
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.LOCAL_KEY_SAVE_FAILED);
        verify(bank).recoverUserKey(eq("token"), eq("new"), isNull(), anyString());
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void unknownConfirmIsCancelledOnMatchingCallbackReplay() {
        doThrow(new RestClientException("timeout")).when(bank).confirmUserKey(eq("new"), anyString());
        assertError(() -> coordinator.completeCallback(1L, "state", "new", List.of(1L)),
                AccountErrorCode.BANK_SERVER_UNAVAILABLE);

        assertError(() -> coordinator.completeCallback(1L, "state", "new", List.of(1L)),
                AccountErrorCode.LINK_REQUEST_CONFLICT);

        verify(bank).recoverUserKey(eq("token"), eq("new"), isNull(), anyString());
        assertThat(receipt.status()).isEqualTo(FAILED);
        verifyNoInteractions(persistence);
        verify(bank, times(1)).confirmUserKey(eq("new"), anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = LinkOperationStore.Status.class,
            names = {"CONFIRM_UNKNOWN", "COMPENSATION_PENDING"})
    void recoveryDoesNotNeedOriginalStateAndRestoresPreviousKey(LinkOperationStore.Status status) {
        receipt = new LinkOperationStore.Operation("old-state", 1L, "hash", "old", "new", status);
        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(operations.findUnresolved(1L)).thenReturn(List.of(receipt));

        coordinator.recoverUnresolved(1L);

        var order = inOrder(bank, operations);
        order.verify(bank).recoverUserKey(eq("token"), eq("new"), eq("old"), anyString());
        order.verify(operations).mark("old-state", FAILED);
        assertThat(receipt.status()).isEqualTo(FAILED);
    }

    @Test
    void processingRecoveryRestoresBankBeforeMarkingFailed() {
        receipt = new LinkOperationStore.Operation(
                "id", 1L, "hash", "old", "new", PROCESSING
        );

        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(operations.findUnresolved(1L))
                .thenReturn(List.of(receipt));
        when(operations.hasUnresolved(1L))
                .thenAnswer(invocation -> receipt.status() != FAILED);

        coordinator.recoverUnresolved(1L);

        var order = inOrder(bank, operations);
        order.verify(bank).recoverUserKey(
                "token", "new", "old", "id"
        );
        order.verify(operations).mark("id", FAILED);

        assertThat(receipt.status()).isEqualTo(FAILED);
        verifyNoInteractions(persistence);
    }

    @Test
    void failedConfirmIntentWriteCanBeRecovered() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(operations)
                .mark(anyString(), eq(CONFIRM_UNKNOWN));

        assertThatThrownBy(() ->
                coordinator.completeCallback(
                        1L, "state", "new", List.of(1L)
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(receipt.status()).isEqualTo(PROCESSING);
        verifyNoInteractions(bank, persistence);

        String operationId = receipt.id();

        when(operations.findUnresolved(1L))
                .thenReturn(List.of(receipt));
        when(operations.hasUnresolved(1L))
                .thenAnswer(invocation -> receipt.status() != FAILED);

        coordinator.recoverUnresolved(1L);

        verify(bank).recoverUserKey(
                "token", "new", null, operationId
        );
        assertThat(receipt.status()).isEqualTo(FAILED);
        verifyNoInteractions(persistence);
    }

    @Test
    void processingRecoveryCanBeRetriedAfterNetworkFailure() {
        receipt = new LinkOperationStore.Operation(
                "id", 1L, "hash", "old", "new", PROCESSING
        );

        when(users.findUserKeyByUserId(1L)).thenReturn("old");
        when(operations.findUnresolved(1L))
                .thenReturn(List.of(receipt));
        when(operations.hasUnresolved(1L))
                .thenAnswer(invocation -> receipt.status() != FAILED);

        doThrow(new RestClientException("temporary network failure"))
                .doNothing()
                .when(bank)
                .recoverUserKey("token", "new", "old", "id");

        assertError(
                () -> coordinator.recoverUnresolved(1L),
                AccountErrorCode.BANK_SERVER_UNAVAILABLE
        );

        assertThat(receipt.status()).isEqualTo(PROCESSING);
        verify(operations, never()).mark("id", FAILED);

        // 두 번째 은행 호출은 성공한다.
        coordinator.recoverUnresolved(1L);

        assertThat(receipt.status()).isEqualTo(FAILED);
        verify(bank, times(2))
                .recoverUserKey("token", "new", "old", "id");
    }

    @Test
    void confirmIntentIsPersistedBeforeExternalCall() {
        coordinator.completeCallback(1L, "state", "new", List.of(1L));
        var order = inOrder(operations, bank);
        order.verify(operations).begin(any());
        order.verify(operations).mark(anyString(), eq(CONFIRM_UNKNOWN));
        order.verify(bank).confirmUserKey(eq("new"), anyString());
    }

    @Test
    void failedConfirmIntentWritePreventsExternalCall() {
        doThrow(new IllegalStateException("database unavailable"))
                .when(operations).mark(anyString(), eq(CONFIRM_UNKNOWN));
        assertThatThrownBy(() -> coordinator.completeCallback(1L, "state", "new", List.of(1L)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bank, persistence);
    }

    @Test
    void expiredBankRecoveryKeepsOperationAndBlocksNewIssuance() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", COMPENSATION_PENDING);
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doThrow(new BankKeyRecoveryException(BankKeyRecoveryException.Reason.EXPIRED, null))
                .when(bank).recoverUserKey(eq("token"), eq("new"), isNull(), anyString());
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.LINK_RECOVERY_EXPIRED);
        assertThat(receipt.status()).isEqualTo(RECOVERY_EXPIRED);
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.LINK_RECOVERY_EXPIRED);
        verify(bank, times(1)).recoverUserKey("token", "new", null, "id");
        verify(bank, never()).requestUserKey(anyString(), anyString(), anyString());
    }

    @Test
    void failedRecoveryRemainsUnresolvedAndCanBeRetried() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doThrow(new RestClientException("response lost")).doNothing()
                .when(bank).recoverUserKey(eq("token"), eq("new"), isNull(), anyString());

        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(receipt.status()).isEqualTo(CONFIRM_UNKNOWN);
        verify(operations, never()).mark(anyString(), any());

        coordinator.recoverUnresolved(1L);
        assertThat(receipt.status()).isEqualTo(FAILED);
        verify(bank, times(2)).recoverUserKey("token", "new", null, "id");
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
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenReturn("replacement");

        assertThat(coordinator.issueOrGetUserKey(1L).userKey()).isEqualTo("replacement");

        var order = inOrder(bank);
        order.verify(bank).recoverUserKey(eq("token"), eq("new"), isNull(), anyString());
        order.verify(bank).requestUserKey(eq("name"), eq("token"), anyString());
        order.verify(bank).confirmUserKey(eq("replacement"), anyString());
    }

    private void assertError(Runnable action, AccountErrorCode code) {
        assertThatThrownBy(action::run).extracting("errorCode").isEqualTo(code);
    }

    @Test
    void conflictingRotationStopsAutomaticRecovery() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doThrow(new BankKeyRecoveryException(BankKeyRecoveryException.Reason.CONFLICT, null))
                .when(bank).recoverUserKey("token", "new", null, "id");
        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.LINK_RECOVERY_CONFLICT);
        assertThat(receipt.status()).isEqualTo(RECOVERY_CONFLICT);
        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.LINK_RECOVERY_CONFLICT);
        verify(bank, times(1)).recoverUserKey("token", "new", null, "id");
    }

    @Test
    void directIssuanceUsesTheSameOperationForIssueConfirmAndRecovery() {
        var id = org.mockito.ArgumentCaptor.forClass(String.class);
        when(bank.requestUserKey(eq("name"), eq("token"), id.capture())).thenReturn("new");
        doThrow(new IllegalStateException("commit failed")).when(persistence)
                .completeLink(eq(1L), eq("new"), isNull(), anyList(), anyString());
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.LOCAL_KEY_SAVE_FAILED);
        assertThat(receipt.id()).isEqualTo(id.getValue());
        verify(bank).confirmUserKey("new", id.getValue());
        verify(bank).recoverUserKey("token", "new", null, id.getValue());
    }

    @Test
    void callbackPassesHashOfStateAsRotationOperation() throws Exception {
        String expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest("state".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        coordinator.completeCallback(1L, "state", "new", List.of(1L));
        verify(bank).confirmUserKey("new", expected);
        assertThat(receipt.id()).isEqualTo(expected);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {408, 429, 500, 503})
    void transientHttpFailureRetainsRetryableOperation(int status) {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doThrow(new org.springframework.web.client.RestClientResponseException(
                "bank failure", status, "failure", null, null, null))
                .when(bank).recoverUserKey("token", "new", null, "id");
        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(receipt.status()).isEqualTo(CONFIRM_UNKNOWN);
        verify(operations, never()).mark(anyString(), any());
    }

    @Test
    void nonRetryableHttpFailureRequiresReconciliation() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", CONFIRM_UNKNOWN);
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doThrow(new org.springframework.web.client.RestClientResponseException(
                "invalid request", 400, "bad request", null, null, null))
                .when(bank).recoverUserKey("token", "new", null, "id");
        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        assertThat(receipt.status()).isEqualTo(RECONCILIATION_REQUIRED);
        assertError(() -> coordinator.recoverUnresolved(1L), AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        verify(bank, times(1)).recoverUserKey("token", "new", null, "id");
    }

    @Test
    void lostIssuanceResponseResumesThePersistedId() {
        when(bank.requestUserKey(eq("name"), eq("token"), anyString()))
                .thenThrow(new RestClientException("response lost")).thenReturn("new");
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        String originalId = receipt.id();
        assertThat(receipt.newKey()).isNull();
        when(operations.findUnresolved(1L)).thenAnswer(i -> List.of(receipt));
        doAnswer(i -> {
            when(users.findUserKeyByUserId(1L)).thenReturn("new");
            receipt = new LinkOperationStore.Operation(receipt.id(), 1L, receipt.requestHash(), null, "new", COMPLETED);
            return null;
        }).when(persistence).completeLink(eq(1L), eq("new"), isNull(), anyList(), eq(originalId));

        assertThat(coordinator.issueOrGetUserKey(1L).userKey()).isEqualTo("new");
        verify(bank, times(2)).requestUserKey("name", "token", originalId);
        verify(operations, times(1)).beginIssue(originalId, 1L);
        verify(bank).confirmUserKey("new", originalId);
        assertThat(receipt.status()).isEqualTo(COMPLETED);
    }

    @Test
    void restartAfterIssuedKeyWasSavedSkipsIssuance() {
        receipt = new LinkOperationStore.Operation("id", 1L, "hash", null, "new", ISSUED);
        when(operations.findUnresolved(1L)).thenReturn(List.of(receipt));
        coordinator.recoverUnresolved(1L);
        verify(bank, never()).requestUserKey(anyString(), anyString(), anyString());
        verify(bank).confirmUserKey("new", "id");
        verify(persistence).completeLink(1L, "new", null, List.of(), "id");
    }

    @Test
    void failingIssuanceIntentWritePreventsBankCall() {
        doThrow(new IllegalStateException("database unavailable")).when(operations).beginIssue(anyString(), anyLong());
        assertThatThrownBy(() -> coordinator.issueOrGetUserKey(1L)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(bank);
    }

    @Test
    void failedIssuedKeyWriteIsResumedWithSameIdWithoutConfirming() {
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenReturn("new");
        doThrow(new IllegalStateException("database unavailable"))
                .when(operations).recordIssued(anyString(), anyString(), anyString());
        assertThatThrownBy(() -> coordinator.issueOrGetUserKey(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(receipt.status()).isEqualTo(ISSUE_PENDING);
        verify(bank, never()).confirmUserKey(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void transientIssuanceHttpFailureRetriesWithOriginalOperationId(int status) {
        assertIssuanceRemainsRetryable(issuanceHttpFailure(status, "{}"));
    }

    @Test
    void issuanceTimeoutRetriesWithOriginalOperationId() {
        assertIssuanceRemainsRetryable(new ResourceAccessException("response lost",
                new java.net.SocketTimeoutException("timeout")));
    }

    @Test
    void existingPendingKeyIsRetryableDespite409() {
        assertIssuanceRemainsRetryable(issuanceHttpFailure(409,
                "{\"status\":409,\"code\":\"PENDING_KEY_ALREADY_EXISTS\",\"message\":\"pending\"}"));
    }

    @Test
    void issuanceRotationConflictStopsSubsequentBankCalls() {
        assertIssuanceStopsRetrying(issuanceHttpFailure(409,
                        "{\"status\":409,\"code\":\"KEY_RECOVERY_CONFLICT\",\"message\":\"conflict\"}"),
                RECOVERY_CONFLICT, AccountErrorCode.LINK_RECOVERY_CONFLICT);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 422})
    void definitiveIssuanceRejectionRequiresReconciliation(int status) {
        assertIssuanceStopsRetrying(issuanceHttpFailure(status, "{}"),
                RECONCILIATION_REQUIRED, AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json", "{}", "{\"code\":\"UNKNOWN_CONFLICT\"}"})
    void unrecognizedIssuance409DoesNotBecomeAnAutomaticRetry(String body) {
        assertIssuanceStopsRetrying(issuanceHttpFailure(409, body),
                RECONCILIATION_REQUIRED, AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
    }

    @Test
    void pendingErrorCodeDoesNotOverrideNonRetryableHttpStatus() {
        assertIssuanceStopsRetrying(issuanceHttpFailure(403,
                        "{\"code\":\"PENDING_KEY_ALREADY_EXISTS\"}"),
                RECONCILIATION_REQUIRED, AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
    }

    private void assertIssuanceRemainsRetryable(RestClientException failure) {
        when(operations.findUnresolved(1L)).thenAnswer(i -> receipt == null ? List.of() : List.of(receipt));
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenThrow(failure);

        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        String originalId = receipt.id();
        assertThat(receipt.status()).isEqualTo(ISSUE_PENDING);
        assertError(() -> coordinator.issueOrGetUserKey(1L), AccountErrorCode.BANK_SERVER_UNAVAILABLE);

        assertThat(receipt.id()).isEqualTo(originalId);
        assertThat(receipt.status()).isEqualTo(ISSUE_PENDING);
        verify(bank, times(2)).requestUserKey("name", "token", originalId);
        verify(operations, times(1)).beginIssue(originalId, 1L);
        verify(operations, never()).mark(anyString(), any());
        verify(operations, never()).recordIssued(anyString(), anyString(), anyString());
        verify(bank, never()).confirmUserKey(anyString(), anyString());
        verifyNoInteractions(persistence);
    }

    private void assertIssuanceStopsRetrying(RestClientResponseException failure,
                                           LinkOperationStore.Status expectedStatus, AccountErrorCode expectedCode) {
        when(operations.findUnresolved(1L)).thenAnswer(i -> receipt == null ? List.of() : List.of(receipt));
        when(bank.requestUserKey(eq("name"), eq("token"), anyString())).thenThrow(failure);

        assertError(() -> coordinator.issueOrGetUserKey(1L), expectedCode);
        String originalId = receipt.id();
        assertThat(receipt.status()).isEqualTo(expectedStatus);
        assertError(() -> coordinator.issueOrGetUserKey(1L), expectedCode);

        assertThat(receipt.id()).isEqualTo(originalId);
        assertThat(receipt.status()).isEqualTo(expectedStatus);
        verify(bank, times(1)).requestUserKey("name", "token", originalId);
        verify(operations, times(1)).beginIssue(originalId, 1L);
        verify(operations, times(1)).mark(originalId, expectedStatus);
        verify(operations, never()).recordIssued(anyString(), anyString(), anyString());
        verify(bank, never()).confirmUserKey(anyString(), anyString());
        verifyNoInteractions(persistence);
    }

    private RestClientResponseException issuanceHttpFailure(int status, String body) {
        // Use a real RestClient error so getResponseBodyAs() exercises the JSON decoder.
        // MockRestServiceServer handles this request entirely in memory.
        var builder = RestClient.builder().baseUrl("https://bank.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://bank.test/api/mock-bank/link"))
                .andRespond(withStatus(HttpStatusCode.valueOf(status))
                        .contentType(MediaType.APPLICATION_JSON).body(body));
        var failure = assertThrows(RestClientResponseException.class, () -> builder.build().post()
                .uri("/api/mock-bank/link").retrieve().toBodilessEntity());
        server.verify();
        return failure;
    }
}
