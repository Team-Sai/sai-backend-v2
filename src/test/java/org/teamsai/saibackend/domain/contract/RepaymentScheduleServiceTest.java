package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.contract.service.EarlyRepaymentService;
import org.teamsai.saibackend.domain.contract.type.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentSchedule;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleGenerateService;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class RepaymentScheduleServiceTest {

    private static final Long V1_CONTRACT_ID = 1L;
    private static final Long V2_CONTRACT_ID = 2L;

    @Mock
    private RepaymentScheduleRepository repaymentScheduleRepository;

    @Mock
    private LoanContractService loanContractService;

    @Mock
    private EarlyRepaymentService earlyRepaymentService;

    @Spy
    private RepaymentScheduleGenerateService repaymentScheduleGenerateService = new RepaymentScheduleGenerateService();

    @InjectMocks
    private RepaymentScheduleService repaymentScheduleService;

    @Captor
    private ArgumentCaptor<List<RepaymentSchedule>> schedulesCaptor;

    private RepaymentSchedule buildRow(int sequence, RepaymentScheduleStatus status, String totalPaymentDue) {
        RepaymentSchedule entity = new RepaymentSchedule(
                1L,
                sequence,
                LocalDate.of(2026, 1, 1),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(totalPaymentDue),
                BigDecimal.ZERO,
                status,
                LocalDateTime.now()
        );
        ReflectionTestUtils.setField(entity, "scheduleId", (long) sequence);
        return entity;
    }

    @Test
    @DisplayName("계약 조건대로 스케줄을 계산해서 12건 저장한다")
    void generateSchedule_savesAllRows() {
        Long contractId = 1L;
        LoanContractResponse contract = LoanContractResponse.builder()
                .contractId(contractId)
                .principalAmount(BigDecimal.valueOf(10_000_000))
                .interestRate(BigDecimal.valueOf(12))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2027, 1, 1))
                .repaymentDay(1)
                .build();

        when(loanContractService.getContractForInternalUse(contractId)).thenReturn(contract);

        repaymentScheduleService.generateSchedule(contractId);

        verify(repaymentScheduleRepository).saveAll(
                argThat((List<RepaymentSchedule> list) -> list.size() == 12)
        );
    }

    @Test
    @DisplayName("존재하지 않는 계약이면 예외를 던지고 저장하지 않는다")
    void generateSchedule_throwsWhenContractNotFound() {
        Long contractId = 999L;
        when(loanContractService.getContractForInternalUse(contractId))
                .thenThrow(LoanContractErrorCode.CONTRACT_NOT_FOUND.toException());

        assertThatThrownBy(() -> repaymentScheduleService.generateSchedule(contractId))
                .isInstanceOf(DomainException.class);

        verify(repaymentScheduleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("대출 기간이 1개월 미만이면 예외를 던지고 저장하지 않는다")
    void generateSchedule_throwsWhenPeriodLessThanOneMonth() {
        Long contractId = 1L;
        LoanContractResponse contract = LoanContractResponse.builder()
                .contractId(contractId)
                .principalAmount(BigDecimal.valueOf(10_000_000))
                .interestRate(BigDecimal.valueOf(12))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.of(2026, 1, 1))
                .maturityDate(LocalDate.of(2026, 1, 15))
                .repaymentDay(1)
                .build();

        when(loanContractService.getContractForInternalUse(contractId)).thenReturn(contract);

        assertThatThrownBy(() -> repaymentScheduleService.generateSchedule(contractId))
                .isInstanceOf(DomainException.class);

        verify(repaymentScheduleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("계약 생성 시 계약의 상환일로 회차 날짜를 만든다")
    void generateSchedule_usesContractRepaymentDay() {
        Long contractId = 1L;
        when(loanContractService.getContractForInternalUse(contractId))
                .thenReturn(buildContract(contractId, 12));

        repaymentScheduleService.generateSchedule(contractId);

        verify(repaymentScheduleRepository).saveAll(schedulesCaptor.capture());
        assertThat(schedulesCaptor.getValue().get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 12));
    }

    @Test
    @DisplayName("계약 변경 시 납부 회차 이후 남은 회차 수가 줄지 않는다")
    void generateChangedSchedule_keepsRemainingRoundCount() {
        givenChangedContract(12, List.of(buildPaidRow(1, LocalDate.of(2026, 12, 12), "9000000")));

        repaymentScheduleService.generateChangedSchedule(V1_CONTRACT_ID, V2_CONTRACT_ID);

        verify(repaymentScheduleRepository).saveAll(schedulesCaptor.capture());
        List<RepaymentSchedule> saved = schedulesCaptor.getValue();
        assertThat(saved).hasSize(11);
        assertThat(saved.get(0).getDueDate()).isEqualTo(LocalDate.of(2027, 1, 12));
        assertThat(saved.get(10).getDueDate()).isEqualTo(LocalDate.of(2027, 11, 11));
    }

    @Test
    @DisplayName("계약 변경 시 변경된 계약의 상환일로 회차 날짜를 만든다")
    void generateChangedSchedule_usesChangedRepaymentDay() {
        givenChangedContract(20, List.of(buildPaidRow(1, LocalDate.of(2026, 12, 12), "9000000")));

        repaymentScheduleService.generateChangedSchedule(V1_CONTRACT_ID, V2_CONTRACT_ID);

        verify(repaymentScheduleRepository).saveAll(schedulesCaptor.capture());
        assertThat(schedulesCaptor.getValue().get(0).getDueDate()).isEqualTo(LocalDate.of(2027, 1, 20));
    }

    @Test
    @DisplayName("납부 회차 없이 계약을 변경하면 지급 월의 다음 달부터 전체 회차를 만든다")
    void generateChangedSchedule_withoutPaidRoundStartsFromStartDate() {
        givenChangedContract(12, List.of());

        repaymentScheduleService.generateChangedSchedule(V1_CONTRACT_ID, V2_CONTRACT_ID);

        verify(repaymentScheduleRepository).saveAll(schedulesCaptor.capture());
        List<RepaymentSchedule> saved = schedulesCaptor.getValue();
        assertThat(saved).hasSize(12);
        assertThat(saved.get(0).getDueDate()).isEqualTo(LocalDate.of(2026, 12, 12));
    }

    @Test
    @DisplayName("계약 변경 시 중도상환으로 줄어든 원금을 시작 원금으로 쓴다")
    void generateChangedSchedule_keepsEarlyRepaidPrincipal() {
        RepaymentSchedule pending = new RepaymentSchedule(
                V1_CONTRACT_ID, 1, LocalDate.of(2026, 12, 12),
                new BigDecimal("200000"), BigDecimal.ZERO, new BigDecimal("200000"),
                new BigDecimal("1800000"), RepaymentScheduleStatus.PENDING, LocalDateTime.now());
        givenChangedContract(12, List.of(pending));

        repaymentScheduleService.generateChangedSchedule(V1_CONTRACT_ID, V2_CONTRACT_ID);

        verify(repaymentScheduleRepository).saveAll(schedulesCaptor.capture());
        BigDecimal principalSum = schedulesCaptor.getValue().stream()
                .map(RepaymentSchedule::getPrincipalDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(principalSum).isEqualByComparingTo("2000000");
    }


    private void givenChangedContract(int v2RepaymentDay, List<RepaymentSchedule> v1Schedules) {
        when(repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(V1_CONTRACT_ID)).thenReturn(v1Schedules);
        when(loanContractService.getContractForInternalUse(V1_CONTRACT_ID))
                .thenReturn(buildContract(V1_CONTRACT_ID, 12));
        when(loanContractService.getContractForInternalUse(V2_CONTRACT_ID))
                .thenReturn(buildContract(V2_CONTRACT_ID, v2RepaymentDay));
    }

    private LoanContractResponse buildContract(Long contractId, int repaymentDay) {
        return LoanContractResponse.builder()
                .contractId(contractId)
                .principalAmount(BigDecimal.valueOf(10_000_000))
                .interestRate(BigDecimal.valueOf(12))
                .repaymentType(RepaymentMethod.EQUAL_PRINCIPAL_AND_INTEREST)
                .startDate(LocalDate.of(2026, 11, 11))
                .maturityDate(LocalDate.of(2027, 11, 11))
                .repaymentDay(repaymentDay)
                .build();
    }

    private RepaymentSchedule buildPaidRow(int sequence, LocalDate dueDate, String remainingPrincipal) {
        return new RepaymentSchedule(
                V1_CONTRACT_ID,
                sequence,
                dueDate,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal(remainingPrincipal),
                RepaymentScheduleStatus.PAID,
                LocalDateTime.now()
        );
    }

    @Test
    @DisplayName("납부 확정 시 스케줄 상태를 PAID로 바꾸고 저장한다")
    void markAsPaid_marksScheduleAsPaidAndSaves() {
        Long scheduleId = 5L;
        LocalDateTime paidAt = LocalDateTime.now();
        RepaymentSchedule schedule = buildRow(1, RepaymentScheduleStatus.PENDING, "800000");

        when(repaymentScheduleRepository.findByIdForUpdate(scheduleId)).thenReturn(Optional.of(schedule));

        repaymentScheduleService.markAsPaid(scheduleId, paidAt);

        assertThat(schedule.getStatus()).isEqualTo(RepaymentScheduleStatus.PAID);
        assertThat(schedule.getPaidAt()).isEqualTo(paidAt);
        verify(repaymentScheduleRepository).save(schedule);
    }

    @Test
    @DisplayName("계약 ID 목록이 비어있으면 쿼리 없이 빈 Map을 반환한다")
    void getSchedulesByContractIds_returnsEmptyMapWhenListIsEmpty() {
        var result = repaymentScheduleService.getSchedulesByContractIds(List.of());

        assertThat(result).isEmpty();
        verify(repaymentScheduleRepository, never()).findByContractIds(anyList());
    }

    @Test
    @DisplayName("계약 ID 목록이 있으면 계약 ID별로 스케줄을 그룹핑해서 반환한다")
    void getSchedulesByContractIds_groupsByContractId() {
        RepaymentScheduleWithRemainingProjection row1 = mock(RepaymentScheduleWithRemainingProjection.class);
        when(row1.getContractId()).thenReturn(1L);
        RepaymentScheduleWithRemainingProjection row2 = mock(RepaymentScheduleWithRemainingProjection.class);
        when(row2.getContractId()).thenReturn(1L);

        when(repaymentScheduleRepository.findByContractIds(List.of(1L))).thenReturn(List.of(row1, row2));

        var result = repaymentScheduleService.getSchedulesByContractIds(List.of(1L));

        assertThat(result).containsOnlyKeys(1L);
        assertThat(result.get(1L)).hasSize(2);
    }
}
