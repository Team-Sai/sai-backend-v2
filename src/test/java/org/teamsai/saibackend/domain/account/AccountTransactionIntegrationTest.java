package org.teamsai.saibackend.domain.account;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClientException;
import org.teamsai.saibackend.domain.account.dto.request.LinkAccountRequest;
import org.teamsai.saibackend.domain.account.dto.response.AccountDetailResponse;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.account.exception.AccountErrorCode;
import org.teamsai.saibackend.domain.account.repository.LinkedBankAccountRepository;
import org.teamsai.saibackend.domain.account.service.*;
import org.teamsai.saibackend.domain.link.service.*;
import org.teamsai.saibackend.domain.transaction.dto.response.BankTransactionResponse;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionPersistenceService;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.client.MockBankClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "SAI_ACCOUNT_DB_TEST", matches = "true")
@SpringBootTest(classes = AccountTransactionIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=${SAI_ACCOUNT_TEST_DB_URL:jdbc:mariadb://localhost:13316/account_test}",
        "spring.datasource.username=${SAI_ACCOUNT_TEST_DB_USER:root}",
        "spring.datasource.password=${SAI_ACCOUNT_TEST_DB_PASSWORD:account-test-only}",
        "spring.datasource.hikari.maximum-pool-size=10",
        "account-link.max-concurrent=3",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/user.sql,classpath:db/linked_bank_account.sql,classpath:db/account_link_operation.sql,classpath:db/bank_transaction.sql",
        "spring.jpa.hibernate.ddl-auto=none", "spring.jpa.open-in-view=false",
        "spring.batch.job.enabled=false", "spring.batch.jdbc.initialize-schema=never"})
