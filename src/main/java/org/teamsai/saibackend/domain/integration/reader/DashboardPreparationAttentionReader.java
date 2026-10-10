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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

@Component
public class DashboardPreparationAttentionReader {

    private static final ZoneId SEOUL =
            ZoneId.of("Asia/Seoul");

    private static final int MAX_ITEMS = 10;

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

        /*
         * createdAt은 LocalDateTime + @CreationTimestamp를 사용한다.
         * 한국 날짜의 경계를 현재 JVM의 타임스탬프 시간대로 변환한다.
         */
        ZoneId timestampZone = ZoneId.systemDefault();

        LocalDateTime startInclusive = today
                .atStartOfDay(SEOUL)
                .withZoneSameInstant(timestampZone)
                .toLocalDateTime();

        LocalDateTime endExclusive = today
                .plusDays(1)
                .atStartOfDay(SEOUL)
                .withZoneSameInstant(timestampZone)
                .toLocalDateTime();

        return notifications
                .findPreparationRemindersCreatedInRange(
                        userId,
                        NotificationType.REPAYMENT_PREPARATION_REMINDER,
                        startInclusive,
                        endExclusive,
                        PageRequest.of(0, MAX_ITEMS)
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
                                        calendarUrl(
                                                notification
                                                        .getSecondaryReferenceId()
                                        )
                                )
                                .build()
                )
                .toList();
    }

    private String calendarUrl(Long dateKey) {
        if (dateKey == null) {
            return "/calendar";
        }

        String value = dateKey.toString();

        if (value.length() != 8) {
            return "/calendar";
        }

        try {
            LocalDate originalDate = LocalDate.parse(
                    value,
                    DateTimeFormatter.BASIC_ISO_DATE
            );

            return "/calendar?date=" + originalDate;
        } catch (DateTimeParseException exception) {
            return "/calendar";
        }
    }
}