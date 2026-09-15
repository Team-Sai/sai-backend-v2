package org.teamsai.saibackend.domain.notification;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.notification.dto.response.NotificationResponse;
import org.teamsai.saibackend.domain.notification.entity.Notification;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;
import org.teamsai.saibackend.domain.notification.service.NotificationService;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService 단위 테스트")
class NotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private User user;

    @InjectMocks
    private NotificationService notificationService;

    @Test
    @DisplayName("정산 참여자에게 알림을 생성한다 (secondaryReferenceId 없이)")
    void createNotificationSuccess() {
        Long userId = 2L;
        Long settlementId = 10L;

        when(entityManager.getReference(User.class, userId)).thenReturn(user);

        notificationService.create(
                userId,
                NotificationType.SETTLEMENT_PARTICIPANT_ADDED,
                "새로운 정산에 참여자로 등록되었습니다.",
                "정산 금액 30000원이 등록되었습니다.",
                settlementId
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification notification = captor.getValue();

        assertThat(notification.getUser()).isEqualTo(user);
        assertThat(notification.getNotificationType()).isEqualTo(NotificationType.SETTLEMENT_PARTICIPANT_ADDED);
        assertThat(notification.getTitle()).isEqualTo("새로운 정산에 참여자로 등록되었습니다.");
        assertThat(notification.getContent()).isEqualTo("정산 금액 30000원이 등록되었습니다.");
        assertThat(notification.getReferenceId()).isEqualTo(settlementId);

        // 5개짜리 오버로드를 쓰면 secondaryReferenceId는 항상 null이어야 한다
        assertThat(notification.getSecondaryReferenceId()).isNull();
    }

    @Test
    @DisplayName("계약 변경 요청 알림은 secondaryReferenceId(changeRequestId)까지 함께 저장한다")
    void createNotificationWithSecondaryReferenceIdSuccess() {
        Long debtorId = 3L;
        Long contractId = 100L;
        Long changeRequestId = 7L;

        when(entityManager.getReference(User.class, debtorId)).thenReturn(user);

        notificationService.create(
                debtorId,
                NotificationType.CONTRACT_CHANGE,
                "계약 변경 요청",
                "홍길동님으로부터 계약 내용 변경 요청이 도착했습니다.",
                contractId,
                changeRequestId
        );

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository).save(captor.capture());

        Notification notification = captor.getValue();

        assertThat(notification.getReferenceId()).isEqualTo(contractId);
        assertThat(notification.getSecondaryReferenceId()).isEqualTo(changeRequestId);
    }

    @Test
    @DisplayName("로그인 사용자의 알림 목록을 조회한다")
    void getNotificationsSuccess() {
        Long userId = 2L;
        LocalDateTime createdAt = LocalDateTime.now();

        NotificationResponse expected = mock(NotificationResponse.class);
        when(expected.getNotificationId()).thenReturn(1L);
        when(expected.getCreatedAt()).thenReturn(createdAt);

        when(notificationRepository.findBankTransactionNotificationsByUserId(userId)).thenReturn(List.of());
        when(notificationRepository.findSettlementNotificationsByUserId(userId)).thenReturn(List.of(expected));
        when(notificationRepository.findContractNotificationsByUserId(userId)).thenReturn(List.of());
        when(notificationRepository.findRepaymentNotificationsByUserId(userId)).thenReturn(List.of());

        List<NotificationResponse> result = notificationService.getNotifications(userId);

        assertThat(result).containsExactly(expected);
    }

    @Test
    @DisplayName("같은 은행 거래의 매칭 검토 알림이 없으면 생성한다")
    void createsMatchingReviewNotificationWhenItDoesNotExist() {
        when(notificationRepository.existsByUser_UserIdAndNotificationTypeAndReferenceId(
                2L,
                NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                100L
        )).thenReturn(false);
        when(entityManager.getReference(User.class, 2L)).thenReturn(user);

        notificationService.createIfAbsent(
                2L,
                NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                "입금 거래 확인이 필요합니다.",
                "정산과 차용증 후보가 모두 발견되었습니다.",
                100L,
                10L
        );

        verify(notificationRepository).save(
                argThat(notification ->
                        notification.getUser().equals(user)
                                && notification.getReferenceId().equals(100L)
                                && notification.getSecondaryReferenceId().equals(10L)
                )
        );
    }

    @Test
    @DisplayName("같은 은행 거래의 매칭 검토 알림이 있으면 중복 생성하지 않는다")
    void doesNotCreateDuplicatedMatchingReviewNotification() {
        when(notificationRepository.existsByUser_UserIdAndNotificationTypeAndReferenceId(
                2L,
                NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                100L
        )).thenReturn(true);

        notificationService.createIfAbsent(
                2L,
                NotificationType.BANK_TRANSACTION_MATCHING_REVIEW,
                "입금 거래 확인이 필요합니다.",
                "정산과 차용증 후보가 모두 발견되었습니다.",
                100L,
                10L
        );

        verify(notificationRepository, never()).save(any());
    }
}
