package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.contract.dto.request.RepaymentMethod;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleWithRemainingProjection;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleSummaryResponse;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class RepaymentScheduleServiceTest {

    @Mock
    private RepaymentScheduleRepository repaymentScheduleRepository;

    @Mock
    private LoanContractService loanContractService;

    @InjectMocks
    private RepaymentScheduleService repaymentScheduleService;

    private RepaymentScheduleEntity buildRow(int sequence, RepaymentScheduleStatus status, String totalPaymentDue) {
        RepaymentScheduleEntity entity = new RepaymentScheduleEntity(
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
                .build();

        when(loanContractService.getContractForInternalUse(contractId)).thenReturn(contract);

        repaymentScheduleService.generateSchedule(contractId);

        verify(repaymentScheduleRepository).saveAll(
                argThat((List<RepaymentScheduleEntity> list) -> list.size() == 12)
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
                .build();

        when(loanContractService.getContractForInternalUse(contractId)).thenReturn(contract);

        assertThatThrownBy(() -> repaymentScheduleService.generateSchedule(contractId))
                .isInstanceOf(DomainException.class);

        verify(repaymentScheduleRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("요약 조회 시 PAID 건만 누적 납부액에 합산된다")
    void getScheduleSummary_calculatesCorrectly() {
        Long contractId = 1L;
        Long userId = 10L;

        when(loanContractService.findContract(contractId, userId))
                .thenReturn(LoanContractResponse.builder().contractId(contractId).creditorId(userId).build());

        List<RepaymentScheduleEntity> schedules = List.of(
                buildRow(1, RepaymentScheduleStatus.PAID, "800000"),
                buildRow(2, RepaymentScheduleStatus.PAID, "800000"),
                buildRow(3, RepaymentScheduleStatus.PENDING, "800000"),
                buildRow(4, RepaymentScheduleStatus.PENDING, "800000")
        );
        when(repaymentScheduleRepository.findByContractIdOrderBySequenceASC(contractId)).thenReturn(schedules);

        RepaymentScheduleSummaryResponse summary = repaymentScheduleService.getScheduleSummary(contractId, userId);

        assertThat(summary.getTotalScheduledAmount()).isEqualByComparingTo("3200000");
        assertThat(summary.getPaidAmount()).isEqualByComparingTo("1600000");
        assertThat(summary.getRemainingAmount()).isEqualByComparingTo("1600000");
        assertThat(summary.getPaidCount()).isEqualTo(2);
        assertThat(summary.getTotalCount()).isEqualTo(4);
        assertThat(summary.getSchedules()).hasSize(4);
    }

    @Test
    @DisplayName("계약 당사자가 아니면 예외를 던지고 조회하지 않는다")
    void getScheduleSummary_throwsWhenUserIsNotParty() {
        Long contractId = 1L;
        Long otherUserId = 999L;

        when(loanContractService.findContract(contractId, otherUserId))
                .thenThrow(LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException());

        assertThatThrownBy(() -> repaymentScheduleService.getScheduleSummary(contractId, otherUserId))
                .isInstanceOf(DomainException.class);

        verify(repaymentScheduleRepository, never()).findByContractIdOrderBySequenceASC(any());
    }

    @Test
    @DisplayName("납부 확정 시 스케줄 상태를 PAID로 바꾸고 저장한다")
    void markAsPaid_marksScheduleAsPaidAndSaves() {
        Long scheduleId = 5L;
        LocalDateTime paidAt = LocalDateTime.now();
        RepaymentScheduleEntity schedule = buildRow(1, RepaymentScheduleStatus.PENDING, "800000");

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