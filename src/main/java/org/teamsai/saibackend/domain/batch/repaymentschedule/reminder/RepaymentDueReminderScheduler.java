package org.teamsai.saibackend.domain.batch.repaymentschedule.reminder;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.batch.core.job.Job;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.batch.common.scheduler.AbstractDailyBatchScheduler;

@Component
@RequiredArgsConstructor
public class RepaymentDueReminderScheduler extends AbstractDailyBatchScheduler {

    private final Job repaymentDueReminderJob;

    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @SchedulerLock(name = "repaymentDueReminder", lockAtLeastFor = "5m", lockAtMostFor = "30m")
    public void runReminder() throws Exception {
        runDaily();
    }

    @Override
    protected Job targetJob() { return repaymentDueReminderJob; }

    @Override
    protected String jobLabel() { return "repaymentSchedule.dueReminder"; }
}
