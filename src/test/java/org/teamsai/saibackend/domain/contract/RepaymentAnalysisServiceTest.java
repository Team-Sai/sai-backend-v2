package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAnalysisContext;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentCandidate;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisService;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.contract.type.ContractStatus;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RepaymentAnalysisServiceTest {

    private static final Long USER_ID = 1L;

    @Mock
    private LoanContractService loanContractService;

    @Mock
    private RepaymentScheduleService repaymentScheduleService;

    private RepaymentAnalysisService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-10-20T00:00:00Z"),
                ZoneId.of("Asia/Seoul")
        );

        service = new RepaymentAnalysisService(
                loanContractService,
                repaymentScheduleService,
                clock
        );
    }

    @Test
    @DisplayName("부분 상환을 반영하고 이번 달 연체와 미래 회차를 구분한다")
    void analyzesAmountsAndCandidates() {
        LoanContractResponse contract =
                contract(10L, null, ContractStatus.COMPLETED, 2L, USER_ID);

        RepaymentScheduleWithRemainingProjection previous =
                schedule(101L, RepaymentScheduleStatus.PENDING,
                        100_000, 80_000, "2026-09-25");

        RepaymentScheduleWithRemainingProjection currentPastDue =
                schedule(102L, RepaymentScheduleStatus.PENDING,
                        200_000, 150_000, "2026-10-15");

        RepaymentScheduleWithRemainingProjection currentUpcoming =
                schedule(103L, RepaymentScheduleStatus.PENDING,
                        220_000, 220_000, "2026-10-25");

        RepaymentScheduleWithRemainingProjection future =
                schedule(104L, RepaymentScheduleStatus.PENDING,
                        300_000, 300_000, "2026-11-15");

        RepaymentScheduleWithRemainingProjection paid =
                schedule(105L, RepaymentScheduleStatus.PAID,
                        500_000, 500_000, "2026-09-10");

        RepaymentScheduleWithRemainingProjection writtenOff =
                schedule(106L, RepaymentScheduleStatus.WRITTEN_OFF,
                        600_000, 600_000, "2026-10-10");

        RepaymentScheduleWithRemainingProjection zeroRemaining =
                schedule(107L, RepaymentScheduleStatus.PENDING,
                        50_000, 0, "2026-10-18");

        List<RepaymentScheduleWithRemainingProjection> schedules = List.of(
                future, currentUpcoming, paid, previous,
                writtenOff, currentPastDue, zeroRemaining
        );

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(contract));

        when(repaymentScheduleService.getSchedulesByContractIds(List.of(10L)))
                .thenReturn(Map.of(10L, schedules));

        RepaymentAnalysisContext result = service.analyze(USER_ID);

        assertThat(result.analysisDate())
                .isEqualTo(LocalDate.of(2026, 10, 20));
        assertThat(result.targetMonth())
                .isEqualTo(YearMonth.of(2026, 10));

        assertThat(result.payableThisMonthAmount())
                .isEqualByComparingTo("370000");
        assertThat(result.overdueAmount())
                .isEqualByComparingTo("80000");
        assertThat(result.totalRequiredAmount())
                .isEqualByComparingTo("450000");
        assertThat(result.totalRemainingAmount())
                .isEqualByComparingTo("750000");

        assertThat(result.candidates())
                .extracting(RepaymentCandidate::scheduleId)
                .containsExactly(101L, 102L, 103L);

        assertThat(result.candidates())
                .extracting(RepaymentCandidate::pastDue)
                .containsExactly(true, true, false);

        assertThat(result.candidates().get(0).contractId()).isEqualTo(10L);
        assertThat(result.candidates().get(0).contractName())
                .isEqualTo("계약-10");

        BigDecimal candidateTotal = result.candidates().stream()
                .map(RepaymentCandidate::remainingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(candidateTotal)
                .isEqualByComparingTo(result.totalRequiredAmount());
    }

    @Test
    @DisplayName("받을 계약과 구버전 및 미체결 계약을 제외한다")
    void excludesCreditorSupersededAndIncompleteContracts() {
        LoanContractResponse oldContract =
                contract(10L, null, ContractStatus.COMPLETED, 2L, USER_ID);

        LoanContractResponse currentContract =
                contract(11L, 10L, ContractStatus.COMPLETED, 2L, USER_ID);

        LoanContractResponse creditorContract =
                contract(12L, null, ContractStatus.COMPLETED, USER_ID, 3L);

        LoanContractResponse pendingContract =
                contract(13L, null, ContractStatus.PENDING, 2L, USER_ID);

        RepaymentScheduleWithRemainingProjection schedule =
                schedule(111L, RepaymentScheduleStatus.PENDING,
                        200_000, 200_000, "2026-10-25");

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(
                        oldContract,
                        currentContract,
                        creditorContract,
                        pendingContract
                ));

        when(repaymentScheduleService.getSchedulesByContractIds(List.of(11L)))
                .thenReturn(Map.of(11L, List.of(schedule)));

        RepaymentAnalysisContext result = service.analyze(USER_ID);

        assertThat(result.payableThisMonthAmount())
                .isEqualByComparingTo("200000");

        assertThat(result.candidates())
                .extracting(RepaymentCandidate::contractId)
                .containsExactly(11L);

        verify(repaymentScheduleService)
                .getSchedulesByContractIds(List.of(11L));
    }

    @Test
    @DisplayName("이번 달 금액이 없으면 다음 달 금액으로 대체하지 않는다")
    void doesNotFallbackToFutureMonth() {
        LoanContractResponse contract =
                contract(10L, null, ContractStatus.COMPLETED, 2L, USER_ID);

        RepaymentScheduleWithRemainingProjection previous =
                schedule(101L, RepaymentScheduleStatus.OVERDUE,
                        80_000, 80_000, "2026-09-25");

        RepaymentScheduleWithRemainingProjection future =
                schedule(102L, RepaymentScheduleStatus.PENDING,
                        300_000, 300_000, "2026-11-15");

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(contract));

        when(repaymentScheduleService.getSchedulesByContractIds(List.of(10L)))
                .thenReturn(Map.of(10L, List.of(previous, future)));

        RepaymentAnalysisContext result = service.analyze(USER_ID);

        assertThat(result.payableThisMonthAmount())
                .isEqualByComparingTo("0");
        assertThat(result.overdueAmount())
                .isEqualByComparingTo("80000");
        assertThat(result.totalRequiredAmount())
                .isEqualByComparingTo("80000");
        assertThat(result.totalRemainingAmount())
                .isEqualByComparingTo("380000");

        assertThat(result.candidates())
                .extracting(RepaymentCandidate::scheduleId)
                .containsExactly(101L);
    }

    @Test
    @DisplayName("갚을 계약이 없으면 0원과 빈 후보 목록을 반환한다")
    void returnsEmptyContextWhenNoDebtorContracts() {
        LoanContractResponse creditorContract =
                contract(10L, null, ContractStatus.COMPLETED, USER_ID, 2L);

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(creditorContract));

        RepaymentAnalysisContext result = service.analyze(USER_ID);

        assertThat(result.payableThisMonthAmount())
                .isEqualByComparingTo("0");
        assertThat(result.overdueAmount())
                .isEqualByComparingTo("0");
        assertThat(result.totalRequiredAmount())
                .isEqualByComparingTo("0");
        assertThat(result.totalRemainingAmount())
                .isEqualByComparingTo("0");
        assertThat(result.candidates()).isEmpty();

        verifyNoInteractions(repaymentScheduleService);
    }

    @Test
    @DisplayName("분석일 당일 납기는 연체로 표시하지 않는다")
    void doesNotMarkTodayAsPastDue() {
        LoanContractResponse contract =
                contract(10L, null, ContractStatus.COMPLETED, 2L, USER_ID);

        RepaymentScheduleWithRemainingProjection dueToday =
                schedule(101L, RepaymentScheduleStatus.PENDING,
                        100_000, 100_000, "2026-10-20");

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(contract));

        when(repaymentScheduleService.getSchedulesByContractIds(List.of(10L)))
                .thenReturn(Map.of(10L, List.of(dueToday)));

        RepaymentAnalysisContext result = service.analyze(USER_ID);

        assertThat(result.candidates()).singleElement().satisfies(candidate -> {
            assertThat(candidate.pastDue()).isFalse();
            assertThat(candidate.dueDate())
                    .isEqualTo(LocalDate.of(2026, 10, 20));
        });
    }

    private LoanContractResponse contract(
            Long id,
            Long previousId,
            ContractStatus status,
            Long creditorId,
            Long debtorId
    ) {
        return LoanContractResponse.builder()
                .contractId(id)
                .previousContractId(previousId)
                .contractAlias("계약-" + id)
                .status(status)
                .creditorId(creditorId)
                .debtorId(debtorId)
                .build();
    }

    private RepaymentScheduleWithRemainingProjection schedule(
            Long id,
            RepaymentScheduleStatus status,
            long scheduledAmount,
            long remainingAmount,
            String dueDate
    ) {
        RepaymentScheduleWithRemainingProjection schedule =
                mock(RepaymentScheduleWithRemainingProjection.class);

        lenient().when(schedule.getScheduleId()).thenReturn(id);
        lenient().when(schedule.getStatus()).thenReturn(status);
        lenient().when(schedule.getDueDate())
                .thenReturn(LocalDate.parse(dueDate));
        lenient().when(schedule.getTotalPaymentDue())
                .thenReturn(BigDecimal.valueOf(scheduledAmount));
        lenient().when(schedule.getRemainingPaymentAmount())
                .thenReturn(BigDecimal.valueOf(remainingAmount));

        return schedule;
    }
}