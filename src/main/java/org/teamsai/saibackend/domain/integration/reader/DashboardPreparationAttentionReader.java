package org.teamsai.saibackend.domain.integration.reader;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.integration.dto.response.DashboardAttentionItemResponse;
import org.teamsai.saibackend.domain.integration.type.DashboardAttentionType;
import org.teamsai.saibackend.domain.notification.repository.NotificationRepository;
import org.teamsai.saibackend.domain.notification.type.NotificationType;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Component
public class DashboardPreparationAttentionReader {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    private final NotificationRepository notifications;
    private final Clock clock;

    public DashboardPreparationAttentionReader(
            NotificationRepository notifications,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.notifications = notifications;
        this.clock = clock;
    }

    public List<DashboardAttentionItemResponse> read(
            Long userId
    ) {
        LocalDate today = clock.instant()
                .atZone(SEOUL)
                .toLocalDate();

        long dateKey = Long.parseLong(
                today.format(DateTimeFormatter.BASIC_ISO_DATE)
        );

        return notifications
                .findByUser_UserIdAndNotificationTypeAndSecondaryReferenceIdOrderByCreatedAtDescNotificationIdDesc(
                        userId,
                        NotificationType.REPAYMENT_PREPARATION_REMINDER,
                        dateKey,
                        PageRequest.of(0, 10)
                )
                .stream()
                .map(notification ->
                        DashboardAttentionItemResponse.builder()
                                .id(notification.getNotificationId())
                                .type(
                                        DashboardAttentionType
                                                .REPAYMENT_PREPARATION_REMINDER
                                )
                                .actionUrl(
                                        "/calendar?date=" + today
                                )
                                .build()
                )
                .toList();
    }
}