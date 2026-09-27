package org.teamsai.saibackend.domain.batch.settlement.reminder;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.ReminderStage;
import org.teamsai.saibackend.domain.payment.dto.PaymentObligationView;
import org.teamsai.saibackend.domain.payment.type.ObligationStatus;
import org.teamsai.saibackend.domain.payment.service.PaymentObligationQueryService;
import org.teamsai.saibackend.domain.payment.type.PaymentStatus;
import org.teamsai.saibackend.domain.settlement.entity.Settlement;
import org.teamsai.saibackend.domain.settlement.entity.SettlementParticipant;
import org.teamsai.saibackend.domain.settlement.repository.SettlementParticipantRepository;
import org.teamsai.saibackend.domain.settlement.support.SettlementReminderSender;
import org.teamsai.saibackend.domain.settlement.type.SettlementParticipantStatus;
import org.teamsai.saibackend.domain.user.entity.User;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SettlementReminderSenderTest {

    @Mock
    private SettlementParticipantRepository participantRepository;
    @Mock
    private PaymentObligationQueryService paymentObligationQueryService;
    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private SettlementReminderSender sender;

    private Settlement settlement(Long id) {
        Settlement settlement = mock(Settlement.class);
        lenient().when(settlement.getSettlementId()).thenReturn(id);
        return settlement;
    }

    private SettlementParticipant participant(Long participantId, Long userId) {
        SettlementParticipant participant = mock(SettlementParticipant.class);
        User user = mock(User.class);

        lenient().when(participant.getParticipantId()).thenReturn(participantId);
        lenient().when(participant.getParticipantStatus()).thenReturn(SettlementParticipantStatus.ACTIVE);
        lenient().when(participant.getUser()).thenReturn(user);
        lenient().when(user.getUserId()).thenReturn(userId);

        return participant;
    }

    private PaymentObligationView obligation(Long obligationId, Long participantId, PaymentStatus status) {
        return new PaymentObligationView(obligationId, participantId, java.math.BigDecimal.ZERO,
                status, ObligationStatus.ACTIVE, null);
    }

    @Nested
    class NoActiveParticipants {

        @Test
        void 활성_참여자가_없으면_0을_반환하고_아무것도_조회하지_않는다() {
            Settlement s = settlement(1L);
            when(participantRepository.findBySettlementIdAndStatus(
                    1L,
                    SettlementParticipantStatus.ACTIVE
            )).thenReturn(Collections.emptyList());

            var result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result.processedCount()).isZero();
            assertThat(result.failedCount()).isZero();
            verifyNoInteractions(paymentObligationQueryService, notificationService);
        }
    }

    @Nested
    class ObligationFiltering {

        @Test
        void 미해결_의무만_필터링해서_리마인드를_보낸다() {
            Settlement s = settlement(1L);
            SettlementParticipant p1 = participant(10L, 100L);
            when(participantRepository.findBySettlementIdAndStatus(
                    1L,
                    SettlementParticipantStatus.ACTIVE
            )).thenReturn(List.of(p1));

            PaymentObligationView unresolvedOb = obligation(500L, 10L, PaymentStatus.UNPAID);
            PaymentObligationView resolvedOb = obligation(501L, 10L, PaymentStatus.PAID);

            when(paymentObligationQueryService.findLatestActiveByParticipantIds(List.of(10L)))
                    .thenReturn(List.of(unresolvedOb, resolvedOb));

            var result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result.processedCount()).isEqualTo(1);
            verify(notificationService).createIfAbsentInNewTransaction(
                    eq(100L), any(), any(), any(), eq(500L), eq(1L)
            );
        }
    }

    @Nested
    class UserMappingFailure {

        @Test
        void participantId에_매핑되는_userId가_없으면_스킵하고_카운트하지_않는다() {
            Settlement s = settlement(1L);
            SettlementParticipant p1 = participant(10L, 100L);
            when(participantRepository.findBySettlementIdAndStatus(
                    1L,
                    SettlementParticipantStatus.ACTIVE
            )).thenReturn(List.of(p1));

            // obligation의 participantId가 activeParticipants에 없는 20L (매핑 실패 상황)
            PaymentObligationView orphanOb = obligation(500L, 20L, PaymentStatus.UNPAID);

            when(paymentObligationQueryService.findLatestActiveByParticipantIds(List.of(10L)))
                    .thenReturn(List.of(orphanOb));

            var result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result.processedCount()).isZero();
            verifyNoInteractions(notificationService);
        }
    }

    @Nested
    class PartialFailure {

        @Test
        void 특정_알림_발송_실패해도_나머지는_계속_처리한다() {
            Settlement s = settlement(1L);
            SettlementParticipant p1 = participant(10L, 100L);
            SettlementParticipant p2 = participant(11L, 101L);
            when(participantRepository.findBySettlementIdAndStatus(
                    1L,
                    SettlementParticipantStatus.ACTIVE
            )).thenReturn(List.of(p1, p2));

            PaymentObligationView ob1 = obligation(500L, 10L, PaymentStatus.UNPAID);
            PaymentObligationView ob2 = obligation(501L, 11L, PaymentStatus.UNPAID);

            when(paymentObligationQueryService.findLatestActiveByParticipantIds(List.of(10L, 11L)))
                    .thenReturn(List.of(ob1, ob2));

            doThrow(new RuntimeException("알림 발송 실패"))
                    .when(notificationService).createIfAbsentInNewTransaction(
                            eq(100L), any(), any(), any(), eq(500L), eq(1L)
                    );

            var result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result.processedCount()).isEqualTo(1);
            assertThat(result.failedCount()).isEqualTo(1);
            verify(notificationService).createIfAbsentInNewTransaction(eq(100L), any(), any(), any(), eq(500L), eq(1L));
            verify(notificationService).createIfAbsentInNewTransaction(eq(101L), any(), any(), any(), eq(501L), eq(1L));
        }
    }
}
