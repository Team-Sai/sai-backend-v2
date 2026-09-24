package org.teamsai.saibackend.domain.notification.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.notification.entity.Notification;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;
import org.teamsai.saibackend.domain.notification.type.NotificationType;
import org.teamsai.saibackend.domain.user.entity.User;


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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void createIfAbsentInNewTransaction(
            Long userId,
            NotificationType type,
            String title,
            String content,
            Long referenceId,
            Long secondaryReferenceId
    ) {
        createIfAbsent(userId, type, title, content, referenceId, secondaryReferenceId);
    }

}
