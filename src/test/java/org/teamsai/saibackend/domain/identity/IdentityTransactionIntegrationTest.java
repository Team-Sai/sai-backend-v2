package org.teamsai.saibackend.domain.identity;

import jakarta.persistence.EntityManager;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.teamsai.saibackend.domain.link.service.UserLinkLock;
import org.teamsai.saibackend.domain.link.service.LinkOperationStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.service.ContractAccountService;
import org.teamsai.saibackend.domain.contract.service.LoanContractFileService;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.identity.dto.request.IdentityPrepareRequest;
import org.teamsai.saibackend.domain.identity.dto.response.PortOneIdentityResponse;
import org.teamsai.saibackend.domain.identity.entity.Identity;
import org.teamsai.saibackend.domain.identity.exception.IdentityErrorCode;
import org.teamsai.saibackend.domain.identity.repository.IdentityRepository;
import org.teamsai.saibackend.domain.identity.service.IdentityService;
import org.teamsai.saibackend.domain.identity.service.IdentityStatusService;
import org.teamsai.saibackend.domain.identity.service.PortOneIdentityService;
import org.teamsai.saibackend.domain.identity.support.IdentityFailureReasonFormatter;
import org.teamsai.saibackend.domain.identity.support.IdentityValidator;
import org.teamsai.saibackend.domain.identity.type.IdentityPurpose;
import org.teamsai.saibackend.domain.identity.type.IdentityStatus;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.user.dto.response.UserResponse;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.domain.user.repository.UserRepository;
import org.teamsai.saibackend.domain.user.service.UserService;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Uses the development MariaDB schema without DDL or application component scanning.
 * Only UUID-tagged fixtures created here are removed; no real PortOne, file, or notification calls.
 * Intentionally NOT @Transactional: assertions observe real commits and rollbacks.
 */
@SpringBootTest(classes = IdentityTransactionIntegrationTest.Config.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=none",
                "spring.jpa.open-in-view=false", "spring.batch.job.enabled=false",
                "spring.batch.jdbc.initialize-schema=never", "slack.batch-notification.enabled=false",
                "portone.identity.store-id=test-store", "portone.identity.channel-key=test-channel",
                "portone.identity.valid-minutes=10"
        })
