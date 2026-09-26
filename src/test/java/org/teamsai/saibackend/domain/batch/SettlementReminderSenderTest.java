package org.teamsai.saibackend.domain.batch;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.ReminderStage;
import org.teamsai.saibackend.domain.payment.entity.PaymentObligationEntity;
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

    private PaymentObligationEntity obligation(Long obligationId, Long participantId, PaymentStatus status) {
        PaymentObligationEntity entity = mock(PaymentObligationEntity.class);
        lenient().when(entity.getPaymentObligationId()).thenReturn(obligationId);
        lenient().when(entity.getParticipantId()).thenReturn(participantId);
        lenient().when(entity.getPaymentStatus()).thenReturn(status);
        return entity;
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

            int result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result).isZero();
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

            PaymentObligationEntity unresolvedOb = mock(PaymentObligationEntity.class);
            when(unresolvedOb.getParticipantId()).thenReturn(10L);
            when(unresolvedOb.getPaymentObligationId()).thenReturn(500L);
            when(unresolvedOb.getPaymentStatus()).thenReturn(PaymentStatus.UNPAID);

            PaymentObligationEntity resolvedOb = mock(PaymentObligationEntity.class);
            when(resolvedOb.getPaymentStatus()).thenReturn(PaymentStatus.PAID);

            when(paymentObligationQueryService.findLatestActiveByParticipantIds(List.of(10L)))
                    .thenReturn(List.of(unresolvedOb, resolvedOb));

            int result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result).isEqualTo(1);
            verify(notificationService).createIfAbsent(
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
            PaymentObligationEntity orphanOb = mock(PaymentObligationEntity.class);
            when(orphanOb.getParticipantId()).thenReturn(20L);
            when(orphanOb.getPaymentStatus()).thenReturn(PaymentStatus.UNPAID);

            when(paymentObligationQueryService.findLatestActiveByParticipantIds(List.of(10L)))
                    .thenReturn(List.of(orphanOb));

            int result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result).isZero();
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

            PaymentObligationEntity ob1 = mock(PaymentObligationEntity.class);
            when(ob1.getParticipantId()).thenReturn(10L);
            when(ob1.getPaymentObligationId()).thenReturn(500L);
            when(ob1.getPaymentStatus()).thenReturn(PaymentStatus.UNPAID);

            PaymentObligationEntity ob2 = mock(PaymentObligationEntity.class);
            when(ob2.getParticipantId()).thenReturn(11L);
            when(ob2.getPaymentObligationId()).thenReturn(501L);
            when(ob2.getPaymentStatus()).thenReturn(PaymentStatus.UNPAID);

            when(paymentObligationQueryService.findLatestActiveByParticipantIds(List.of(10L, 11L)))
                    .thenReturn(List.of(ob1, ob2));

            doThrow(new RuntimeException("알림 발송 실패"))
                    .when(notificationService).createIfAbsent(
                            eq(100L), any(), any(), any(), eq(500L), eq(1L)
                    );

            int result = sender.sendForSettlement(s, ReminderStage.D3);

            assertThat(result).isEqualTo(1);
            verify(notificationService).createIfAbsent(eq(100L), any(), any(), any(), eq(500L), eq(1L));
            verify(notificationService).createIfAbsent(eq(101L), any(), any(), any(), eq(501L), eq(1L));
        }
    }
}
