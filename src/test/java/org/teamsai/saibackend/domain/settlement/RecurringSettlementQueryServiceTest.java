package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.settlement.dto.response.RecurringSettlementCycleListResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.RecurringSettlementCycleResponse;
import org.teamsai.saibackend.domain.settlement.dto.response.SettlementPaymentStatusResponse;
import org.teamsai.saibackend.domain.settlement.entity.RecurringSettlement;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.repository.RecurringSettlementRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.repository.SettlementRepository;
import org.teamsai.saibackend.domain.settlement.service.RecurringSettlementQueryService;
import org.teamsai.saibackend.domain.settlement.service.SettlementQueryService;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.settlement.type.SettlementStatus;
import org.teamsai.saibackend.domain.user.entity.User;
import org.teamsai.saibackend.global.exception.DomainException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RecurringSettlementQueryServiceTest {

    private static final Long RECURRING_ID = 10L;
    private static final Long OWNER_ID = 1L;
    private static final Long MEMBER_ID = 2L;

    @Mock
    private RecurringSettlementRepository recurringSettlementRepository;

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private SettlementParticipantRepository settlementParticipantRepository;

    @Mock
    private SettlementQueryService settlementQueryService;

    @InjectMocks
    private RecurringSettlementQueryService recurringSettlementQueryService;

    @Test
    void ownerSeesAllCyclesIncludingInProgressLatestFirst() {
        Settlement first = cycle(101L, LocalDate.of(2026, 8, 1), SettlementStatus.CLOSED);
        Settlement second = cycle(102L, LocalDate.of(2026, 9, 1), SettlementStatus.CLOSED);
        Settlement third = cycle(103L, LocalDate.of(2026, 10, 1), SettlementStatus.IN_PROGRESS);

        given(recurringSettlementRepository.findById(RECURRING_ID)).willReturn(Optional.of(recurringSettlement()));
        given(settlementRepository.findAllByRecurringIdOrderByCycleDate(RECURRING_ID))
                .willReturn(List.of(first, second, third));
        given(settlementQueryService.readPaymentStatuses(List.of(first, second, third))).willReturn(Map.of(
                101L, paymentStatus(),
                102L, paymentStatus(),
                103L, paymentStatus()
        ));

        RecurringSettlementCycleListResponse response =
                recurringSettlementQueryService.getCycles(RECURRING_ID, OWNER_ID);

        assertThat(response.getRole()).isEqualTo("OWNER");
        assertThat(response.getTotalCycleCount()).isEqualTo(3);
        assertThat(response.getCycles())
                .extracting(RecurringSettlementCycleResponse::getSettlementId)
                .containsExactly(103L, 102L, 101L);
        assertThat(response.getCycles())
                .extracting(RecurringSettlementCycleResponse::getCycleNo)
                .containsExactly(3, 2, 1);
        assertThat(response.getCycles().get(0).getSettlementStatus()).isEqualTo(SettlementStatus.IN_PROGRESS);
        verify(settlementParticipantRepository, never())
                .findSettlementIdsByRecurringIdAndUserId(any(), any(), any());
    }

    @Test
    void memberSeesOnlyParticipatingCyclesWithOriginalCycleNo() {
        Settlement first = cycle(101L, LocalDate.of(2026, 8, 1), SettlementStatus.CLOSED);
        Settlement second = cycle(102L, LocalDate.of(2026, 9, 1), SettlementStatus.IN_PROGRESS);

        given(recurringSettlementRepository.findById(RECURRING_ID)).willReturn(Optional.of(recurringSettlement()));
        given(settlementParticipantRepository.findSettlementIdsByRecurringIdAndUserId(
                RECURRING_ID, MEMBER_ID, SettlementParticipantStatus.ACTIVE
        )).willReturn(List.of(102L));
        given(settlementRepository.findAllByRecurringIdOrderByCycleDate(RECURRING_ID))
                .willReturn(List.of(first, second));
        // 참여 중인 회차만 납부 현황을 조회한다
        given(settlementQueryService.readPaymentStatuses(List.of(second)))
                .willReturn(Map.of(102L, paymentStatus()));

        RecurringSettlementCycleListResponse response =
                recurringSettlementQueryService.getCycles(RECURRING_ID, MEMBER_ID);

        assertThat(response.getRole()).isEqualTo("MEMBER");
        assertThat(response.getCycles()).hasSize(1);
        assertThat(response.getCycles().get(0).getSettlementId()).isEqualTo(102L);
        assertThat(response.getCycles().get(0).getCycleNo()).isEqualTo(2);
    }

    @Test
    void throwsWhenUserIsNeitherOwnerNorParticipant() {
        given(recurringSettlementRepository.findById(RECURRING_ID)).willReturn(Optional.of(recurringSettlement()));
        given(settlementParticipantRepository.findSettlementIdsByRecurringIdAndUserId(
                RECURRING_ID, MEMBER_ID, SettlementParticipantStatus.ACTIVE
        )).willReturn(List.of());

        assertThatThrownBy(() -> recurringSettlementQueryService.getCycles(RECURRING_ID, MEMBER_ID))
                .isInstanceOf(DomainException.class);
        verify(settlementRepository, never()).findAllByRecurringIdOrderByCycleDate(any());
    }

    @Test
    void throwsWhenRecurringSettlementNotFound() {
        given(recurringSettlementRepository.findById(RECURRING_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> recurringSettlementQueryService.getCycles(RECURRING_ID, OWNER_ID))
                .isInstanceOf(DomainException.class);
    }

    private RecurringSettlement recurringSettlement() {
        return RecurringSettlement.builder()
                .recurringSettlementId(RECURRING_ID)
                .owner(User.builder().userId(OWNER_ID).build())
                .title("넷플릭스 공유")
                .totalAmount(new BigDecimal("17000"))
                .startDate(LocalDate.of(2026, 8, 1))
                .build();
    }

    private Settlement cycle(Long settlementId, LocalDate cycleDate, SettlementStatus status) {
        return Settlement.builder()
                .settlementId(settlementId)
                .cycleDate(cycleDate)
                .settlementStatus(status)
                .build();
    }

    private SettlementPaymentStatusResponse paymentStatus() {
        return SettlementPaymentStatusResponse.builder()
                .totalExpectedAmount(new BigDecimal("17000"))
                .totalPaidAmount(new BigDecimal("8500"))
                .totalRemainingAmount(new BigDecimal("8500"))
                .paidCount(1)
                .unpaidCount(1)
                .progressRate(new BigDecimal("50.00"))
                .build();
    }
}
