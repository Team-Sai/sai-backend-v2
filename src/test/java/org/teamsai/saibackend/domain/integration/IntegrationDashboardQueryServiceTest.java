package org.teamsai.saibackend.domain.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardStatus;
import org.teamsai.saibackend.domain.integration.dto.response.IntegrationDashboardResponse;
import org.teamsai.saibackend.domain.integration.service.IntegrationDashboardQueryService;
import org.teamsai.saibackend.domain.integration.type.DashboardAttentionType;
import org.teamsai.saibackend.domain.integration.type.DashboardTransactionStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationDashboardQueryServiceTest {

    @Mock
    private ContractDashboardQueryService contractDashboardQueryService;

    @Mock
    private SettlementQueryService settlementQueryService;

    @InjectMocks
    private IntegrationDashboardQueryService integrationDashboardQueryService;

    @Test
    void includesLoanReceivableAndPayableAmounts() {
        Long userId = 1L;
        ContractDashboardSummaryResponse loanSummary = ContractDashboardSummaryResponse.builder()
                .totalLentAmount(BigDecimal.valueOf(12_000_000))
                .totalBorrowedAmount(BigDecimal.valueOf(1_000_000))
                .build();

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(
                        ContractDashboardResponse.builder().summary(loanSummary).build(),
                        null,
                        List.of()
                ));

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId,
                YearMonth.of(2026, 8)
        );

        assertThat(response.getAmountSummary().getReceivable().getLoanAmount())
                .isEqualByComparingTo("12000000");
        assertThat(response.getAmountSummary().getReceivable().getTotalAmount())
                .isEqualByComparingTo("12000000");
        assertThat(response.getAmountSummary().getPayable().getLoanAmount())
                .isEqualByComparingTo("1000000");
        assertThat(response.getAmountSummary().getPayable().getTotalAmount())
                .isEqualByComparingTo("1000000");
        assertThat(response.getAmountSummary().getReceivable().getSettlementAmount())
                .isEqualByComparingTo("0");
        assertThat(response.getAmountSummary().getPayable().getSettlementAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    void includesLoanContractsInRecentTransactions() {
        Long userId = 1L;
        ContractDashboardRowResponse contract = ContractDashboardRowResponse.builder()
                .contractId(15L)
                .contractAlias("생활비 차용증")
                .principalAmount(BigDecimal.valueOf(3_000_000))
                .totalRemainingAmount(BigDecimal.valueOf(1_250_000))
                .contractStatus(ContractDashboardStatus.ONGOING)
                .build();

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(ContractDashboardResponse.builder()
                        .summary(ContractDashboardSummaryResponse.builder()
                                .totalLentAmount(BigDecimal.ZERO)
                                .totalBorrowedAmount(BigDecimal.ZERO)
                                .build())
                        .contracts(List.of(contract))
                        .build(), null, List.of()));

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId,
                YearMonth.of(2026, 8)
        );

        assertThat(response.getRecentTransactions()).hasSize(1);
        assertThat(response.getRecentTransactions().get(0).getTargetId()).isEqualTo(15L);
        assertThat(response.getRecentTransactions().get(0).getType())
                .isEqualTo(PaymentTargetType.LOAN);
        assertThat(response.getRecentTransactions().get(0).getTitle())
                .isEqualTo("생활비 차용증");
        assertThat(response.getRecentTransactions().get(0).getStatus())
                .isEqualTo(DashboardTransactionStatus.IN_PROGRESS);
        assertThat(response.getRecentTransactions().get(0).getAmount())
                .isEqualByComparingTo("3000000");
        assertThat(response.getRecentTransactions().get(0).getDetailUrl())
                .isEqualTo("/contracts/15/contract-detail");
    }

    @Test
    void includesLoanSchedulesInCalendarAttentionAndMonthlySummary() {
        Long userId = 1L;
        LocalDate today = LocalDate.now();
        LocalDate upcomingDueDate = today.plusDays(2);
        YearMonth requestedMonth = YearMonth.from(upcomingDueDate);

        LoanContractResponse contract = LoanContractResponse.builder()
                .contractId(20L)
                .creditorId(2L)
                .debtorId(userId)
                .contractAlias("생활비 차용증")
                .status(ContractStatus.COMPLETED)
                .build();

        RepaymentScheduleWithRemainingProjection paidSchedule = mock(RepaymentScheduleWithRemainingProjection.class);
        lenient().when(paidSchedule.getScheduleId()).thenReturn(100L);
        lenient().when(paidSchedule.getDueDate()).thenReturn(requestedMonth.atDay(1));
        lenient().when(paidSchedule.getStatus()).thenReturn(RepaymentScheduleStatus.PAID);

        RepaymentScheduleWithRemainingProjection pendingSchedule = mock(RepaymentScheduleWithRemainingProjection.class);
        lenient().when(pendingSchedule.getScheduleId()).thenReturn(101L);
        lenient().when(pendingSchedule.getDueDate()).thenReturn(upcomingDueDate);
        lenient().when(pendingSchedule.getStatus()).thenReturn(RepaymentScheduleStatus.PENDING);

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(
                        emptyContractDashboard(), contract, List.of(paidSchedule, pendingSchedule)
                ));

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId,
                requestedMonth
        );

        assertThat(response.getCalendarDays()).hasSize(1);
        assertThat(response.getCalendarDays().get(0).getDate()).isEqualTo(upcomingDueDate);
        assertThat(response.getCalendarDays().get(0).isHasInbound()).isFalse();
        assertThat(response.getCalendarDays().get(0).isHasOutbound()).isTrue();

        assertThat(response.getAttentionItems()).hasSize(1);
        assertThat(response.getAttentionItems().get(0).getId()).isEqualTo(101L);
        assertThat(response.getAttentionItems().get(0).getType())
                .isEqualTo(DashboardAttentionType.LOAN_DUE_SOON);
        assertThat(response.getAttentionItems().get(0).getRemainingDays()).isEqualTo(2L);
        assertThat(response.getAttentionItems().get(0).getActionUrl())
                .isEqualTo("/contracts/20/schedule");

        assertThat(response.getMonthlySummary().getInProgressLoanRepaymentCount()).isEqualTo(1);
        assertThat(response.getMonthlySummary().getCompletedTransactionCount()).isEqualTo(1);
        assertThat(response.getMonthlySummary().getTransactionCompletionRate())
                .isEqualByComparingTo("50.00");
    }

    @Test
    void filtersAndSortsUpcomingLoanAttentionItems() {
        Long userId = 1L;
        LocalDate today = LocalDate.now();
        LoanContractResponse contract = LoanContractResponse.builder()
                .contractId(20L)
                .creditorId(2L)
                .debtorId(userId)
                .status(ContractStatus.COMPLETED)
                .build();

        RepaymentScheduleWithRemainingProjection dueInFourDays = schedule(104L, today.plusDays(4), RepaymentScheduleStatus.PENDING);
        RepaymentScheduleWithRemainingProjection paidToday = schedule(105L, today, RepaymentScheduleStatus.PAID);
        RepaymentScheduleWithRemainingProjection dueInThreeDays = schedule(103L, today.plusDays(3), RepaymentScheduleStatus.PENDING);
        RepaymentScheduleWithRemainingProjection dueToday = schedule(100L, today, RepaymentScheduleStatus.PENDING);
        RepaymentScheduleWithRemainingProjection overdue = schedule(99L, today.minusDays(1), RepaymentScheduleStatus.PENDING);

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(emptyContractDashboard(), contract, List.of(
                        dueInFourDays,
                        paidToday,
                        dueInThreeDays,
                        dueToday,
                        overdue
                )));

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId,
                YearMonth.from(today)
        );

        assertThat(response.getAttentionItems())
                .extracting(
                        item -> item.getId(),
                        item -> item.getType(),
                        item -> item.getRemainingDays(),
                        item -> item.getActionUrl()
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                100L,
                                DashboardAttentionType.LOAN_DUE_SOON,
                                0L,
                                "/contracts/20/schedule"
                        ),
                        org.assertj.core.groups.Tuple.tuple(
                                103L,
                                DashboardAttentionType.LOAN_DUE_SOON,
                                3L,
                                "/contracts/20/schedule"
                        )
                );
    }

    @Test
    void integratesOwnerSettlementAcrossDashboardSections() {
        Long userId = 1L;
        LocalDate today = LocalDate.now();
        LocalDate dueDate = today.plusDays(3);
        LocalDateTime createdAt = LocalDateTime.now().plusDays(1);
        SettlementListResponse settlement = settlement(
                30L, "여행 정산", "OWNER", "IN_PROGRESS", dueDate, createdAt
        );
        SettlementPaymentStatusResponse paymentStatus = paymentStatus(
                30L,
                BigDecimal.valueOf(10_000),
                BigDecimal.valueOf(7000),
                obligation(11L, 2L, BigDecimal.valueOf(7000))
        );

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(emptyContractDashboard(), null, List.of()));
        when(settlementQueryService.getSettlementList(userId)).thenReturn(List.of(settlement));
        when(settlementQueryService.getPaymentStatus(30L, userId)).thenReturn(paymentStatus);

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId, YearMonth.from(dueDate)
        );

        assertThat(response.getAmountSummary().getReceivable().getSettlementAmount())
                .isEqualByComparingTo("7000");
        assertThat(response.getMonthlySummary().getInProgressSettlementCount()).isEqualTo(1);
        assertThat(response.getMonthlySummary().getTransactionCompletionRate())
                .isEqualByComparingTo("0.00");
        assertThat(response.getCalendarDays())
                .singleElement()
                .satisfies(day -> {
                    assertThat(day.getDate()).isEqualTo(dueDate);
                    assertThat(day.isHasInbound()).isTrue();
                    assertThat(day.isHasOutbound()).isFalse();
                });
        assertThat(response.getAttentionItems())
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.getId()).isEqualTo(30L);
                    assertThat(item.getType()).isEqualTo(DashboardAttentionType.SETTLEMENT_DUE_SOON);
                    assertThat(item.getRemainingDays()).isEqualTo(3L);
                    assertThat(item.getActionUrl()).isEqualTo("/settlements/30");
                });
        assertThat(response.getRecentTransactions())
                .singleElement()
                .satisfies(transaction -> {
                    assertThat(transaction.getType()).isEqualTo(PaymentTargetType.SETTLEMENT);
                    assertThat(transaction.getAmount()).isEqualByComparingTo("10000");
                    assertThat(transaction.getDetailUrl()).isEqualTo("/settlements/30");
                });
    }

    @Test
    void participantUsesOnlyOwnUnpaidSettlementBalance() {
        Long userId = 1L;
        LocalDate dueDate = LocalDate.now().plusDays(2);
        SettlementListResponse settlement = settlement(
                31L, "회식 정산", "MEMBER", "IN_PROGRESS", dueDate, LocalDateTime.now()
        );
        SettlementPaymentStatusResponse paymentStatus = paymentStatus(
                31L,
                BigDecimal.valueOf(11_000),
                BigDecimal.valueOf(9000),
                obligation(12L, userId, BigDecimal.valueOf(6000), BigDecimal.valueOf(4000)),
                obligation(13L, 2L, BigDecimal.valueOf(5000))
        );

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(emptyContractDashboard(), null, List.of()));
        when(settlementQueryService.getSettlementList(userId)).thenReturn(List.of(settlement));
        when(settlementQueryService.getPaymentStatus(31L, userId)).thenReturn(paymentStatus);

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId, YearMonth.from(dueDate)
        );

        assertThat(response.getAmountSummary().getPayable().getSettlementAmount())
                .isEqualByComparingTo("4000");
        assertThat(response.getCalendarDays().get(0).isHasOutbound()).isTrue();
        assertThat(response.getRecentTransactions().get(0).getAmount())
                .isEqualByComparingTo("6000");
    }

    @Test
    void keepsFiveRecentTransactionsForEachType() {
        Long userId = 1L;
        LocalDateTime baseCreatedAt = LocalDateTime.of(2026, 8, 1, 10, 0);
        List<ContractDashboardRowResponse> loans = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> ContractDashboardRowResponse.builder()
                        .contractId((long) index)
                        .contractAlias("차용증 " + index)
                        .principalAmount(BigDecimal.valueOf(index * 1000L))
                        .totalRemainingAmount(BigDecimal.valueOf(index * 100L))
                        .contractStatus(ContractDashboardStatus.ONGOING)
                        .createdAt(baseCreatedAt.plusDays(index))
                        .build())
                .toList();
        List<SettlementListResponse> settlements = IntStream.rangeClosed(1, 6)
                .mapToObj(index -> settlement(
                        100L + index,
                        "정산 " + index,
                        "OWNER",
                        "IN_PROGRESS",
                        LocalDate.of(2026, 8, 20),
                        baseCreatedAt.plusDays(10 + index)
                ))
                .toList();

        when(contractDashboardQueryService.getIntegrationDashboardData(userId))
                .thenReturn(loanData(ContractDashboardResponse.builder()
                        .summary(ContractDashboardSummaryResponse.builder()
                                .totalLentAmount(BigDecimal.ZERO)
                                .totalBorrowedAmount(BigDecimal.ZERO)
                                .build())
                        .contracts(loans)
                        .build(), null, List.of()));
        when(settlementQueryService.getSettlementList(userId)).thenReturn(settlements);
        settlements.forEach(settlement -> when(settlementQueryService.getPaymentStatus(
                settlement.settlementId(), userId
        )).thenReturn(paymentStatus(
                settlement.settlementId(),
                BigDecimal.valueOf(10_000),
                BigDecimal.valueOf(10_000),
                obligation(settlement.settlementId(), 2L, BigDecimal.valueOf(10_000))
        )));

        IntegrationDashboardResponse response = integrationDashboardQueryService.getDashboard(
                userId, YearMonth.of(2026, 8)
        );

        assertThat(response.getRecentTransactions()).hasSize(10);
        assertThat(response.getRecentTransactions())
                .filteredOn(transaction -> transaction.getType() == PaymentTargetType.LOAN)
                .hasSize(5);
        assertThat(response.getRecentTransactions())
                .filteredOn(transaction -> transaction.getType() == PaymentTargetType.SETTLEMENT)
                .extracting(transaction -> transaction.getTargetId())
                .containsExactly(106L, 105L, 104L, 103L, 102L);
    }

    private SettlementListResponse settlement(
            Long id, String title, String role, String status, LocalDate dueDate, LocalDateTime createdAt
    ) {
        return new SettlementListResponse(
                id, title, role, "ETC", "SHARED", "EQUAL", status, BigDecimal.ZERO, dueDate, null, null, null, createdAt
        );
    }

    private SettlementPaymentStatusResponse paymentStatus(
            Long settlementId,
            BigDecimal totalExpected,
            BigDecimal totalRemaining,
            SettlementPaymentObligationResponse... obligations
    ) {
        return SettlementPaymentStatusResponse.builder()
                .settlementId(settlementId)
                .obligations(List.of(obligations))
                .totalExpectedAmount(totalExpected)
                .totalRemainingAmount(totalRemaining)
                .build();
    }

    private SettlementPaymentObligationResponse obligation(Long id, Long userId, BigDecimal remaining) {
        return obligation(id, userId, remaining, remaining);
    }

    private SettlementPaymentObligationResponse obligation(
            Long id, Long userId, BigDecimal expected, BigDecimal remaining
    ) {
        return SettlementPaymentObligationResponse.builder()
                .paymentObligationId(id)
                .userId(userId)
                .expectedAmount(expected)
                .paidAmount(BigDecimal.ZERO)
                .remainingAmount(remaining)
                .paymentStatus(PaymentStatus.UNPAID)
                .build();
    }

    private RepaymentScheduleWithRemainingProjection schedule(
            Long scheduleId,
            LocalDate dueDate,
            RepaymentScheduleStatus status
    ) {
        RepaymentScheduleWithRemainingProjection schedule = mock(RepaymentScheduleWithRemainingProjection.class);
        lenient().when(schedule.getScheduleId()).thenReturn(scheduleId);
        lenient().when(schedule.getDueDate()).thenReturn(dueDate);
        lenient().when(schedule.getStatus()).thenReturn(status);
        return schedule;
    }

    private ContractDashboardResponse emptyContractDashboard() {
        return ContractDashboardResponse.builder()
                .summary(ContractDashboardSummaryResponse.builder()
                        .totalLentAmount(BigDecimal.ZERO)
                        .totalBorrowedAmount(BigDecimal.ZERO)
                        .build())
                .contracts(List.of())
                .build();
    }

    private ContractDashboardQueryService.IntegrationDashboardData loanData(
            ContractDashboardResponse dashboard,
            LoanContractResponse contract,
            List<RepaymentScheduleWithRemainingProjection> schedules
    ) {
        List<ContractDashboardQueryService.LoanScheduleContext> contexts = contract == null
                ? List.of()
                : schedules.stream()
                .map(schedule -> new ContractDashboardQueryService.LoanScheduleContext(contract, schedule))
                .toList();
        return new ContractDashboardQueryService.IntegrationDashboardData(dashboard, contexts);
    }
}
