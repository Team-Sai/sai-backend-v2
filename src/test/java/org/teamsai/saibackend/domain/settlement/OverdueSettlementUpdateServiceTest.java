package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
import org.teamsai.saibackend.domain.payment.repository.PaymentObligationRepository;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.service.OverdueSettlementUpdateService;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OverdueSettlementUpdateServiceTest {

    @Mock private SettlementParticipantRepository participantRepository;
    @Mock private PaymentObligationRepository paymentObligationRepository;

    @InjectMocks
    private OverdueSettlementUpdateService sut;

    private Settlement settlement(Long id) {
        return Settlement.builder()
                .settlementId(id)
                .build();
    }

    private SettlementParticipant participant(
            Long participantId,
            SettlementParticipantStatus status
    ) {
        return SettlementParticipant.builder()
                .participantId(participantId)
                .participantStatus(status)
                .build();
    }

    @Test
    @DisplayName("ACTIVE 참여자의 미납 obligation들을 연체 상태로 변경한다")
    void updatesOverdueSinceInBulk() {
        LocalDate referenceDate = LocalDate.of(2026, 2, 1);
        Settlement settlement = settlement(1L);

        when(participantRepository.findBySettlementIdAndStatus(
                1L,
                SettlementParticipantStatus.ACTIVE
        )).thenReturn(List.of(
                participant(101L, SettlementParticipantStatus.ACTIVE)
        ));

        PaymentObligationEntity first =
                new PaymentObligationEntity(
                        101L,
                        java.math.BigDecimal.TEN
                );

        PaymentObligationEntity second =
                new PaymentObligationEntity(
                        101L,
                        java.math.BigDecimal.TEN
                );

        when(paymentObligationRepository.findUnpaidByParticipantIds(
                List.of(101L),
                List.of(
                        PaymentStatus.UNPAID,
                        PaymentStatus.PARTIALLY_PAID
                ),
                ObligationStatus.ACTIVE
        )).thenReturn(List.of(first, second));

        sut.updateOverdueForSettlement(settlement, referenceDate);

        org.assertj.core.api.Assertions.assertThat(first.getOverdueSince())
                .isEqualTo(referenceDate.plusDays(1).atStartOfDay());

        org.assertj.core.api.Assertions.assertThat(second.getOverdueSince())
                .isEqualTo(referenceDate.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("조회된 모든 미납 obligation을 연체 상태로 변경한다")
    void logsWhenPartiallyUpdated() {
        LocalDate referenceDate = LocalDate.of(2026, 2, 1);
        Settlement settlement = settlement(1L);

        when(participantRepository.findBySettlementIdAndStatus(
                1L,
                SettlementParticipantStatus.ACTIVE
        )).thenReturn(List.of(
                participant(101L, SettlementParticipantStatus.ACTIVE)
        ));

        PaymentObligationEntity obligation =
                new PaymentObligationEntity(
                        101L,
                        java.math.BigDecimal.TEN
                );

        when(paymentObligationRepository.findUnpaidByParticipantIds(
                List.of(101L),
                List.of(
                        PaymentStatus.UNPAID,
                        PaymentStatus.PARTIALLY_PAID
                ),
                ObligationStatus.ACTIVE
        )).thenReturn(List.of(obligation));

        sut.updateOverdueForSettlement(settlement, referenceDate);

        org.assertj.core.api.Assertions.assertThat(obligation.getOverdueSince())
                .isEqualTo(referenceDate.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("ACTIVE 참여자가 없으면 obligation 조회 자체를 하지 않는다")
    void skipsWhenNoActiveParticipants() {
        LocalDate referenceDate = LocalDate.of(2026, 2, 1);
        Settlement settlement = settlement(1L);

        when(participantRepository.findBySettlementIdAndStatus(
                1L,
                SettlementParticipantStatus.ACTIVE
        )).thenReturn(List.of());

        sut.updateOverdueForSettlement(settlement, referenceDate);

        verify(paymentObligationRepository, never())
                .findUnpaidByParticipantIds(
                        any(),
                        any(),
                        any()
                );
    }

    @Test
    @DisplayName("미납 obligation이 없으면 갱신할 것도 없다")
    void doesNothingWhenNoUnpaidObligations() {
        LocalDate referenceDate = LocalDate.of(2026, 2, 1);
        Settlement settlement = settlement(1L);

        when(participantRepository.findBySettlementIdAndStatus(
                1L,
                SettlementParticipantStatus.ACTIVE
        )).thenReturn(List.of(
                participant(101L, SettlementParticipantStatus.ACTIVE)
        ));

        when(paymentObligationRepository.findUnpaidByParticipantIds(
                List.of(101L),
                List.of(
                        PaymentStatus.UNPAID,
                        PaymentStatus.PARTIALLY_PAID
                ),
                ObligationStatus.ACTIVE
        )).thenReturn(List.of());

        sut.updateOverdueForSettlement(settlement, referenceDate);

        verify(paymentObligationRepository).findUnpaidByParticipantIds(
                List.of(101L),
                List.of(
                        PaymentStatus.UNPAID,
                        PaymentStatus.PARTIALLY_PAID
                ),
                ObligationStatus.ACTIVE
        );
    }
}