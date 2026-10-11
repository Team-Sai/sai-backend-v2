package org.teamsai.saibackend.domain.batch.repaymentschedule.reminder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.calendar.service.PreparationReminderService;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

@Slf4j
@Component
public class PreparationReminderScheduler {
    private final RepaymentPreparationEventRepository events;
    private final PreparationReminderService reminders;
    private final Clock clock;
    private static final int USER_CHUNK_SIZE = 200;

    public PreparationReminderScheduler(
            RepaymentPreparationEventRepository events,
            PreparationReminderService reminders,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.events = events;
        this.reminders = reminders;
        this.clock = clock;
    }

    @Scheduled(
            cron = "${sai.calendar.preparation-reminder-cron:0 * * * * *}",
            zone = "Asia/Seoul"
    )
    public void runReminders() {
        Instant cutoff = clock.instant();
        long startedAt = System.nanoTime();

        long afterUserId = 0L;
        int visitedUsers = 0;
        int failedUsers = 0;
        int notificationGroups = 0;

        while (true) {
            List<Long> userIds = events.findDueReminderUserIdsAfter(
                    cutoff,
                    afterUserId,
                    PageRequest.of(0, USER_CHUNK_SIZE)
            );

            if (userIds.isEmpty()) {
                break;
            }

            for (Long userId : userIds) {
                visitedUsers++;

                try {
                    notificationGroups += reminders.processUser(userId, cutoff);
                } catch (RuntimeException exception) {
                    failedUsers++;

                    log.warn(
                            "event=PREPARATION_REMINDER_FAILURE "
                                    + "userId={} exceptionType={}",
                            userId,
                            exception.getClass().getSimpleName(),
                            exception
                    );
                }
            }

            // 실패한 사용자도 이번 실행에서는 다시 조회하지 않는다.
            // 미처리 상태가 유지되므로 다음 스케줄러 실행에서 재시도한다.
            afterUserId = userIds.get(userIds.size() - 1);
        }

        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        log.info(
                "event=PREPARATION_REMINDER_BATCH_COMPLETED "
                        + "visitedUsers={} failedUsers={} "
                        + "notificationGroups={} elapsedMs={}",
                visitedUsers,
                failedUsers,
                notificationGroups,
                elapsedMs
        );
    }
}