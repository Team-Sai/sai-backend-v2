package org.teamsai.saibackend.domain.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.teamsai.saibackend.domain.notification.dto.response.NotificationResponse;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;
import org.teamsai.saibackend.domain.notification.service.NotificationQueryService;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationQueryServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private NotificationQueryService notificationQueryService;

    @Test
    void mergesNotificationTypesInNewestFirstOrder() {
        Long userId = 2L;
        LocalDateTime earlier = LocalDateTime.of(2026, 8, 1, 9, 0);
        LocalDateTime later = LocalDateTime.of(2026, 8, 2, 9, 0);
        NotificationResponse bank = notification(null, earlier);
        NotificationResponse settlement = notification(2L, later);
        NotificationResponse contract = notification(3L, later);

        when(notificationRepository.findBankTransactionNotificationsByUserId(userId))
                .thenReturn(List.of(bank));
        when(notificationRepository.findSettlementNotificationsByUserId(userId))
                .thenReturn(List.of(settlement));
        when(notificationRepository.findContractNotificationsByUserId(userId))
                .thenReturn(List.of(contract));
        when(notificationRepository.findRepaymentNotificationsByUserId(userId))
                .thenReturn(List.of());

        List<NotificationResponse> result = notificationQueryService.getNotifications(userId);

        assertThat(result).containsExactly(contract, settlement, bank);
    }

    private NotificationResponse notification(Long id, LocalDateTime createdAt) {
        NotificationResponse response = mock(NotificationResponse.class);
        if (id != null) {
            when(response.getNotificationId()).thenReturn(id);
        }
        when(response.getCreatedAt()).thenReturn(createdAt);
        return response;
    }
}
