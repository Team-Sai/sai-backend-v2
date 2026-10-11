package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.assembler.ContractDashboardAssembler;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardSummaryResponse;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

class ContractDashboardAssemblerTest {

    private static final Long USER_ID = 1L;
    private static final YearMonth TARGET_MONTH = YearMonth.of(2026, 10);

    @Test
    @DisplayName("이전 달 부분 상환은 잔여액으로 계산하고 PENDING 상태도 포함한다")
    void calculatesPreviousMonthUnpaidUsingRemainingAmount() {
        ContractDashboardRowResponse row = ContractDashboardAssembler.toRow(
                contract(10L, 2L, USER_ID),
                List.of(
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                100_000, 80_000,
                                LocalDate.of(2026, 9, 25)),
                        schedule(
                                RepaymentScheduleStatus.OVERDUE,
                                50_000, 30_000,
                                LocalDate.of(2026, 8, 25)),
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                200_000, 150_000,
                                LocalDate.of(2026, 10, 15))
                ),
                USER_ID,
                TARGET_MONTH
        );

        assertThat(row.getOverdueAmount())
                .isEqualByComparingTo("110000");

        assertThat(row.getThisMonthDueAmount())
                .isEqualByComparingTo("150000");

        assertThat(row.getTotalRemainingAmount())
                .isEqualByComparingTo("260000");
    }

    @Test
    @DisplayName("월 경계로 이전 미상환과 이번 달 미상환을 구분한다")
    void separatesAmountsAtMonthBoundaries() {
        ContractDashboardRowResponse row = ContractDashboardAssembler.toRow(
                contract(10L, 2L, USER_ID),
                List.of(
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                10_000, 10_000,
                                LocalDate.of(2026, 9, 30)),
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                20_000, 20_000,
                                LocalDate.of(2026, 10, 1)),
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                30_000, 30_000,
                                LocalDate.of(2026, 10, 31)),
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                40_000, 40_000,
                                LocalDate.of(2026, 11, 1))
                ),
                USER_ID,
                TARGET_MONTH
        );

        assertThat(row.getOverdueAmount())
                .isEqualByComparingTo("10000");

        assertThat(row.getThisMonthDueAmount())
                .isEqualByComparingTo("50000");

        // 다음 달 금액은 전체 잔여액에는 포함된다.
        assertThat(row.getTotalRemainingAmount())
                .isEqualByComparingTo("100000");
    }

    @Test
    @DisplayName("완납 및 상각 회차는 이전 미상환과 이번 달 금액에서 제외한다")
    void excludesPaidAndWrittenOffSchedules() {
        ContractDashboardRowResponse row = ContractDashboardAssembler.toRow(
                contract(10L, 2L, USER_ID),
                List.of(
                        schedule(
                                RepaymentScheduleStatus.PAID,
                                100_000, 100_000,
                                LocalDate.of(2026, 9, 25)),
                        schedule(
                                RepaymentScheduleStatus.WRITTEN_OFF,
                                200_000, 200_000,
                                LocalDate.of(2026, 9, 26)),
                        schedule(
                                RepaymentScheduleStatus.PAID,
                                300_000, 300_000,
                                LocalDate.of(2026, 10, 15)),
                        schedule(
                                RepaymentScheduleStatus.WRITTEN_OFF,
                                400_000, 400_000,
                                LocalDate.of(2026, 10, 25))
                ),
                USER_ID,
                TARGET_MONTH
        );

        assertThat(row.getOverdueAmount())
                .isEqualByComparingTo("0");

        assertThat(row.getThisMonthDueAmount())
                .isEqualByComparingTo("0");

        assertThat(row.getTotalRemainingAmount())
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("이번 달 금액이 0원이면 다음 달 금액으로 대체하지 않는다")
    void doesNotFallbackToNextMonthAmount() {
        ContractDashboardRowResponse row = ContractDashboardAssembler.toRow(
                contract(10L, 2L, USER_ID),
                List.of(
                        schedule(
                                RepaymentScheduleStatus.OVERDUE,
                                80_000, 80_000,
                                LocalDate.of(2026, 9, 25)),
                        schedule(
                                RepaymentScheduleStatus.PENDING,
                                300_000, 300_000,
                                LocalDate.of(2026, 11, 15))
                ),
                USER_ID,
                TARGET_MONTH
        );

        ContractDashboardSummaryResponse summary =
                ContractDashboardAssembler.buildSummary(List.of(row));

        assertThat(summary.getThisMonthDueAmount())
                .isEqualByComparingTo("0");

        assertThat(summary.getPayableThisMonthAmount())
                .isEqualByComparingTo("0");

        assertThat(summary.getPayableOverdueAmount())
                .isEqualByComparingTo("80000");

        assertThat(summary.getPayableTotalRequiredAmount())
                .isEqualByComparingTo("80000");

        assertThat(summary.getDueMonth()).isNull();
        assertThat(summary.getPayableDueMonth()).isNull();
    }

    @Test
    @DisplayName("받을 돈과 갚을 돈의 이번 달 금액 및 이전 미상환액을 분리한다")
    void separatesReceivableAndPayableAmounts() {
        ContractDashboardRowResponse debtorRow =
                ContractDashboardAssembler.toRow(
                        contract(10L, 2L, USER_ID),
                        List.of(
                                schedule(
                                        RepaymentScheduleStatus.OVERDUE,
                                        100_000, 80_000,
                                        LocalDate.of(2026, 9, 25)),
                                schedule(
                                        RepaymentScheduleStatus.PENDING,
                                        420_000, 420_000,
                                        LocalDate.of(2026, 10, 15))
                        ),
                        USER_ID,
                        TARGET_MONTH
                );

        ContractDashboardRowResponse creditorRow =
                ContractDashboardAssembler.toRow(
                        contract(20L, USER_ID, 3L),
                        List.of(
                                schedule(
                                        RepaymentScheduleStatus.OVERDUE,
                                        50_000, 50_000,
                                        LocalDate.of(2026, 9, 20)),
                                schedule(
                                        RepaymentScheduleStatus.PENDING,
                                        300_000, 300_000,
                                        LocalDate.of(2026, 10, 20))
                        ),
                        USER_ID,
                        TARGET_MONTH
                );

        ContractDashboardSummaryResponse summary =
                ContractDashboardAssembler.buildSummary(
                        List.of(debtorRow, creditorRow)
                );

        assertThat(summary.getThisMonthDueAmount())
                .isEqualByComparingTo("720000");

        assertThat(summary.getReceivableThisMonthAmount())
                .isEqualByComparingTo("300000");

        assertThat(summary.getPayableThisMonthAmount())
                .isEqualByComparingTo("420000");

        assertThat(summary.getReceivableOverdueAmount())
                .isEqualByComparingTo("50000");

        assertThat(summary.getPayableOverdueAmount())
                .isEqualByComparingTo("80000");

        // 받을 돈으로 갚을 돈을 상계하지 않는다.
        assertThat(summary.getPayableTotalRequiredAmount())
                .isEqualByComparingTo("500000");
    }

    private LoanContractResponse contract(
            Long contractId,
            Long creditorId,
            Long debtorId
    ) {
        return LoanContractResponse.builder()
                .contractId(contractId)
                .contractAlias("계약-" + contractId)
                .status(ContractStatus.COMPLETED)
                .creditorId(creditorId)
                .debtorId(debtorId)
                .principalAmount(BigDecimal.valueOf(1_000_000))
                .maturityDate(LocalDate.of(2027, 10, 31))
                .build();
    }

    private RepaymentScheduleWithRemainingProjection schedule(
            RepaymentScheduleStatus status,
            long scheduledAmount,
            long remainingAmount,
            LocalDate dueDate
    ) {
        RepaymentScheduleWithRemainingProjection schedule =
                mock(RepaymentScheduleWithRemainingProjection.class);

        lenient().when(schedule.getStatus()).thenReturn(status);
        lenient().when(schedule.getDueDate()).thenReturn(dueDate);

        lenient().when(schedule.getTotalPaymentDue())
                .thenReturn(BigDecimal.valueOf(scheduledAmount));

        lenient().when(schedule.getRemainingPaymentAmount())
                .thenReturn(BigDecimal.valueOf(remainingAmount));

        return schedule;
    }
}