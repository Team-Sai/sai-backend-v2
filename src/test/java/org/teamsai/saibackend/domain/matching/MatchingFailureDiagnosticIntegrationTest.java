package org.teamsai.saibackend.domain.matching;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.teamsai.saibackend.domain.matching.model.MatchingCandidate;
import org.teamsai.saibackend.domain.matching.repository.*;
import org.teamsai.saibackend.domain.matching.service.*;
import org.teamsai.saibackend.domain.matching.type.*;
import org.teamsai.saibackend.domain.payment.repository.PaymentRecordRepository;
import org.teamsai.saibackend.domain.payment.service.*;
import org.teamsai.saibackend.domain.transaction.repository.BankTransactionRepository;
import org.teamsai.saibackend.domain.transaction.service.BankTransactionService;
import org.teamsai.saibackend.domain.transaction.entity.BankTransaction;
import org.teamsai.saibackend.domain.transaction.type.*;
import org.teamsai.saibackend.domain.account.service.LinkedBankAccountService;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.settlement.support.SettlementPaymentStatusChecker;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Isolated MariaDB diagnostic: no production credentials or production data. */
@Tag("integration")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SAI_MATCHING_DIAGNOSTIC", matches="true")
@SpringBootTest(classes = MatchingFailureDiagnosticIntegrationTest.Config.class, properties = {
    "spring.datasource.url=jdbc:mariadb://127.0.0.1:13317/sai_matching_diagnostic",
    "spring.datasource.username=root", "spring.datasource.password=diagnostic-only",
    "spring.sql.init.mode=never", "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.batch.job.enabled=false", "spring.batch.jdbc.initialize-schema=never"
})
class MatchingFailureDiagnosticIntegrationTest {
    @org.springframework.context.annotation.Configuration
    @EnableAutoConfiguration
    @EntityScan("org.teamsai.saibackend.domain")
    @EnableJpaRepositories(basePackageClasses = {BankTransactionRepository.class,
        PaymentRecordRepository.class, BankTransactionMatchCandidateRepository.class})
    @Import({AutoMatchingJudge.class, AutoMatchingService.class, BankMatchingService.class,
        BankMatchingTransactionService.class, BankTransactionService.class,
        BankTransactionMatchCandidateService.class, BankTransactionMatchCandidateQueryRepository.class,
        SettlementPaymentService.class, PaymentRecordService.class, BankTransactionRetryService.class})
    static class Config {}

    @Autowired BankMatchingService matching;
    @Autowired BankTransactionRepository transactions;
    @Autowired JdbcTemplate jdbc;
    @Autowired BankTransactionRetryService retry;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @MockitoBean org.teamsai.saibackend.global.notification.SlackNotifier slack;
    // Candidate discovery is controlled; payment writes, row locks, status writes and transaction
    // propagation all use real Spring beans and MariaDB. No payment exceptions are injected.
    @MockitoBean MatchingCandidateRepository discovery;
    @MockitoBean LoanPaymentService loan;
    @MockitoBean LinkedBankAccountService accounts;
    @MockitoBean NotificationService notifications;
    @MockitoBean PaymentObligationQueryService obligations;
    @MockitoBean SettlementPaymentStatusChecker checker;

    @BeforeEach void seed() {
        jdbc.update("DELETE FROM bank_transaction_match_candidate");
        jdbc.update("DELETE FROM payment_record");
        jdbc.update("DELETE FROM bank_transaction");
        jdbc.update("DELETE FROM payment_obligation");
        jdbc.update("INSERT INTO payment_obligation (payment_obligation_id, participant_id, expected_amount, payment_status, review_status, obligation_status) VALUES (1,1,25000,'PARTIALLY_PAID','NORMAL','ACTIVE')");
        jdbc.update("INSERT INTO payment_record (bank_transaction_id,payment_target_type,target_id,amount,source_type,record_status,recorded_at) VALUES (999999,'SETTLEMENT',1,24000,'MANUAL','CONFIRMED',NOW())");
    }

