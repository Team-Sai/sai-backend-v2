package org.teamsai.saibackend.domain.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.teamsai.saibackend.domain.notification.dto.response.NotificationResponse;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;

    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotifications(Long userId) {
        List<NotificationResponse> notifications = new ArrayList<>();

        notifications.addAll(notificationRepository.findBankTransactionNotificationsByUserId(userId));
        notifications.addAll(notificationRepository.findSettlementNotificationsByUserId(userId));
        notifications.addAll(notificationRepository.findContractNotificationsByUserId(userId));
        notifications.addAll(notificationRepository.findRepaymentNotificationsByUserId(userId));

        notifications.sort(
                Comparator.comparing(NotificationResponse::getCreatedAt)
                        .thenComparing(NotificationResponse::getNotificationId)
                        .reversed()
        );

        return notifications;
    }
}
