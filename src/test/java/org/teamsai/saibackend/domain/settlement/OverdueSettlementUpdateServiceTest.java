package org.teamsai.saibackend.domain.settlement;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.payment.service.SettlementPaymentService;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.service.OverdueSettlementUpdateService;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OverdueSettlementUpdateServiceTest {

    @Mock private SettlementParticipantRepository participantRepository;
    @Mock private SettlementPaymentService settlementPaymentService;

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

        sut.updateOverdueForSettlement(settlement, referenceDate);

        verify(settlementPaymentService).markOverdueByParticipantIds(
                List.of(101L), referenceDate.plusDays(1).atStartOfDay());
    }

    @Test
    @DisplayName("여러 ACTIVE 참여자의 ID를 한 번에 결제 서비스로 전달한다")
    void delegatesAllActiveParticipants() {
        LocalDate referenceDate = LocalDate.of(2026, 2, 1);
        Settlement settlement = settlement(1L);

        when(participantRepository.findBySettlementIdAndStatus(
                1L,
                SettlementParticipantStatus.ACTIVE
        )).thenReturn(List.of(
                participant(101L, SettlementParticipantStatus.ACTIVE),
                participant(102L, SettlementParticipantStatus.ACTIVE)
        ));

        sut.updateOverdueForSettlement(settlement, referenceDate);

        verify(settlementPaymentService).markOverdueByParticipantIds(
                List.of(101L, 102L), referenceDate.plusDays(1).atStartOfDay());
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

        verifyNoInteractions(settlementPaymentService);
    }

}
