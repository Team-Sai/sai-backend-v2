package org.teamsai.saibackend.domain.batch;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.teamsai.saibackend.domain.account.entity.LinkedBankAccount;
import org.teamsai.saibackend.domain.batch.service.WriteOffTransactionExecutor;
import org.teamsai.saibackend.domain.contract.entity.LoanContract;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.repository.LoanContractRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleGenerator;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.service.PaymentRecordService;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.user.entity.User;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {
        WriteOffTransactionExecutor.class,
        RepaymentScheduleService.class,
        RepaymentScheduleGenerator.class,
        SettlementPaymentService.class,
        WriteOffTransactionExecutorIntegrationTest.TestSliceConfig.class
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("dev")
@DisplayName("WriteOffTransactionExecutor 실제 JPQL 통합 테스트")
class WriteOffTransactionExecutorIntegrationTest {

    @TestConfiguration
    @EnableAutoConfiguration
    @EnableJpaRepositories(
            basePackageClasses = {
                    RepaymentScheduleRepository.class,
                    PaymentObligationRepository.class,
                    LoanContractRepository.class
            }
    )
    @EntityScan(
            basePackageClasses = {
                    RepaymentScheduleEntity.class,
                    PaymentObligationEntity.class,
                    LoanContract.class,
                    LinkedBankAccount.class,
                    User.class
            }
    )
    static class TestSliceConfig {
        @Bean
        PaymentRecordService paymentRecordService() {
            return org.mockito.Mockito.mock(PaymentRecordService.class);
        }

        // RepaymentScheduleService가 요구하지만 이 테스트와는 무관한 의존성 -> 목으로 대체
        @Bean
        LoanContractService loanContractService() {
            return org.mockito.Mockito.mock(LoanContractService.class);
        }
    }

    @Autowired
    private WriteOffTransactionExecutor writeOffTransactionExecutor;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM repayment_schedule WHERE contract_id >= 90000");
        jdbcTemplate.update("DELETE FROM loan_contract WHERE contract_id >= 90000");
        jdbcTemplate.update("DELETE FROM users WHERE user_id >= 90000");
    }

    @Test
    @DisplayName("700건을 넘겨도 청크 단위 실제 벌크 UPDATE JPQL로 전부 WRITTEN_OFF 처리된다")
    void writeOffSchedulesInNewTransaction_실제_DB에_반영된다() {
        insertUser(97001L, "채권자");
        insertUser(97002L, "채무자");
        insertLoanContract(97101L, 97001L, 97002L);

        List<Long> scheduleIds = LongStream.rangeClosed(1, 700)
                .map(i -> 97200L + i)
                .boxed()
                .toList();

        for (int i = 0; i < scheduleIds.size(); i++) {
            insertOverdueSchedule(scheduleIds.get(i), 97101L, i + 1);
        }

        int total = writeOffTransactionExecutor.writeOffSchedulesInNewTransaction(scheduleIds);

        assertThat(total).isEqualTo(700);

        Integer writtenOffCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM repayment_schedule WHERE contract_id = ? AND status = 'WRITTEN_OFF'",
                Integer.class, 97101L);
        assertThat(writtenOffCount).isEqualTo(700);
    }

    private void insertUser(Long userId, String name) {
        String unique = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                INSERT INTO users (user_id, user_token, email, password, name, birth_date)
                VALUES (?, ?, ?, ?, ?, '2000-01-01')
                """,
                userId, unique, unique + "@example.com", "password", name
        );
    }

    private void insertLoanContract(Long contractId, Long creditorId, Long debtorId) {
        jdbcTemplate.update(
                """
                INSERT INTO loan_contract (
                    contract_id, creditor_id, debtor_id,
                    principal_amount, interest_rate, repayment_type,
                    start_date, maturity_date, repayment_day,
                    creditor_address, contract_alias,
                    created_at, updated_at
                )
                VALUES (
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?,
                    NOW(), NOW()
                )
                """,
                contractId, creditorId, debtorId,
                new BigDecimal("100000000"), new BigDecimal("5.0"), "EQUAL_PRINCIPAL",
                LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1), 1,
                "서울시 테스트구", "테스트 계약"
        );
    }

    private void insertOverdueSchedule(Long scheduleId, Long contractId, int sequence) {
        jdbcTemplate.update(
                """
                INSERT INTO repayment_schedule (
                    schedule_id, contract_id, `sequence`, due_date,
                    principal_due, interest_due, total_payment_due, remaining_principal,
                    status, created_at
                )
                VALUES (?, ?, ?, ?, 100000, 5000, 105000, 100000, 'OVERDUE', NOW())
                """,
                scheduleId, contractId, sequence, LocalDate.of(2026, 1, 1)
        );
    }
}