@ActiveProfiles("account-transaction-test")
class AccountTransactionIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @Profile("account-transaction-test")
    @EnableAutoConfiguration
    @EntityScan("org.teamsai.saibackend.domain")
    @EnableJpaRepositories(basePackageClasses = {UserRepository.class,
            LinkedBankAccountRepository.class, BankTransactionRepository.class})
    @Import({AccountLinkCoordinator.class, AccountLinkService.class, LinkOperationStore.class,
            UserLinkLock.class, LinkedAccountWriter.class, LinkedBankAccountService.class,
            UserService.class, AccountService.class, BankTransactionPersistenceService.class})
    static class Config {}

    @Autowired UserRepository users;
    @Autowired LinkedBankAccountRepository accounts;
    @Autowired AccountLinkCoordinator coordinator;
    @Autowired AccountService accountService;
    @Autowired LinkedBankAccountService accountLinks;
    @Autowired LinkedAccountWriter writer;
    @Autowired AccountLinkService persistence;
    @Autowired LinkOperationStore operations;
    @Autowired UserLinkLock lock;
    @Autowired BankTransactionPersistenceService transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserService userService;
    @MockitoBean MockBankClient bank;
    @org.springframework.beans.factory.annotation.Value("${account-link.max-concurrent}")
    int maxConcurrentLinks;
    Long userId;
    String state;
    String key;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = LinkOperationStore.Status.class,
            names = {"COMPLETED", "FAILED"})
    void withdrawalPreservesFinishedOperation(LinkOperationStore.Status status) {
        operations.begin(new LinkOperationStore.Operation(state, userId, "request-hash", null, key,
                LinkOperationStore.Status.PROCESSING));
        operations.mark(state, status);

        userService.withdraw(userId);

        assertThat(users.existsById(userId)).isFalse();
        assertThat(operations.find(state)).hasValueSatisfying(operation -> {
            assertThat(operation.userId()).isEqualTo(userId);
            assertThat(operation.status()).isEqualTo(status);
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = LinkOperationStore.Status.class,
            names = {"ISSUE_PENDING", "ISSUED", "PROCESSING", "CONFIRM_UNKNOWN", "COMPENSATION_PENDING",
                    "RECOVERY_EXPIRED", "RECOVERY_CONFLICT", "RECONCILIATION_REQUIRED"})
    void unresolvedOperationBlocksWithdrawal(LinkOperationStore.Status status) {
        operations.begin(new LinkOperationStore.Operation(state, userId, "request-hash", null, key,
                LinkOperationStore.Status.PROCESSING));
        operations.mark(state, status);

        assertError(() -> userService.withdraw(userId), AccountErrorCode.LINK_RECONCILIATION_REQUIRED);

        assertThat(users.existsById(userId)).isTrue();
        assertThat(operations.find(state)).hasValueSatisfying(operation ->
                assertThat(operation.status()).isEqualTo(status));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(org.teamsai.saibackend.global.client.BankKeyRecoveryException.Reason.class)
    void definitiveRecoveryFailureIsPersistedAndBlocksNewIssue(
            org.teamsai.saibackend.global.client.BankKeyRecoveryException.Reason reason) {
        operations.begin(new LinkOperationStore.Operation(state, userId, "request-hash", null, key,
                LinkOperationStore.Status.PROCESSING));
        operations.mark(state, LinkOperationStore.Status.CONFIRM_UNKNOWN);
        doThrow(new org.teamsai.saibackend.global.client.BankKeyRecoveryException(reason, null))
                .when(bank).recoverUserKey(state, key, null, state);
        boolean expired = reason == org.teamsai.saibackend.global.client.BankKeyRecoveryException.Reason.EXPIRED;
        var expected = expired ? AccountErrorCode.LINK_RECOVERY_EXPIRED : AccountErrorCode.LINK_RECOVERY_CONFLICT;
        assertError(() -> coordinator.recoverUnresolved(userId), expected);
        assertThat(operations.find(state).orElseThrow().status()).isEqualTo(expired
                ? LinkOperationStore.Status.RECOVERY_EXPIRED : LinkOperationStore.Status.RECOVERY_CONFLICT);
        assertThat(operations.hasUnresolved(userId)).isTrue();
        assertError(() -> coordinator.issueOrGetUserKey(userId), expected);
        verify(bank, times(1)).recoverUserKey(state, key, null, state);
        verify(bank, never()).requestUserKey(anyString(), anyString(), anyString());
    }

    @BeforeEach void fixture() {
        state = UUID.randomUUID().toString();
        key = "key-" + state;
        userId = users.saveAndFlush(User.builder().userToken(state).email(state + "@account.test")
                .password("test-only").name("Account Test").birthDate(LocalDate.of(2000,1,1))
                .build()).getUserId();
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM bank_transaction WHERE linked_account_id IN (SELECT linked_account_id FROM linked_bank_account WHERE user_id=?)", userId);
        jdbc.update("DELETE FROM linked_bank_account WHERE user_id=?", userId);
        jdbc.update("DELETE FROM account_link_operation WHERE user_id=?", userId);
        jdbc.update("DELETE FROM users WHERE user_id=?", userId);
    }

    @Test void callbackCommitsAndReplayDoesNotCallBankAgain() {
        when(bank.getAccountDetail(1L,key)).thenAnswer(i -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return detail(1L);
        });
        coordinator.completeCallback(userId,state,key,List.of(1L,1L));
        coordinator.completeCallback(userId,state,key,List.of(1L));
        assertThat(users.findUserKeyByUserId(userId)).isEqualTo(key);
        assertThat(accounts.findAllByUserId(userId)).hasSize(1);
        assertThat(status()).isEqualTo("COMPLETED");
        verify(bank,times(1)).confirmUserKey(eq(key), anyString());
        verify(bank,times(1)).getAccountDetail(1L,key);
    }

    @Test void replayWithDifferentPayloadIsRejected() {
        when(bank.getAccountDetail(1L,key)).thenReturn(detail(1L));
        coordinator.completeCallback(userId,state,key,List.of(1L));
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(2L)), AccountErrorCode.LINK_REQUEST_CONFLICT);
        verify(bank,times(1)).confirmUserKey(eq(key), anyString());
        verify(bank,never()).getAccountDetail(2L,key);
    }

    @Test void secondInsertFailureRollsBackSelectedAccounts() {
        jdbc.update("UPDATE users SET user_key=? WHERE user_id=?",key,userId);
        when(bank.getAccountDetail(1L,key)).thenReturn(detail(1L));
        when(bank.getAccountDetail(2L,key)).thenReturn(detail(2L));
        var request = new LinkAccountRequest(List.of(new LinkAccountRequest.SelectedAccount(1L,"ok"),
                new LinkAccountRequest.SelectedAccount(2L,"x".repeat(51))));
        assertThatThrownBy(() -> accountLinks.linkSelectedAccounts(userId,request))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(accounts.findAllByUserId(userId)).isEmpty();
    }

    @Test void duplicateDoesNotPoisonTransactionAndNextAccountCommits() {
        writer.insertAll(List.of(candidate(1L,"first")));
        var result=writer.insertAll(List.of(candidate(1L,"ignored"),candidate(2L,"second")));
        assertThat(result).hasSize(1);
        assertThat(accounts.findAllByUserId(userId)).hasSize(2);
        assertThat(accounts.findByUserIdAndAccountId(userId,1L).orElseThrow().getAccountAlias()).isEqualTo("first");
    }

    @Test void simultaneousInsertsStoreExactlyOneAccount() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CyclicBarrier barrier=new CyclicBarrier(2);
        try {
            Callable<Integer> work=() -> {barrier.await(5,TimeUnit.SECONDS); return writer.insertAll(List.of(candidate(1L,"same"))).size();};
            var first=pool.submit(work); var second=pool.submit(work);
            assertThat(first.get(10,TimeUnit.SECONDS)+second.get(10,TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(accounts.findAllByUserId(userId)).hasSize(1);
        } finally {pool.shutdownNow();}
    }

    @Test void callbackWriteFailureRollsBackKeyAccountsAndReceiptThenRecovers() {
        when(bank.getAccountDetail(1L,key)).thenReturn(detail(1L));
        var invalid=new AccountDetailResponse(2L,"088","masked","x".repeat(51),"holder",BigDecimal.TEN,"ACTIVE",null,null);
        when(bank.getAccountDetail(2L,key)).thenReturn(invalid);
        assertThatThrownBy(() -> coordinator.completeCallback(userId,state,key,List.of(1L,2L)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(users.findUserKeyByUserId(userId)).isNull();
        assertThat(accounts.findAllByUserId(userId)).isEmpty();
        assertThat(status()).isEqualTo("FAILED");
        verify(bank).recoverUserKey(eq(state), eq(key), isNull(), anyString());
    }

    @Test void relinkFailureRestoresOriginalKey() {
        String old="old-"+state;
        jdbc.update("UPDATE users SET user_key=? WHERE user_id=?",old,userId);
        when(bank.getAccountDetail(1L,key)).thenThrow(new RestClientException("failed"));
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(users.findUserKeyByUserId(userId)).isEqualTo(old);
        verify(bank).recoverUserKey(eq(state), eq(key), eq(old), anyString());
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test void sameKeyFailureDoesNotReconfirmOrRestoreIt() {
        jdbc.update("UPDATE users SET user_key=? WHERE user_id=?",key,userId);
        when(bank.getAccountDetail(1L,key)).thenThrow(new RestClientException("failed"));
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        verify(bank,never()).confirmUserKey(anyString(), anyString());
        verify(bank,never()).recoverUserKey(anyString(), anyString(), nullable(String.class), anyString());
        verify(bank,never()).revokeUserKey(anyString());
        assertThat(status()).isEqualTo("FAILED");
    }

    @Test void compensationFailureIsDurableAndRetryOnlyCompensates() {
        when(bank.getAccountDetail(1L,key)).thenThrow(new RestClientException("failed"));

        doThrow(new RestClientException("offline")).doThrow(new RestClientException("offline")).doNothing()
                .when(bank).recoverUserKey(eq(state), eq(key), isNull(), anyString());
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(status()).isEqualTo("COMPENSATION_PENDING");
        assertError(() -> accountService.issueOrGetUserKey(userId),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.LINK_REQUEST_CONFLICT);
        assertThat(status()).isEqualTo("FAILED");
        verify(bank,times(1)).confirmUserKey(eq(key), anyString());
        verify(bank,never()).revokeUserKey(anyString());
        verify(bank,times(3)).recoverUserKey(eq(state), eq(key), isNull(), anyString());
    }

    @Test void confirmTimeoutIsRecordedAndBlocksBlindRetries() {
        doThrow(new RestClientException("response lost")).when(bank).confirmUserKey(eq(key), anyString());
        doThrow(new RestClientException("offline")).when(bank).recoverUserKey(eq(state), eq(key), isNull(), anyString());
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertThat(status()).isEqualTo("CONFIRM_UNKNOWN");
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        assertError(() -> coordinator.completeCallback(userId,state+"other",key,List.of(1L)),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        verify(bank,times(1)).confirmUserKey(eq(key), anyString());
        verify(bank,never()).revokeUserKey(anyString());
    }

    @Test void recoveryClosesDurableOperationWithoutOriginalState() {
        operations.begin(new LinkOperationStore.Operation(state, userId, "hash", null, key,
                LinkOperationStore.Status.PROCESSING));
        operations.mark(state, LinkOperationStore.Status.CONFIRM_UNKNOWN);
        coordinator.recoverUnresolved(userId);
        assertThat(operations.hasUnresolved(userId)).isFalse();
        assertThat(status()).isEqualTo("FAILED");
        verify(bank).recoverUserKey(eq(state), eq(key), isNull(), anyString());
    }

    @Test void concurrentCallbackAndKeyIssueCannotChangeSameUser() throws Exception {
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        doAnswer(i -> {entered.countDown(); if(!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return null;})
                .when(bank).confirmUserKey(eq(key), anyString());
        when(bank.getAccountDetail(1L,key)).thenReturn(detail(1L));
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            var first=pool.submit(() -> coordinator.completeCallback(userId,state,key,List.of(1L)));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.LINK_IN_PROGRESS);
            assertError(() -> accountService.issueOrGetUserKey(userId),AccountErrorCode.LINK_IN_PROGRESS);
            var selection = new LinkAccountRequest(List.of(new LinkAccountRequest.SelectedAccount(1L,"alias")));
            assertError(() -> coordinator.linkSelectedAccounts(userId,selection),AccountErrorCode.LINK_IN_PROGRESS);
            release.countDown(); first.get(10,TimeUnit.SECONDS);
            coordinator.completeCallback(userId,state,key,List.of(1L));
            verify(bank,times(1)).confirmUserKey(eq(key), anyString());
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void lockIsReleasedAfterException() {
        assertThatThrownBy(() -> lock.execute(userId,()->{throw new IllegalStateException("test");}))
                .isInstanceOf(IllegalStateException.class);
        assertThat(lock.execute(userId,()->"released")).isEqualTo("released");
    }

    @Test void concurrentUsersCannotExhaustPoolWithLockConnections() throws Exception {
        CountDownLatch entered = new CountDownLatch(maxConcurrentLinks), release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrentLinks);
        List<Future<Integer>> work = new java.util.ArrayList<>();
        try {
            for (long i = 1; i <= maxConcurrentLinks; i++) {
                long lockId = -1000 - i;
                work.add(pool.submit(() -> lock.execute(lockId, () -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return jdbc.queryForObject("SELECT 1", Integer.class);
                })));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertError(() -> lock.execute(userId, () -> "overloaded"), AccountErrorCode.LINK_IN_PROGRESS);
            release.countDown();
            for (var result : work) assertThat(result.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(lock.execute(userId, () -> "available")).isEqualTo("available");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void keyIssueIsIdempotentAndCommitsReceipt() {
        when(bank.requestUserKey(anyString(), anyString(), anyString())).thenReturn(key);
        assertThat(accountService.issueOrGetUserKey(userId)).isEqualTo(accountService.issueOrGetUserKey(userId));
        assertThat(users.findUserKeyByUserId(userId)).isEqualTo(key);
        assertThat(status()).isEqualTo("COMPLETED");
        verify(bank,times(1)).requestUserKey(anyString(), anyString(), anyString());
        verify(bank,times(1)).confirmUserKey(eq(key), anyString());
    }

    @Test void keyIssueNetworkErrorIsTranslated() {
        when(bank.requestUserKey(anyString(), anyString(), anyString())).thenThrow(new RestClientException("offline"));
        assertError(() -> accountService.issueOrGetUserKey(userId),AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        verify(bank,never()).confirmUserKey(anyString(), anyString());
        assertThat(users.findUserKeyByUserId(userId)).isNull();
    }

    @Test void lostIssuanceResponseRetainsIdAndResumesAfterRestart() {
        when(bank.requestUserKey(anyString(), anyString(), anyString()))
                .thenThrow(new RestClientException("response lost")).thenReturn(key);
        assertError(() -> accountService.issueOrGetUserKey(userId), AccountErrorCode.BANK_SERVER_UNAVAILABLE);
        var pending = operations.findUnresolved(userId).get(0);
        assertThat(pending.status()).isEqualTo(LinkOperationStore.Status.ISSUE_PENDING);
        assertThat(pending.newKey()).isNull();
        // Reconstruct the coordinator to exclude any in-memory state from the retry.
        var restarted = new AccountLinkCoordinator(lock, users, accountLinks, persistence, bank, operations);
        assertThat(restarted.issueOrGetUserKey(userId).userKey()).isEqualTo(key);
        verify(bank, times(2)).requestUserKey("Account Test", state, pending.id());
        verify(bank).confirmUserKey(key, pending.id());
        assertThat(operations.find(pending.id()).orElseThrow().status()).isEqualTo(LinkOperationStore.Status.COMPLETED);
        assertThat(users.findUserKeyByUserId(userId)).isEqualTo(key);
    }

    @Test void successfulSameKeyCallbackStillSavesAccountsAndReceipt() {
        jdbc.update("UPDATE users SET user_key=? WHERE user_id=?",key,userId);
        when(bank.getAccountDetail(1L,key)).thenReturn(detail(1L));
        coordinator.completeCallback(userId,state,key,List.of(1L));
        assertThat(accounts.findAllByUserId(userId)).hasSize(1);
        assertThat(status()).isEqualTo("COMPLETED");
        verify(bank,never()).confirmUserKey(anyString(), anyString());
    }

    @Test void missingUserDoesNotCallBank() {
        assertThatThrownBy(() -> accountService.issueOrGetUserKey(-1L))
                .extracting("errorCode").isEqualTo(org.teamsai.saibackend.domain.user.exception.UserErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(bank);
    }

    @Test void compensationNeverRestoresOverAnotherWritersKey() {
        String newer="other-"+state;
        when(bank.getAccountDetail(1L,key)).thenAnswer(i -> {
            jdbc.update("UPDATE users SET user_key=? WHERE user_id=?",newer,userId);
            throw new RestClientException("failed");
        });
        assertError(() -> coordinator.completeCallback(userId,state,key,List.of(1L)),AccountErrorCode.LINK_RECONCILIATION_REQUIRED);
        assertThat(users.findUserKeyByUserId(userId)).isEqualTo(newer);
        assertThat(status()).isEqualTo("COMPENSATION_PENDING");
        verify(bank,never()).revokeUserKey(anyString());
        verify(bank,never()).recoverUserKey(anyString(), anyString(), nullable(String.class), anyString());
    }

    @Test void staleExpectedKeyCannotOverwriteNewKeyOrCompleteReceipt() {
        jdbc.update("UPDATE users SET user_key=? WHERE user_id=?",key,userId);
        String operationId=UUID.randomUUID().toString();
        operations.begin(new LinkOperationStore.Operation(operationId,userId,"hash",null,"new",LinkOperationStore.Status.PROCESSING));
        assertThatThrownBy(() -> persistence.completeLink(userId,"new",null,List.of(candidate(1L,"alias")),operationId))
                .extracting("errorCode").isEqualTo(org.teamsai.saibackend.domain.user.exception.UserErrorCode.LINK_KEY_UPDATE_CONFLICT);
        assertThat(users.findUserKeyByUserId(userId)).isEqualTo(key);
        assertThat(accounts.findAllByUserId(userId)).isEmpty();
        assertThat(status()).isEqualTo("PROCESSING");
    }

    @Test void replayedAndOlderTransactionsCannotRegressBalanceOrCursor() {
        Long id=writer.insertAll(List.of(candidate(1L,"alias"))).get(0).getLinkedAccountId();
        transactions.saveAndAdvanceCursor(id,List.of(tx(20L,BigDecimal.valueOf(200))));
        transactions.saveAndAdvanceCursor(id,List.of(tx(10L,BigDecimal.valueOf(100)),tx(20L,BigDecimal.ONE)));
        var account=accounts.findById(id).orElseThrow();
        assertThat(account.getLastSyncedTransactionId()).isEqualTo(20L);
        assertThat(account.getBalance()).isEqualByComparingTo("200");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bank_transaction WHERE linked_account_id=?",Long.class,id)).isEqualTo(2L);
        transactions.saveAndAdvanceCursor(id,List.of(tx(30L,null)));
        assertThat(accounts.findById(id).orElseThrow().getBalance()).isEqualByComparingTo("200");
        assertThat(accounts.findLastSyncedTransactionIdById(id)).isEqualTo(30L);
    }

    @Test void invalidSecondTransactionRollsBackFirstInsertAndBalance() {
        Long id=writer.insertAll(List.of(candidate(1L,"alias"))).get(0).getLinkedAccountId();
        var invalid=new BankTransactionResponse(2L,"bad",1L,null,BigDecimal.TEN,BigDecimal.ONE,
                "holder","masked","memo",LocalDateTime.now(),1L);
        assertError(() -> transactions.saveAndAdvanceCursor(id,List.of(tx(1L,BigDecimal.ONE),invalid)),AccountErrorCode.INVALID_BANK_RESPONSE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bank_transaction WHERE linked_account_id=?",Long.class,id)).isZero();
        assertThat(accounts.findById(id).orElseThrow().getBalance()).isEqualByComparingTo("10");
        assertThat(accounts.findLastSyncedTransactionIdById(id)).isZero();
    }

    private String status() {return jdbc.queryForObject("SELECT status FROM account_link_operation WHERE user_id=?",String.class,userId);}
    private AccountDetailResponse detail(Long id) {return new AccountDetailResponse(id,"088","masked","name","holder",BigDecimal.TEN,"ACTIVE",null,null);}
    private LinkedBankAccount candidate(Long id,String alias) {return LinkedBankAccount.builder().userId(userId).accountId(id)
            .bankCode("088").accountNumber("masked").accountAlias(alias).accountHolderName("holder").balance(BigDecimal.TEN).build();}
    private BankTransactionResponse tx(Long id,BigDecimal balance) {return new BankTransactionResponse(id,"tx-"+id,1L,"DEPOSIT",
            BigDecimal.TEN,balance,"holder","masked","memo",LocalDateTime.now(),1L);}
    private void assertError(Runnable action,AccountErrorCode code) {assertThatThrownBy(action::run).extracting("errorCode").isEqualTo(code);}
}