@ActiveProfiles({"dev", "identity-transaction-test"})
class IdentityTransactionIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @Profile("identity-transaction-test")
    @EnableAutoConfiguration
    @EntityScan("org.teamsai.saibackend.domain")
    @EnableJpaRepositories(basePackageClasses = {
            IdentityRepository.class, UserRepository.class, LoanContractRepository.class
    })
    @Import({IdentityService.class, IdentityStatusService.class, IdentityValidator.class,
            IdentityFailureReasonFormatter.class, LoanContractService.class, UserService.class})
    static class Config {}

    @Autowired IdentityService service;
    @Autowired IdentityStatusService statusService;
    @Autowired IdentityRepository identities;
    @Autowired UserRepository users;
    @Autowired LoanContractRepository contracts;
    @Autowired LoanContractService contractService;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @MockitoBean PortOneIdentityService portOne;
    @MockitoBean LoanContractFileService files;
    @MockitoBean ContractAccountService accounts;
    @MockitoSpyBean UserService userService;
    @MockitoBean UserLinkLock userLinkLock;
    @MockitoBean LinkOperationStore linkOperationStore;
    @MockitoBean NotificationService notifications;

    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> contractIds = new ArrayList<>();
    private Long ownerId;
    private Long otherId;
    private TransactionTemplate tx;
    private TransactionTemplate independent;
    private static final LocalDate BIRTH = LocalDate.of(2000, 1, 1);
    private static final String NAME = "Identity Test";

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        ownerId = insertUser();
        otherId = insertUser();
    }

    @AfterEach
    void cleanUp() {
        for (Long id : contractIds) jdbc.update("delete from loan_contract where contract_id = ?", id);
        for (Long id : userIds) {
            jdbc.update("delete from identity where user_id = ?", id);
            jdbc.update("delete from users where user_id = ?", id);
        }
    }

    @Test
    void prepareCommitsForeignKeyAndLazyAssociation() {
        String id = prepare();
        assertThat(state(id)).isEqualTo("REQUESTED");
        tx.executeWithoutResult(s -> {
            Identity identity = identities.findByIdentityVerificationId(id).orElseThrow();
            assertThat(Hibernate.isInitialized(identity.getUser())).isFalse();
            assertThat(identity.getUser().getUserId()).isEqualTo(ownerId);
            assertThat(Hibernate.isInitialized(identity.getUser())).isFalse();
            assertThat(identity.getRequestedAt()).isNotNull();
        });
    }

    @Test
    void successCallsPortOneOutsideTransactionAndCommitsBeforeReturning() {
        String id = prepare();
        when(portOne.getIdentityVerification(id)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state(id)).isEqualTo("REQUESTED");
            return verifiedResponse(id);
        });
        var result = service.complete(ownerId, id);
        assertThat(state(id)).isEqualTo("VERIFIED");
        assertThat(result.status()).isEqualTo(IdentityStatus.VERIFIED);
        assertThat(result.expiresAt()).isEqualTo(result.verifiedAt().plusMinutes(10));
        assertThat(jdbc.queryForObject("select timestampdiff(second, verified_at, expires_at) from identity where identity_verification_id=?",
                Long.class, id)).isEqualTo(600L);
        var again = service.complete(ownerId, id);
        assertThat(again.verifiedAt()).isEqualTo(readIdentity(id).getVerifiedAt());
        verify(portOne, times(1)).getIdentityVerification(id);
    }

    @Test
    void failedResultCommitsBeforeDomainExceptionAndTruncatesReason() {
        String id = prepare();
        when(portOne.getIdentityVerification(id)).thenReturn(new PortOneIdentityResponse(id, "FAILED", null,
                new PortOneIdentityResponse.Failure("x".repeat(300), null, null)));
        assertError(() -> service.complete(ownerId, id), IdentityErrorCode.PORTONE_VERIFICATION_NOT_VERIFIED);
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(readIdentity(id).getFailureReason()).hasSize(255);
        assertThat(readIdentity(id).getVerifiedAt()).isNull();
    }

    @Test
    void mismatchCommitsFailureBeforeRethrowing() {
        String id = prepare();
        when(portOne.getIdentityVerification(id)).thenReturn(new PortOneIdentityResponse(id, "VERIFIED",
                new PortOneIdentityResponse.VerifiedCustomer("Different", BIRTH, "test-ci"), null));
        assertError(() -> service.complete(ownerId, id), IdentityErrorCode.IDENTITY_INFORMATION_MISMATCH);
        assertThat(state(id)).isEqualTo("FAILED");
        assertThat(readIdentity(id).getFailureReason()).isEqualTo("IDENTITY_INFORMATION_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "UNKNOWN", "WRONG_ID", "MISSING_CUSTOMER", "API_ERROR"})
    void incompleteOrInvalidRemoteResultDoesNotChangeDatabase(String scenario) {
        String id = prepare();
        IdentityErrorCode error = IdentityErrorCode.PORTONE_API_INVALID_RESPONSE;
        if (scenario.equals("API_ERROR")) {
            error = IdentityErrorCode.PORTONE_API_CALL_FAILED;
            when(portOne.getIdentityVerification(id)).thenThrow(error.toException());
        } else {
            String status = scenario.equals("READY") ? "READY" : scenario.equals("UNKNOWN") ? "UNKNOWN" : "VERIFIED";
            when(portOne.getIdentityVerification(id)).thenReturn(new PortOneIdentityResponse(
                    scenario.equals("WRONG_ID") ? "another-id" : id, status, null, null));
            if (scenario.equals("READY")) error = IdentityErrorCode.IDENTITY_VERIFICATION_NOT_COMPLETED;
        }
        assertError(() -> service.complete(ownerId, id), error);
        assertThat(state(id)).isEqualTo("REQUESTED");
    }

    @Test
    void anotherOwnerIsRejectedBeforeRemoteCall() {
        String id = prepare();
        assertError(() -> service.complete(otherId, id), IdentityErrorCode.IDENTITY_VERIFICATION_FORBIDDEN);
        verifyNoInteractions(portOne);
        assertThat(state(id)).isEqualTo("REQUESTED");
    }

    @Test
    void consumeIsInvisibleUntilOuterCommitAndPreservesManagedEntities() {
        String id = verified();
        tx.executeWithoutResult(s -> {
            User user = users.findById(ownerId).orElseThrow();
            service.consume(ownerId, id, IdentityPurpose.LOAN_CONTRACT);
            assertThat(state(id)).isEqualTo("USED");
            String committedState = independent.execute(inner -> state(id));
            assertThat(committedState).isEqualTo("VERIFIED");
            assertThat(em.contains(user)).isTrue();
            user.updateUserKey("identity-it-" + UUID.randomUUID());
        });
        assertThat(state(id)).isEqualTo("USED");
        assertThat(readIdentity(id).getUsedAt()).isNotNull();
        assertThat(users.findById(ownerId).orElseThrow().getUserKey()).startsWith("identity-it-");
    }

    @Test
    void outerFailureRollsBackConsumeAndOtherDirtyChanges() {
        String id = verified();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            User user = users.findById(ownerId).orElseThrow();
            service.consume(ownerId, id, IdentityPurpose.LOAN_CONTRACT);
            user.updateUserKey("must-roll-back");
            em.flush();
            throw new IllegalStateException("later business failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("VERIFIED");
        assertThat(readIdentity(id).getUsedAt()).isNull();
        assertThat(users.findById(ownerId).orElseThrow().getUserKey()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"WRONG_OWNER", "WRONG_PURPOSE", "EXPIRED", "ALREADY_USED", "REQUESTED", "FAILED"})
    void unusableVerificationIsRejectedWithoutChangingState(String scenario) {
        String id = verified();
        switch (scenario) {
            case "EXPIRED" -> jdbc.update("update identity set expires_at=date_sub(current_timestamp, interval 1 second) where identity_verification_id=?", id);
            case "ALREADY_USED" -> service.consume(ownerId, id, IdentityPurpose.LOAN_CONTRACT);
            case "REQUESTED", "FAILED" -> jdbc.update("update identity set status=? where identity_verification_id=?", scenario, id);
            default -> { }
        }
        String before = state(id);
        assertError(() -> service.consume(scenario.equals("WRONG_OWNER") ? otherId : ownerId, id,
                scenario.equals("WRONG_PURPOSE") ? IdentityPurpose.SETTLEMENT : IdentityPurpose.LOAN_CONTRACT),
                IdentityErrorCode.IDENTITY_VERIFICATION_CONSUME_FAILED);
        assertThat(state(id)).isEqualTo(before);
    }

    @Test
    void actualContractSigningCommitsDirtyCheckingAfterConsume() {
        String id = verified();
        Long contractId = pendingContract();
        when(files.saveSignatureFile(eq(contractId), any())).thenReturn("test/signature.png");
        doReturn(UserResponse.builder().name(NAME).birthDate(BIRTH).build()).when(userService).getMyInfo(anyLong());
        assertThat(sign(contractId, id)).isEqualTo(ContractStatus.COMPLETED);
        assertThat(state(id)).isEqualTo("USED");
        LoanContract contract = contracts.findById(contractId).orElseThrow();
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.COMPLETED);
        assertThat(contract.getDebtorSignature()).isEqualTo("test/signature.png");
        assertThat(contract.getDebtorAddress()).isEqualTo("test address");
    }

    @Test
    void actualContractFailureAfterSignatureMutationRollsBackBoth() {
        String id = verified();
        Long contractId = pendingContract();
        when(files.saveSignatureFile(eq(contractId), any())).thenReturn("test/signature.png");
        doThrow(new IllegalStateException("after signature mutation")).when(userService).getMyInfo(anyLong());
        assertThatThrownBy(() -> sign(contractId, id)).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("VERIFIED");
        assertThat(readIdentity(id).getUsedAt()).isNull();
        LoanContract contract = contracts.findById(contractId).orElseThrow();
        assertThat(contract.getStatus()).isEqualTo(ContractStatus.PENDING);
        assertThat(contract.getDebtorSignature()).isNull();
    }

    @Test
    void concurrentCompletionUsesCommittedWinnerState() throws Exception {
        String id = prepare();
        CyclicBarrier remoteCalls = new CyclicBarrier(2);
        when(portOne.getIdentityVerification(id)).thenAnswer(invocation -> {
            remoteCalls.await(15, TimeUnit.SECONDS);
            return verifiedResponse(id);
        });
        var results = race(() -> service.complete(ownerId, id).status());
        assertThat(results).containsExactly(IdentityStatus.VERIFIED, IdentityStatus.VERIFIED);
        assertThat(state(id)).isEqualTo("VERIFIED");
    }

    @Test
    void concurrentConsumeHasExactlyOneWinner() throws Exception {
        String id = verified();
        var results = race(() -> {
            try {
                service.consume(ownerId, id, IdentityPurpose.LOAN_CONTRACT);
                return "USED";
            } catch (DomainException ex) {
                assertThat(ex.getErrorCode()).isEqualTo(IdentityErrorCode.IDENTITY_VERIFICATION_CONSUME_FAILED);
                return "REJECTED";
            }
        });
        assertThat(results).containsExactlyInAnyOrder("USED", "REJECTED");
        assertThat(state(id)).isEqualTo("USED");
    }

    @Test
    void bulkUpdateLeavesLoadedEntityStaleButProjectionReadsDatabase() {
        String id = verified();
        tx.executeWithoutResult(s -> {
            Identity loaded = identities.findByIdentityVerificationId(id).orElseThrow();
            service.consume(ownerId, id, IdentityPurpose.LOAN_CONTRACT);
            assertThat(loaded.getStatus()).isEqualTo(IdentityStatus.VERIFIED);
            assertThat(identities.findStateByIdentityVerificationId(id).orElseThrow().status()).isEqualTo(IdentityStatus.USED);
            em.refresh(loaded);
            assertThat(loaded.getStatus()).isEqualTo(IdentityStatus.USED);
        });
    }

    @Test
    void uniqueConstraintRejectsDuplicateVerificationId() {
        String id = prepare();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> identities.saveAndFlush(Identity.builder()
                .identityVerificationId(id).user(users.getReferenceById(ownerId))
                .purpose(IdentityPurpose.LOAN_CONTRACT).status(IdentityStatus.REQUESTED)
                .requestedAt(LocalDateTime.now()).build())))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(state(id)).isEqualTo("REQUESTED");
    }

    @Test
    void foreignKeyRejectsMissingUser() {
        assertThatThrownBy(() -> service.prepare(-1L, new IdentityPrepareRequest(IdentityPurpose.LOAN_CONTRACT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void completeMustKeepRemoteCallOutsideAnExistingTransaction() {
        String id = prepare();
        when(portOne.getIdentityVerification(id)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new PortOneIdentityResponse(id, "FAILED", null, null);
        });
        assertError(() -> tx.executeWithoutResult(s -> service.complete(ownerId, id)),
                IdentityErrorCode.PORTONE_VERIFICATION_NOT_VERIFIED);
        assertThat(state(id)).isEqualTo("FAILED");
    }

    @Test
    void successfulCompletionSurvivesOuterRollbackWhileOuterChangesDoNot() {
        String id = prepare();
        when(portOne.getIdentityVerification(id)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return verifiedResponse(id);
        });
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            User other = users.findById(otherId).orElseThrow();
            other.updateUserKey("outer-change-must-roll-back");
            service.complete(ownerId, id);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            throw new IllegalStateException("outer failure after completion");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("VERIFIED");
        assertThat(users.findById(otherId).orElseThrow().getUserKey()).isNull();
    }

    @Test
    void prepareParticipatesInOuterRollback() {
        String[] preparedId = new String[1];
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            preparedId[0] = prepare();
            assertThat(state(preparedId[0])).isEqualTo("REQUESTED");
            Integer visibleRows = independent.execute(inner -> jdbc.queryForObject(
                    "select count(*) from identity where identity_verification_id=?", Integer.class, preparedId[0]));
            assertThat(visibleRows).isZero();
            throw new IllegalStateException("prepare caller failure");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from identity where identity_verification_id=?",
                Integer.class, preparedId[0])).isZero();
    }

    @Test
    void schemaStringColumnLengthsMatchConstraints() {
        var expectedLengths = java.util.Map.of(
                "identity_verification_id", 100L,
                "failure_reason", 255L
        );

        expectedLengths.forEach((column, length) -> assertThat(jdbc.queryForObject("""
        select character_maximum_length from information_schema.columns
        where table_schema=database() and table_name='identity' and column_name=?
        """, Long.class, column)).isEqualTo(length));
    }

    private Long insertUser() {
        String tag = UUID.randomUUID().toString();
        Long id = users.saveAndFlush(User.builder().userToken(tag).email(tag + "@identity.test")
                .password("not-a-real-password").name(NAME).birthDate(BIRTH).build()).getUserId();
        userIds.add(id);
        return id;
    }

    private String prepare() {
        return service.prepare(ownerId, new IdentityPrepareRequest(IdentityPurpose.LOAN_CONTRACT)).identityVerificationId();
    }

    private String verified() {
        String id = prepare();
        assertThat(statusService.updateVerified(id, LocalDateTime.now(), LocalDateTime.now().plusMinutes(10))).isEqualTo(1);
        return id;
    }

    private PortOneIdentityResponse verifiedResponse(String id) {
        return new PortOneIdentityResponse(id, "VERIFIED",
                new PortOneIdentityResponse.VerifiedCustomer(NAME, BIRTH, "test-ci"), null);
    }

    private String state(String id) {
        return jdbc.queryForObject("select status from identity where identity_verification_id=?", String.class, id);
    }

    private Identity readIdentity(String id) {
        return identities.findByIdentityVerificationId(id).orElseThrow();
    }

    private Long pendingContract() {
        Long id = tx.execute(s -> contracts.saveAndFlush(LoanContract.builder()
                .creditor(users.getReferenceById(otherId)).debtor(users.getReferenceById(ownerId))
                .principalAmount(new BigDecimal("10000")).interestRate(BigDecimal.ZERO)
                .repaymentType(RepaymentMethod.BULLET_REPAYMENT).startDate(LocalDate.now())
                .maturityDate(LocalDate.now().plusMonths(1)).repaymentDay(1)
                .creditorAddress("test creditor").contractAlias("identity-it-" + UUID.randomUUID())
                .status(ContractStatus.PENDING).build()).getContractId());
        contractIds.add(id);
        return id;
    }

    private ContractStatus sign(Long contractId, String identityId) {
        return contractService.submitDebtorSignature(contractId, ownerId, "test address",
                new MockMultipartFile("signature", new byte[]{1}), identityId);
    }

    private <T> List<T> race(Callable<T> action) throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<T> synchronizedAction = () -> {
            start.await(15, TimeUnit.SECONDS);
            return action.call();
        };
        try {
            var first = executor.submit(synchronizedAction);
            var second = executor.submit(synchronizedAction);
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void assertError(Runnable action, IdentityErrorCode error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(error));
    }
}
