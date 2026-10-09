package org.teamsai.saibackend.domain.batch.repaymentschedule.reminder;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.calendar.repository.RepaymentPreparationEventRepository;
import org.teamsai.saibackend.domain.calendar.service.PreparationReminderService;

import java.time.Clock;
import java.time.Instant;

@Slf4j
@Component
public class PreparationReminderScheduler {
    private final RepaymentPreparationEventRepository events;
    private final PreparationReminderService reminders;
    private final Clock clock;

    public PreparationReminderScheduler(
            RepaymentPreparationEventRepository events,
            PreparationReminderService reminders,
            @Qualifier("repaymentClock") Clock clock
    ) {
        this.events = events;
        this.reminders = reminders;
        this.clock = clock;
    }

    @Scheduled(cron = "${sai.calendar.preparation-reminder-cron:0 * * * * *}",
            zone = "Asia/Seoul")
    public void runReminders() {
        Instant cutoff = clock.instant();
        for (Long userId : events.findDueReminderUserIds(cutoff)) {
            try {
                reminders.processUser(userId, cutoff);
            } catch (RuntimeException exception) {
                log.warn("event=PREPARATION_REMINDER_FAILURE exceptionType={}",
                        exception.getClass().getSimpleName(), exception);
            }
        }
    }
}