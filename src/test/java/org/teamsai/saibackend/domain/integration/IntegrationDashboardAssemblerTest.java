package org.teamsai.saibackend.domain.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.calendar.dto.response.DashboardCalendarItemResponse;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.contract.type.ContractDashboardStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.domain.integration.assembler.IntegrationDashboardAssembler;
import org.teamsai.saibackend.domain.integration.assembler.IntegrationDashboardAssembler.SettlementContext;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardAttentionItemResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardCalendarDayResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardMonthlySummaryResponse;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardRecentTransactionResponse;
import org.teamsai.saibackend.domain.integration.type.DashboardAttentionType;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentTargetType;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentObligationResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

@DisplayName("IntegrationDashboardAssembler 단위 테스트")
class IntegrationDashboardAssemblerTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private SettlementListResponse settlement(
            Long id, String title, String role, String status, LocalDate dueDate, LocalDateTime createdAt
    ) {
        return new SettlementListResponse(
                id, title, role, "ETC", "SHARED", "EQUAL", status, BigDecimal.ZERO, dueDate, null, null, null, createdAt
        );
    }

    private SettlementPaymentStatusResponse paymentStatus(
            BigDecimal totalExpected,
            BigDecimal totalRemaining,
            SettlementPaymentObligationResponse... obligations
    ) {
        return SettlementPaymentStatusResponse.builder()
                .obligations(List.of(obligations))
                .totalExpectedAmount(totalExpected)
                .totalRemainingAmount(totalRemaining)
                .build();
    }

    private SettlementPaymentObligationResponse obligation(Long userId, BigDecimal expected, BigDecimal remaining) {
        return SettlementPaymentObligationResponse.builder()
                .userId(userId)
                .expectedAmount(expected)
                .remainingAmount(remaining)
                .paymentStatus(PaymentStatus.UNPAID)
                .build();
    }

    private RepaymentScheduleWithRemainingProjection schedule(Long scheduleId, LocalDate dueDate, RepaymentScheduleStatus status) {
        RepaymentScheduleWithRemainingProjection schedule = mock(RepaymentScheduleWithRemainingProjection.class);
        lenient().when(schedule.getScheduleId()).thenReturn(scheduleId);
        lenient().when(schedule.getDueDate()).thenReturn(dueDate);
        lenient().when(schedule.getStatus()).thenReturn(status);
        lenient().when(schedule.getSequence()).thenReturn(1);
        lenient().when(schedule.getTotalPaymentDue()).thenReturn(BigDecimal.valueOf(50_000));
        return schedule;
    }

    private LoanContractResponse loanContract(Long contractId, Long creditorId, Long debtorId) {
        return LoanContractResponse.builder()
                .contractId(contractId)
                .creditorId(creditorId)
                .debtorId(debtorId)
                .contractAlias("생활비 차용증")
                .status(ContractStatus.COMPLETED)
                .build();
    }

    @Nested
    @DisplayName("정산 컨텍스트 조립")
    class ToSettlementContext {

        @Test
        @DisplayName("OWNER는 전체 잔액/예상액을 그대로 사용한다")
        void ownerUsesTotalAmounts() {
            SettlementListResponse settlement = settlement(1L, "여행 정산", "OWNER", "IN_PROGRESS", LocalDate.now(), LocalDateTime.now());
            SettlementPaymentStatusResponse status = paymentStatus(
                    BigDecimal.valueOf(10_000), BigDecimal.valueOf(7_000),
                    obligation(OTHER_USER_ID, BigDecimal.valueOf(10_000), BigDecimal.valueOf(7_000))
            );

            SettlementContext context = IntegrationDashboardAssembler.toSettlementContext(settlement, status, USER_ID);

            assertThat(context.roleRemainingAmount()).isEqualByComparingTo("7000");
            assertThat(context.originalRoleAmount()).isEqualByComparingTo("10000");
            assertThat(context.isOwner()).isTrue();
        }

        @Test
        @DisplayName("참여자는 본인 몫의 obligation만 합산한다")
        void memberUsesOwnObligationOnly() {
            SettlementListResponse settlement = settlement(2L, "회식 정산", "MEMBER", "IN_PROGRESS", LocalDate.now(), LocalDateTime.now());
            SettlementPaymentStatusResponse status = paymentStatus(
                    BigDecimal.valueOf(11_000), BigDecimal.valueOf(9_000),
                    obligation(USER_ID, BigDecimal.valueOf(6_000), BigDecimal.valueOf(4_000)),
                    obligation(OTHER_USER_ID, BigDecimal.valueOf(5_000), BigDecimal.valueOf(5_000))
            );

            SettlementContext context = IntegrationDashboardAssembler.toSettlementContext(settlement, status, USER_ID);

            assertThat(context.roleRemainingAmount()).isEqualByComparingTo("4000");
            assertThat(context.originalRoleAmount()).isEqualByComparingTo("6000");
            assertThat(context.isOwner()).isFalse();
        }

        @Test
        @DisplayName("obligation 목록이 없으면 0으로 처리한다")
        void handlesNullObligations() {
            SettlementListResponse settlement = settlement(3L, "빈 정산", "MEMBER", "IN_PROGRESS", LocalDate.now(), LocalDateTime.now());
            SettlementPaymentStatusResponse status = SettlementPaymentStatusResponse.builder().build();

            SettlementContext context = IntegrationDashboardAssembler.toSettlementContext(settlement, status, USER_ID);

            assertThat(context.roleRemainingAmount()).isEqualByComparingTo("0");
            assertThat(context.originalRoleAmount()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("금액 요약 조립")
    class ToAmountSummary {

        @Test
        @DisplayName("대출과 정산 금액을 역할별로 합산한다")
        void combinesLoanAndSettlementAmounts() {
            ContractDashboardSummaryResponse loanSummary = ContractDashboardSummaryResponse.builder()
                    .totalLentAmount(BigDecimal.valueOf(12_000_000))
                    .totalBorrowedAmount(BigDecimal.valueOf(1_000_000))
                    .build();
            List<SettlementContext> settlements = List.of(
                    new SettlementContext(settlement(1L, "A", "OWNER", "IN_PROGRESS", LocalDate.now(), LocalDateTime.now()),
                            BigDecimal.valueOf(3_000), BigDecimal.valueOf(3_000)),
                    new SettlementContext(settlement(2L, "B", "MEMBER", "IN_PROGRESS", LocalDate.now(), LocalDateTime.now()),
                            BigDecimal.valueOf(2_000), BigDecimal.valueOf(2_000))
            );

            var result = IntegrationDashboardAssembler.toAmountSummary(loanSummary, settlements);

            assertThat(result.getReceivable().getLoanAmount()).isEqualByComparingTo("12000000");
            assertThat(result.getReceivable().getSettlementAmount()).isEqualByComparingTo("3000");
            assertThat(result.getReceivable().getTotalAmount()).isEqualByComparingTo("12003000");
            assertThat(result.getPayable().getLoanAmount()).isEqualByComparingTo("1000000");
            assertThat(result.getPayable().getSettlementAmount()).isEqualByComparingTo("2000");
            assertThat(result.getPayable().getTotalAmount()).isEqualByComparingTo("1002000");
        }

        @Test
        @DisplayName("정산이 없으면 대출 금액만 반영한다")
        void handlesNoSettlements() {
            ContractDashboardSummaryResponse loanSummary = ContractDashboardSummaryResponse.builder()
                    .totalLentAmount(BigDecimal.valueOf(5_000))
                    .totalBorrowedAmount(null)
                    .build();

            var result = IntegrationDashboardAssembler.toAmountSummary(loanSummary, List.of());

            assertThat(result.getReceivable().getTotalAmount()).isEqualByComparingTo("5000");
            assertThat(result.getPayable().getTotalAmount()).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("최근 거래 조립")
    class ToRecentTransactions {

        @Test
        @DisplayName("대출/정산 항목을 합쳐 최신순으로 정렬한다")
        void mergesAndSortsByNewest() {
            LocalDateTime older = LocalDateTime.of(2026, 8, 1, 0, 0);
            LocalDateTime newer = LocalDateTime.of(2026, 8, 5, 0, 0);

            ContractDashboardRowResponse loan = ContractDashboardRowResponse.builder()
                    .contractId(1L)
                    .contractAlias("차용증")
                    .principalAmount(BigDecimal.valueOf(1_000))
                    .contractStatus(ContractDashboardStatus.ONGOING)
                    .createdAt(older)
                    .build();
            ContractDashboardResponse contractDashboard = ContractDashboardResponse.builder().contracts(List.of(loan)).build();

            SettlementContext settlementContext = new SettlementContext(
                    settlement(2L, "정산", "OWNER", "IN_PROGRESS", LocalDate.now(), newer),
                    BigDecimal.valueOf(500), BigDecimal.valueOf(2_000)
            );

            List<DashboardRecentTransactionResponse> result =
                    IntegrationDashboardAssembler.toRecentTransactions(contractDashboard, List.of(settlementContext));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getType()).isEqualTo(PaymentTargetType.SETTLEMENT);
            assertThat(result.get(0).getAmount()).isEqualByComparingTo("2000");
            assertThat(result.get(1).getType()).isEqualTo(PaymentTargetType.LOAN);
        }

        @Test
        @DisplayName("각 타입별로 최대 5건까지만 남긴다")
        void limitsToFivePerType() {
            List<ContractDashboardRowResponse> loans = java.util.stream.IntStream.rangeClosed(1, 7)
                    .mapToObj(i -> ContractDashboardRowResponse.builder()
                            .contractId((long) i)
                            .contractAlias("차용증 " + i)
                            .principalAmount(BigDecimal.valueOf(i))
                            .contractStatus(ContractDashboardStatus.ONGOING)
                            .createdAt(LocalDateTime.of(2026, 8, i, 0, 0))
                            .build())
                    .toList();
            ContractDashboardResponse contractDashboard = ContractDashboardResponse.builder().contracts(loans).build();

            List<DashboardRecentTransactionResponse> result =
                    IntegrationDashboardAssembler.toRecentTransactions(contractDashboard, List.of());

            assertThat(result).hasSize(5);
            assertThat(result.get(0).getTargetId()).isEqualTo(7L);
        }

        @Test
        @DisplayName("계약 목록이 null이면 빈 목록으로 처리한다")
        void handlesNullContracts() {
            ContractDashboardResponse contractDashboard = ContractDashboardResponse.builder().contracts(null).build();

            List<DashboardRecentTransactionResponse> result =
                    IntegrationDashboardAssembler.toRecentTransactions(contractDashboard, List.of());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("캘린더 일자 조립")
    class ToCalendarDays {

        @Test
        @DisplayName("대출 상환일과 정산 마감일을 같은 날짜로 합친다")
        void mergesLoanAndSettlementDirections() {
            LocalDate dueDate = LocalDate.of(2026, 8, 15);
            YearMonth yearMonth = YearMonth.from(dueDate);

            LoanContractResponse contract = loanContract(1L, OTHER_USER_ID, USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(10L, dueDate, RepaymentScheduleStatus.PENDING)
            ));
            List<SettlementContext> settlements = List.of(new SettlementContext(
                    settlement(2L, "정산", "OWNER", "IN_PROGRESS", dueDate, LocalDateTime.now()),
                    BigDecimal.valueOf(1_000), BigDecimal.valueOf(1_000)
            ));

            List<DashboardCalendarDayResponse> result =
                    IntegrationDashboardAssembler.toCalendarDays(loanSchedules, settlements, yearMonth, USER_ID);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getDate()).isEqualTo(dueDate);
            assertThat(result.get(0).isHasInbound()).isTrue();
            assertThat(result.get(0).isHasOutbound()).isTrue();
        }

        @Test
        @DisplayName("이미 마감된 정산이나 잔액 0인 정산은 제외한다")
        void excludesClosedOrZeroRemainingSettlements() {
            LocalDate dueDate = LocalDate.of(2026, 8, 15);
            SettlementContext closed = new SettlementContext(
                    settlement(1L, "종료된 정산", "OWNER", "CLOSED", dueDate, LocalDateTime.now()),
                    BigDecimal.valueOf(1_000), BigDecimal.valueOf(1_000)
            );
            SettlementContext zeroRemaining = new SettlementContext(
                    settlement(2L, "완납된 정산", "OWNER", "IN_PROGRESS", dueDate, LocalDateTime.now()),
                    BigDecimal.ZERO, BigDecimal.valueOf(1_000)
            );

            List<DashboardCalendarDayResponse> result = IntegrationDashboardAssembler.toCalendarDays(
                    List.of(), List.of(closed, zeroRemaining), YearMonth.from(dueDate), USER_ID
            );

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("특정 날짜 캘린더 상세 조립")
    class ToCalendarDayDetail {

        @Test
        @DisplayName("해당 날짜의 대출/정산 항목을 제목순으로 합쳐서 반환한다")
        void mergesLoanAndSettlementItemsSortedByTitle() {
            LocalDate date = LocalDate.of(2026, 8, 15);
            LoanContractResponse contract = loanContract(1L, USER_ID, OTHER_USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(10L, date, RepaymentScheduleStatus.PENDING)
            ));
            SettlementContext settlementContext = new SettlementContext(
                    settlement(2L, "AAA 정산", "OWNER", "IN_PROGRESS", date, LocalDateTime.now()),
                    BigDecimal.valueOf(1_000), BigDecimal.valueOf(1_000)
            );

            List<DashboardCalendarItemResponse> result = IntegrationDashboardAssembler.toCalendarDayDetail(
                    loanSchedules, List.of(settlementContext), date, USER_ID
            );

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTitle()).isEqualTo("AAA 정산");
            assertThat(result.get(0).getType()).isEqualTo(PaymentTargetType.SETTLEMENT);
            assertThat(result.get(0).getSubLabel()).isEqualTo("받을 돈");
            assertThat(result.get(1).getType()).isEqualTo(PaymentTargetType.LOAN);
            assertThat(result.get(1).getSubLabel()).isEqualTo("수취예정");
        }

        @Test
        @DisplayName("채권자가 아니면 대출 항목의 라벨은 납부예정이 된다")
        void showsPayableLabelForDebtor() {
            LocalDate date = LocalDate.of(2026, 8, 15);
            LoanContractResponse contract = loanContract(1L, OTHER_USER_ID, USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(10L, date, RepaymentScheduleStatus.PENDING)
            ));

            List<DashboardCalendarItemResponse> result = IntegrationDashboardAssembler.toCalendarDayDetail(
                    loanSchedules, List.of(), date, USER_ID
            );

            assertThat(result).singleElement().satisfies(item ->
                    assertThat(item.getSubLabel()).isEqualTo("납부예정")
            );
        }

        @Test
        @DisplayName("다른 날짜의 항목은 포함하지 않는다")
        void excludesOtherDates() {
            LocalDate date = LocalDate.of(2026, 8, 15);
            LocalDate otherDate = LocalDate.of(2026, 8, 16);
            LoanContractResponse contract = loanContract(1L, USER_ID, OTHER_USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(10L, otherDate, RepaymentScheduleStatus.PENDING)
            ));

            List<DashboardCalendarItemResponse> result = IntegrationDashboardAssembler.toCalendarDayDetail(
                    loanSchedules, List.of(), date, USER_ID
            );

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("주의 항목 조립")
    class ToAttentionItems {

        @Test
        @DisplayName("3일 이내 도래하는 대출/정산 항목을 남은 일수순으로 정렬한다")
        void sortsByRemainingDays() {
            LocalDate today = LocalDate.now();
            LoanContractResponse contract = loanContract(1L, OTHER_USER_ID, USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(10L, today.plusDays(2), RepaymentScheduleStatus.PENDING)
            ));
            SettlementContext settlementContext = new SettlementContext(
                    settlement(2L, "정산", "OWNER", "IN_PROGRESS", today.plusDays(1), LocalDateTime.now()),
                    BigDecimal.valueOf(1_000), BigDecimal.valueOf(1_000)
            );

            List<DashboardAttentionItemResponse> result =
                    IntegrationDashboardAssembler.toAttentionItems(loanSchedules, List.of(settlementContext));

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getType()).isEqualTo(DashboardAttentionType.SETTLEMENT_DUE_SOON);
            assertThat(result.get(0).getRemainingDays()).isEqualTo(1L);
            assertThat(result.get(1).getType()).isEqualTo(DashboardAttentionType.LOAN_DUE_SOON);
            assertThat(result.get(1).getRemainingDays()).isEqualTo(2L);
        }

        @Test
        @DisplayName("3일보다 먼 항목은 제외한다")
        void excludesItemsBeyondThreeDays() {
            LocalDate today = LocalDate.now();
            LoanContractResponse contract = loanContract(1L, OTHER_USER_ID, USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(10L, today.plusDays(4), RepaymentScheduleStatus.PENDING)
            ));

            List<DashboardAttentionItemResponse> result =
                    IntegrationDashboardAssembler.toAttentionItems(loanSchedules, List.of());

            assertThat(result).isEmpty();
        }
    }

    @Nested
    @DisplayName("월별 요약 조립")
    class ToMonthlySummary {

        @Test
        @DisplayName("완료/진행중 건수와 완료율을 계산한다")
        void calculatesCompletionRate() {
            YearMonth yearMonth = YearMonth.of(2026, 8);
            LoanContractResponse contract = loanContract(1L, OTHER_USER_ID, USER_ID);
            var loanSchedules = List.of(
                    new ContractDashboardQueryService.LoanScheduleContext(contract, schedule(1L, yearMonth.atDay(1), RepaymentScheduleStatus.PAID)),
                    new ContractDashboardQueryService.LoanScheduleContext(contract, schedule(2L, yearMonth.atDay(2), RepaymentScheduleStatus.PENDING))
            );
            SettlementContext closedSettlement = new SettlementContext(
                    settlement(2L, "정산", "OWNER", "CLOSED", yearMonth.atDay(3), LocalDateTime.now()),
                    BigDecimal.ZERO, BigDecimal.valueOf(1_000)
            );

            DashboardMonthlySummaryResponse result =
                    IntegrationDashboardAssembler.toMonthlySummary(loanSchedules, List.of(closedSettlement), yearMonth);

            assertThat(result.getCompletedTransactionCount()).isEqualTo(2);
            assertThat(result.getInProgressLoanRepaymentCount()).isEqualTo(1);
            assertThat(result.getInProgressSettlementCount()).isEqualTo(0);
            assertThat(result.getTransactionCompletionRate()).isEqualByComparingTo("66.67");
        }

        @Test
        @DisplayName("이번 달 거래가 없으면 완료율은 0이다")
        void returnsZeroRateWhenNoTransactions() {
            DashboardMonthlySummaryResponse result =
                    IntegrationDashboardAssembler.toMonthlySummary(List.of(), List.of(), YearMonth.of(2026, 8));

            assertThat(result.getTransactionCompletionRate()).isEqualByComparingTo("0");
            assertThat(result.getCompletedTransactionCount()).isEqualTo(0);
        }

        @Test
        @DisplayName("다른 달의 일정/정산은 집계에서 제외한다")
        void excludesOtherMonths() {
            YearMonth yearMonth = YearMonth.of(2026, 8);
            LoanContractResponse contract = loanContract(1L, OTHER_USER_ID, USER_ID);
            var loanSchedules = List.of(new ContractDashboardQueryService.LoanScheduleContext(
                    contract, schedule(1L, YearMonth.of(2026, 9).atDay(1), RepaymentScheduleStatus.PENDING)
            ));

            DashboardMonthlySummaryResponse result =
                    IntegrationDashboardAssembler.toMonthlySummary(loanSchedules, List.of(), yearMonth);

            assertThat(result.getInProgressLoanRepaymentCount()).isEqualTo(0);
        }
    }
}
