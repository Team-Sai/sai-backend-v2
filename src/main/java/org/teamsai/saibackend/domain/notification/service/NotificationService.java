package org.teamsai.saibackend.domain.notification.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.notification.dto.response.NotificationResponse;
import org.teamsai.saibackend.domain.notification.entity.Notification;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.entity.User;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final EntityManager entityManager;

    public void create(Long userId, NotificationType type, String title, String content, Long referenceId){
        create(userId, type, title, content, referenceId, null);
    }

    public void create(Long userId, NotificationType type, String title, String content, Long referenceId, Long secondaryReferenceId){
        Notification notification = Notification.builder()
                .user(entityManager.getReference(User.class, userId))
                .notificationType(type)
                .title(title)
                .content(content)
                .referenceId(referenceId)
                .secondaryReferenceId(secondaryReferenceId)
                .build();

        notificationRepository.save(notification);
    }

    @Transactional
    public void createIfAbsent(
            Long userId,
            NotificationType type,
            String title,
            String content,
            Long referenceId,
            Long secondaryReferenceId
    ) {
        if (notificationRepository.existsByUser_UserIdAndNotificationTypeAndReferenceId(
                userId,
                type,
                referenceId
        )) {
            return;
        }

        create(
                userId,
                type,
                title,
                content,
                referenceId,
                secondaryReferenceId
        );
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotifications(Long userId) {
        List<NotificationResponse> notifications = new ArrayList<>();

        notifications.addAll(notificationRepository.findBankTransactionNotificationsByUserId(userId));
        notifications.addAll(notificationRepository.findSettlementNotificationsByUserId(userId));
        notifications.addAll(notificationRepository.findContractNotificationsByUserId(userId));
        notifications.addAll(notificationRepository.findRepaymentNotificationsByUserId(userId));

        notifications.sort(
                Comparator.comparing(NotificationResponse::createdAt)
                        .thenComparing(NotificationResponse::notificationId)
                        .reversed()
        );

        return notifications;
    }
}