    BankTransaction deposit() {
        return transactions.saveAndFlush(new BankTransaction(1L,"diagnostic",new BigDecimal("1000.00"),
            BankTransactionType.DEPOSIT,LocalDateTime.now(),"한민엽",null,LocalDateTime.now()));
    }
    MatchingCandidate candidate(long id) {
        return new MatchingCandidate(MatchingTargetType.SETTLEMENT,id,1L,"한민엽",new BigDecimal("1000.00"));
    }
    @Test void exactRemainingAmountCommitsSuccessfully() {
        var tx=deposit();
        when(discovery.findMatchCandidatesByLinkedAccountId(any(),any())).thenReturn(List.of(candidate(1)));
        var result=matching.execute(1L,1L,true);
        assertThat(result.appliedCount()).isEqualTo(1);
        assertThat(transactions.findById(tx.getBankTransactionId()).orElseThrow().getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
        assertThat(jdbc.queryForObject("SELECT SUM(amount) FROM payment_record WHERE target_id=1",BigDecimal.class)).isEqualByComparingTo("25000");
        System.out.println("DIAGNOSTIC exact 1000/1000 -> APPLIED, total paid=25000");
    }
    @Test void exactAmountWithMultipleCandidatesNeedsReview() {
        var tx=deposit();
        when(discovery.findMatchCandidatesByLinkedAccountId(any(),any())).thenReturn(List.of(candidate(1),candidate(2)));
        var result=matching.execute(1L,1L,true);
        assertThat(result.needsCheckCount()).isEqualTo(1);
        assertThat(transactions.findById(tx.getBankTransactionId()).orElseThrow().getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.NEEDS_CHECK);
        System.out.println("DIAGNOSTIC exact amount + two candidates -> NEEDS_CHECK");
    }
    @Test void realCandidateInsertFailureRollsBackInsteadOfPersistingFailed() {
        var tx=deposit();
        // A real unique-key violation, not a mocked exception.
        when(discovery.findMatchCandidatesByLinkedAccountId(any(),any())).thenReturn(List.of(candidate(1),candidate(1)));
        assertThatThrownBy(()->matching.execute(1L,1L,true))
            .isInstanceOf(org.hibernate.AssertionFailure.class)
            .hasMessageContaining("null identifier");
        assertThat(transactions.findById(tx.getBankTransactionId()).orElseThrow().getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bank_transaction_match_candidate",Integer.class)).isZero();
        System.out.println("DIAGNOSTIC real candidate unique-key violation -> rollback, persisted PENDING (not FAILED)");
    }
    @Test void exactAmountStaysFailedWhenRetryBudgetAlreadyExhausted() {
        var tx=deposit();
        jdbc.update("UPDATE bank_transaction SET processing_status='FAILED',retry_count=5 WHERE bank_transaction_id=?",tx.getBankTransactionId());
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> retry.retryForAccount(1L,1L));
        assertThat(transactions.findById(tx.getBankTransactionId()).orElseThrow().getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.FAILED);
        verifyNoInteractions(discovery);
        System.out.println("DIAGNOSTIC exact 1000/1000 + prior FAILED + retry_count=5 -> still FAILED; matching never invoked");
    }
    @Test void exactAmountRecoversFromFailedWhenRetryBudgetRemains() {
        var tx=deposit();
        jdbc.update("UPDATE bank_transaction SET processing_status='FAILED',retry_count=4 WHERE bank_transaction_id=?",tx.getBankTransactionId());
        when(discovery.findMatchCandidatesByLinkedAccountId(any(),any())).thenReturn(List.of(candidate(1)));
        new org.springframework.transaction.support.TransactionTemplate(transactionManager)
            .executeWithoutResult(status -> retry.retryForAccount(1L,1L));
        assertThat(transactions.findById(tx.getBankTransactionId()).orElseThrow().getProcessingStatus()).isEqualTo(BankTransactionProcessingStatus.APPLIED);
        System.out.println("DIAGNOSTIC exact 1000/1000 + prior FAILED + retry_count=4 -> APPLIED on fifth retry");
    }
}
