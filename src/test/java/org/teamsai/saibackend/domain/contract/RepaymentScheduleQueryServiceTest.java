package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.teamsai.saibackend.domain.contract.dto.response.LoanContractResponse;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentScheduleSummaryResponse;
import org.teamsai.saibackend.domain.contract.entity.RepaymentScheduleEntity;
import org.teamsai.saibackend.domain.contract.exception.LoanContractErrorCode;
import org.teamsai.saibackend.domain.contract.repository.RepaymentScheduleRepository;
import org.teamsai.saibackend.domain.contract.service.LoanContractService;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleQueryService;
import org.teamsai.saibackend.domain.contract.type.RepaymentScheduleStatus;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepaymentScheduleQueryServiceTest {

    @Mock
    private RepaymentScheduleRepository repaymentScheduleRepository;

    @Mock
    private LoanContractService loanContractService;

    @InjectMocks
    private RepaymentScheduleQueryService repaymentScheduleQueryService;

    @Test
    void summarizesOnlyPaidSchedulesAndReturnsNextDueDate() {
        Long contractId = 1L;
        Long userId = 10L;
        when(loanContractService.findContract(contractId, userId))
                .thenReturn(LoanContractResponse.builder().contractId(contractId).creditorId(userId).build());
        List<RepaymentScheduleEntity> schedules = List.of(
                schedule(1, RepaymentScheduleStatus.PAID),
                schedule(2, RepaymentScheduleStatus.PAID),
                schedule(3, RepaymentScheduleStatus.PENDING),
                schedule(4, RepaymentScheduleStatus.PENDING)
        );
        when(repaymentScheduleRepository.findByContractIdOrderBySequenceAsc(contractId))
                .thenReturn(schedules);
        when(repaymentScheduleRepository.findFirstByContractIdAndStatusInOrderBySequenceAsc(
                contractId,
                List.of(RepaymentScheduleStatus.PENDING, RepaymentScheduleStatus.OVERDUE)
        )).thenReturn(Optional.of(schedules.get(2)));

        RepaymentScheduleSummaryResponse summary =
                repaymentScheduleQueryService.getScheduleSummary(contractId, userId);

        assertThat(summary.getTotalScheduledAmount()).isEqualByComparingTo("3200000");
        assertThat(summary.getPaidAmount()).isEqualByComparingTo("1600000");
        assertThat(summary.getRemainingAmount()).isEqualByComparingTo("1600000");
        assertThat(summary.getPaidCount()).isEqualTo(2);
        assertThat(summary.getTotalCount()).isEqualTo(4);
        assertThat(summary.getSchedules()).hasSize(4);
        assertThat(summary.getNextDueDate()).isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void rejectsNonPartyBeforeLoadingSchedules() {
        when(loanContractService.findContract(1L, 999L))
                .thenThrow(LoanContractErrorCode.CONTRACT_ACCESS_DENIED.toException());

        assertThatThrownBy(() -> repaymentScheduleQueryService.getScheduleSummary(1L, 999L))
                .isInstanceOf(DomainException.class);

        verify(repaymentScheduleRepository, never()).findByContractIdOrderBySequenceAsc(1L);
    }

    private RepaymentScheduleEntity schedule(int sequence, RepaymentScheduleStatus status) {
        RepaymentScheduleEntity schedule = new RepaymentScheduleEntity(
                1L,
                sequence,
                LocalDate.of(2026, 1, 1),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new BigDecimal("800000"),
                BigDecimal.ZERO,
                status,
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
        ReflectionTestUtils.setField(schedule, "scheduleId", (long) sequence);
        return schedule;
    }
}
