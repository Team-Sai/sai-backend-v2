package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.contract.dto.request.ContractStatus;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardRowResponse;
import org.teamsai.saibackend.domain.contract.dto.response.ContractDashboardResponse;
import org.teamsai.saibackend.domain.contract.service.ContractDashboardQueryService;
import org.teamsai.saibackend.domain.contract.type.ContractRole;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractDashboardQueryServiceTest {

    @Mock
    private LoanContractService loanContractService;

    @Mock
    private RepaymentScheduleService repaymentScheduleService;

    @InjectMocks
    private ContractDashboardQueryService dashboardService;

    private static final Long USER_ID = 1L;

    @Test
    @DisplayName("구버전(V1)과 미완료 계약은 목록에서 제외된다")
    void getDashboard_excludesSupersededAndIncompleteContracts() {
        LoanContractResponse v1Superseded = buildContract(10L, null, ContractStatus.COMPLETED, "생활비-구버전", 1L, 2L);
        LoanContractResponse v2Current = buildContract(11L, 10L, ContractStatus.COMPLETED, "생활비-신버전", 1L, 2L);
        LoanContractResponse incomplete = buildContract(12L, null, ContractStatus.PENDING, "미완료계약", 1L, 5L);

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(v1Superseded, v2Current, incomplete));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(11L)))
                .thenReturn(Map.of());

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);

        assertThat(response.getContracts()).hasSize(1);
        assertThat(response.getContracts().get(0).getContractId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("총 잔액, 이번달 낼 돈, 납부상태를 정확히 계산한다")
    void getDashboard_calculatesAmountsCorrectly() {
        LoanContractResponse borrowedContract = buildContract(20L, null, ContractStatus.COMPLETED, "차량구입", 3L, 1L);

        List<RepaymentScheduleWithRemainingProjection> schedules = List.of(
                buildSchedule(RepaymentScheduleStatus.PAID, 500_000, LocalDate.now().minusMonths(1)),
                buildSchedule(RepaymentScheduleStatus.PENDING, 600_000, LocalDate.now()),
                buildSchedule(RepaymentScheduleStatus.PENDING, 650_000, LocalDate.now().plusMonths(1))
        );

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(borrowedContract));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(20L)))
                .thenReturn(Map.of(20L, schedules));
        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);
        ContractDashboardRowResponse row = response.getContracts().get(0);

        assertThat(row.getTotalRemainingAmount()).isEqualByComparingTo("1250000");
        assertThat(row.getThisMonthDueAmount()).isEqualByComparingTo("600000");
        assertThat(row.getContractStatus().name()).isEqualTo("ONGOING");
    }

    @Test
    @DisplayName("상각된 상환 회차는 상환 완료로 표시된다")
    void getDashboard_treatsWrittenOffSchedulesAsCompletedRepayment() {
        LoanContractResponse contract = buildContract(
                22L, null, ContractStatus.COMPLETED, "상각 계약", 1L, 2L
        );

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(contract));
        RepaymentScheduleWithRemainingProjection writtenOffSchedule =
                buildSchedule(RepaymentScheduleStatus.WRITTEN_OFF, 500_000, LocalDate.now());
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(22L)))
                .thenReturn(Map.of(22L, List.of(writtenOffSchedule)));

        ContractDashboardResponse response = dashboardService.getDashboard(
                USER_ID, null, "ALL", null, null, 1
        );

        assertThat(response.getContracts().get(0).getRepaymentStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("부분 납부된 회차는 실제 잔여 납부금액으로 대시보드에 표시된다")
    void getDashboard_usesRemainingPaymentAmountAfterPartialPayment() {
        LoanContractResponse contract = buildContract(
                21L, null, ContractStatus.COMPLETED, "부분납부계약", 3L, 1L
        );
        RepaymentScheduleWithRemainingProjection schedule = mock(RepaymentScheduleWithRemainingProjection.class);
        when(schedule.getStatus()).thenReturn(RepaymentScheduleStatus.PENDING);
        when(schedule.getTotalPaymentDue()).thenReturn(BigDecimal.valueOf(50_000));
        when(schedule.getDueDate()).thenReturn(LocalDate.now());
        when(schedule.getRemainingPaymentAmount()).thenReturn(BigDecimal.valueOf(20_000));

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(contract));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(21L)))
                .thenReturn(Map.of(21L, List.of(schedule)));

        ContractDashboardResponse response = dashboardService.getDashboard(
                USER_ID, null, "ALL", null, null, 1
        );

        assertThat(response.getContracts().get(0).getTotalRemainingAmount())
                .isEqualByComparingTo("20000");
    }

    @Test
    @DisplayName("빌려준 돈, 빌린 돈, 이번달 상환예정금이 정확히 합산되고 defaultFilter가 결정된다")
    void getDashboard_buildsSummaryCorrectly() {
        LoanContractResponse lentContract = buildContract(30L, null, ContractStatus.COMPLETED, "빌려준계약", 1L, 2L);
        LoanContractResponse borrowedContract = buildContract(31L, null, ContractStatus.COMPLETED, "빌린계약", 3L, 1L);

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(lentContract, borrowedContract));
        RepaymentScheduleWithRemainingProjection lentSchedule =
                buildSchedule(RepaymentScheduleStatus.PENDING, 200_000, LocalDate.now().plusMonths(1));
        RepaymentScheduleWithRemainingProjection borrowedSchedule =
                buildSchedule(RepaymentScheduleStatus.PENDING, 1_250_000, LocalDate.now());
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(30L, 31L)))
                .thenReturn(Map.of(
                        30L, List.of(lentSchedule),
                        31L, List.of(borrowedSchedule)
                ));

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);

        assertThat(response.getSummary().getTotalContractCount()).isEqualTo(2);
        assertThat(response.getSummary().getTotalLentAmount()).isEqualByComparingTo("200000");
        assertThat(response.getSummary().getTotalBorrowedAmount()).isEqualByComparingTo("1250000");
        assertThat(response.getSummary().getThisMonthDueAmount()).isEqualByComparingTo("1250000");
        assertThat(response.getSummary().getDefaultFilter()).isEqualTo("BORROWED");
    }

    @Test
    @DisplayName("계약 별칭으로 검색하면 일치하는 계약만 반환된다")
    void getDashboard_filtersByKeyword() {
        LoanContractResponse target = buildContract(40L, null, ContractStatus.COMPLETED, "생활비 대출", 1L, 2L);
        LoanContractResponse other = buildContract(41L, null, ContractStatus.COMPLETED, "차량 구입", 1L, 3L);

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(target, other));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(40L, 41L)))
                .thenReturn(Map.of());

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, "생활비", "ALL", null, null, 1);

        assertThat(response.getContracts()).hasSize(1);
        assertThat(response.getContracts().get(0).getContractAlias()).isEqualTo("생활비 대출");
    }

    @Test
    @DisplayName("역할 필터(LENT)를 적용하면 채권자인 계약만 반환된다")
    void getDashboard_filtersByRole() {
        LoanContractResponse lent = buildContract(50L, null, ContractStatus.COMPLETED, "빌려준계약", 1L, 2L);
        LoanContractResponse borrowed = buildContract(51L, null, ContractStatus.COMPLETED, "빌린계약", 3L, 1L);

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(lent, borrowed));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(50L, 51L)))
                .thenReturn(Map.of());

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "LENT", null, null, 1);

        assertThat(response.getContracts()).hasSize(1);
        assertThat(response.getContracts().get(0).getContractId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("금액 큰 순 정렬을 적용하면 잔액이 큰 계약이 먼저 온다")
    void getDashboard_sortsByAmountDesc() {
        LoanContractResponse small = buildContract(60L, null, ContractStatus.COMPLETED, "소액계약", 1L, 2L);
        LoanContractResponse large = buildContract(61L, null, ContractStatus.COMPLETED, "고액계약", 1L, 3L);

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(small, large));

        RepaymentScheduleWithRemainingProjection smallSchedule =
                buildSchedule(RepaymentScheduleStatus.PENDING, 100_000, LocalDate.now());
        RepaymentScheduleWithRemainingProjection largeSchedule =
                buildSchedule(RepaymentScheduleStatus.PENDING, 9_000_000, LocalDate.now());
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(60L, 61L)))
                .thenReturn(Map.of(
                        60L, List.of(smallSchedule),
                        61L, List.of(largeSchedule)
                ));
        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, "AMOUNT_DESC", 1);

        assertThat(response.getContracts().get(0).getContractId()).isEqualTo(61L);
        assertThat(response.getContracts().get(1).getContractId()).isEqualTo(60L);
    }

    @Test
    void getDashboard_sortsByCreatedAtOnlyWhenIntegrationSortIsRequested() {
        LoanContractResponse newerLowerId = buildContract(60L, null, ContractStatus.COMPLETED, "new", 1L, 2L)
                .toBuilder().createdAt(LocalDateTime.of(2026, 8, 2, 10, 0)).build();
        LoanContractResponse olderHigherId = buildContract(61L, null, ContractStatus.COMPLETED, "old", 1L, 3L)
                .toBuilder().createdAt(LocalDateTime.of(2026, 8, 1, 10, 0)).build();
        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(newerLowerId, olderHigherId));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(60L, 61L)))
                .thenReturn(Map.of());

        ContractDashboardResponse integration = dashboardService.getDashboard(
                USER_ID, null, "ALL", null, "CREATED_DESC", 1
        );
        ContractDashboardResponse existingDefault = dashboardService.getDashboard(
                USER_ID, null, "ALL", null, null, 1
        );

        assertThat(integration.getContracts()).extracting(ContractDashboardRowResponse::getContractId)
                .containsExactly(60L, 61L);
        assertThat(existingDefault.getContracts()).extracting(ContractDashboardRowResponse::getContractId)
                .containsExactly(61L, 60L);
    }

    @Test
    void getIntegrationDashboardDataReusesEachContractsSchedules() {
        LoanContractResponse contract = buildContract(
                62L, null, ContractStatus.COMPLETED, "통합 대시보드 계약", 1L, 2L
        );
        RepaymentScheduleWithRemainingProjection schedule = buildSchedule(
                RepaymentScheduleStatus.PENDING, 500_000, LocalDate.now()
        );
        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(contract));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(62L)))
                .thenReturn(Map.of(62L, List.of(schedule)));

        ContractDashboardQueryService.IntegrationDashboardData result =
                dashboardService.getIntegrationDashboardData(USER_ID);

        assertThat(result.dashboard().getContracts()).hasSize(1);
        assertThat(result.loanSchedules()).singleElement().satisfies(context -> {
            assertThat(context.contract().getContractId()).isEqualTo(62L);
            assertThat(context.schedule()).isSameAs(schedule);
        });
        verify(repaymentScheduleService, times(1)).getSchedulesByContractIds(List.of(62L));
    }

    @Test
    @DisplayName("페이지당 5개씩, totalPages가 올림 계산된다")
    void getDashboard_paginatesCorrectly() {
        List<LoanContractResponse> sixContracts = List.of(
                buildContract(70L, null, ContractStatus.COMPLETED, "계약1", 1L, 2L),
                buildContract(71L, null, ContractStatus.COMPLETED, "계약2", 1L, 2L),
                buildContract(72L, null, ContractStatus.COMPLETED, "계약3", 1L, 2L),
                buildContract(73L, null, ContractStatus.COMPLETED, "계약4", 1L, 2L),
                buildContract(74L, null, ContractStatus.COMPLETED, "계약5", 1L, 2L),
                buildContract(75L, null, ContractStatus.COMPLETED, "계약6", 1L, 2L)
        );

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(sixContracts);
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(70L, 71L, 72L, 73L, 74L, 75L)))
                .thenReturn(Map.of());

        ContractDashboardResponse page1 = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);
        ContractDashboardResponse page2 = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 2);

        assertThat(page1.getContracts()).hasSize(5);
        assertThat(page2.getContracts()).hasSize(1);
        assertThat(page1.getTotalPages()).isEqualTo(2);
        assertThat(page1.getTotalCount()).isEqualTo(6);
    }

    private LoanContractResponse buildContract(
            Long contractId, Long previousContractId, ContractStatus status,
            String alias, Long creditorId, Long debtorId
    ) {
        return LoanContractResponse.builder()
                .contractId(contractId)
                .previousContractId(previousContractId)
                .status(status)
                .contractAlias(alias)
                .creditorId(creditorId)
                .debtorId(debtorId)
                .principalAmount(BigDecimal.valueOf(1_000_000))
                .interestRate(BigDecimal.valueOf(5))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.now().minusMonths(3))
                .maturityDate(LocalDate.now().plusMonths(9))
                .build();
    }

    private RepaymentScheduleWithRemainingProjection buildSchedule(RepaymentScheduleStatus status, long amount, LocalDate dueDate) {
        RepaymentScheduleWithRemainingProjection schedule = mock(RepaymentScheduleWithRemainingProjection.class);
        lenient().when(schedule.getStatus()).thenReturn(status);
        lenient().when(schedule.getTotalPaymentDue()).thenReturn(BigDecimal.valueOf(amount));
        lenient().when(schedule.getDueDate()).thenReturn(dueDate);
        return schedule;
    }

    @Test
    @DisplayName("sortType이 null이면 예외 없이 최신 계약순(contractId 내림차순)으로 처리된다")
    void getDashboard_handlesNullSortTypeSafely() {
        LoanContractResponse older = buildContract(80L, null, ContractStatus.COMPLETED, "먼저생성", 1L, 2L);
        LoanContractResponse newer = buildContract(81L, null, ContractStatus.COMPLETED, "나중생성", 1L, 2L);

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(older, newer));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(80L, 81L)))
                .thenReturn(Map.of());

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);

        assertThat(response.getContracts()).hasSize(2);
        assertThat(response.getContracts().get(0).getContractId()).isEqualTo(81L);
        assertThat(response.getContracts().get(1).getContractId()).isEqualTo(80L);
    }

    @Test
    @DisplayName("page가 0 이하로 들어와도 예외 없이 1페이지로 처리된다")
    void getDashboard_handlesInvalidPageSafely() {
        LoanContractResponse contract = buildContract(90L, null, ContractStatus.COMPLETED, "테스트계약", 1L, 2L);

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(contract));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(90L)))
                .thenReturn(Map.of());

        ContractDashboardResponse responseZero = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 0);
        ContractDashboardResponse responseNegative = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, -5);

        assertThat(responseZero.getContracts()).hasSize(1);
        assertThat(responseNegative.getContracts()).hasSize(1);
    }

    @Test
    @DisplayName("빌려준 돈이 더 많으면 defaultFilter는 LENT다")
    void getDashboard_defaultFilterIsLentWhenLentIsGreater() {
        LoanContractResponse lentContract = buildContract(100L, null, ContractStatus.COMPLETED, "빌려준계약", 1L, 2L);
        LoanContractResponse borrowedContract = buildContract(101L, null, ContractStatus.COMPLETED, "빌린계약", 3L, 1L);

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(lentContract, borrowedContract));

        RepaymentScheduleWithRemainingProjection lentSchedule =
                buildSchedule(RepaymentScheduleStatus.PENDING, 5_000_000, LocalDate.now());
        RepaymentScheduleWithRemainingProjection borrowedSchedule =
                buildSchedule(RepaymentScheduleStatus.PENDING, 100_000, LocalDate.now());
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(100L, 101L)))
                .thenReturn(Map.of(
                        100L, List.of(lentSchedule),
                        101L, List.of(borrowedSchedule)
                ));

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);

        assertThat(response.getSummary().getDefaultFilter()).isEqualTo("LENT");
    }

    @Test
    @DisplayName("정렬 기준(역할순/구분순/상태순/마감일순/가나다순)이 각각 정확히 적용된다")
    void getDashboard_sortsByEachCriteriaCorrectly() {
        LoanContractResponse creditorContract = buildContract(110L, null, ContractStatus.COMPLETED, "가나다1", 1L, 2L);
        LoanContractResponse debtorContract = buildContract(111L, null, ContractStatus.COMPLETED, "나다라2", 3L, 1L);

        when(loanContractService.findContractsByUser(USER_ID))
                .thenReturn(List.of(debtorContract, creditorContract));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(111L, 110L)))
                .thenReturn(Map.of());

        ContractDashboardResponse alphabetSorted = dashboardService.getDashboard(USER_ID, null, "ALL", null, "ALPHABET", 1);
        assertThat(alphabetSorted.getContracts().get(0).getContractAlias()).isEqualTo("가나다1");

        ContractDashboardResponse roleSorted = dashboardService.getDashboard(USER_ID, null, "ALL", null, "ROLE", 1);
        assertThat(roleSorted.getContracts().get(0).getRole()).isEqualTo(ContractRole.CREDITOR);

        ContractDashboardResponse categorySorted = dashboardService.getDashboard(USER_ID, null, "ALL", null, "CATEGORY", 1);
        assertThat(categorySorted.getContracts().get(0).getCategory().name()).isEqualTo("RECEIVE");

        ContractDashboardResponse deadlineSorted = dashboardService.getDashboard(USER_ID, null, "ALL", null, "DEADLINE", 1);
        assertThat(deadlineSorted.getContracts()).hasSize(2);
    }

    @Test
    @DisplayName("잔액이 0이면 납부상태는 PAID다")
    void getDashboard_paymentStatusIsPaidWhenNoRemaining() {
        LoanContractResponse contract = buildContract(120L, null, ContractStatus.COMPLETED, "완납계약", 1L, 2L);

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(contract));
        RepaymentScheduleWithRemainingProjection paidSchedule =
                buildSchedule(RepaymentScheduleStatus.PAID, 500_000, LocalDate.now().minusMonths(1));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(120L)))
                .thenReturn(Map.of(120L, List.of(paidSchedule)));

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);
        ContractDashboardRowResponse row = response.getContracts().get(0);

        assertThat(row.getTotalRemainingAmount()).isEqualByComparingTo("0");
        assertThat(row.getPaymentStatus().name()).isEqualTo("PAID");
    }

    @Test
    @DisplayName("잔여상환액이 있고 이번달 낼 돈이 없어도 계약상태는 ONGOING이다")
    void getDashboard_returnsOngoingWhenNoDueThisMonthButBalanceRemains() {
        LoanContractResponse contract = buildContract(120L, null, ContractStatus.COMPLETED, "이번달납부없음", 1L, 2L);

        List<RepaymentScheduleWithRemainingProjection> schedules = List.of(
                buildSchedule(RepaymentScheduleStatus.PENDING, 500_000, LocalDate.now().plusMonths(2))
        );

        when(loanContractService.findContractsByUser(USER_ID)).thenReturn(List.of(contract));
        when(repaymentScheduleService.getSchedulesByContractIds(List.of(120L)))
                .thenReturn(Map.of(120L, schedules));

        ContractDashboardResponse response = dashboardService.getDashboard(USER_ID, null, "ALL", null, null, 1);
        ContractDashboardRowResponse row = response.getContracts().get(0);

        assertThat(row.getTotalRemainingAmount()).isEqualByComparingTo("500000");
        assertThat(row.getThisMonthDueAmount()).isEqualByComparingTo("0");
        assertThat(row.getContractStatus().name()).isEqualTo("ONGOING");
    }
}
