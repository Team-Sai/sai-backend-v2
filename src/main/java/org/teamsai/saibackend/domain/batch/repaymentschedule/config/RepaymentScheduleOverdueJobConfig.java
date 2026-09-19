package org.teamsai.saibackend.domain.batch.repaymentschedule.config;

import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.teamsai.saibackend.domain.batch.common.listener.LoggingJobExecutionListener;
import org.teamsai.saibackend.domain.contract.service.RepaymentScheduleService;

import java.time.LocalDate;

@Configuration
@RequiredArgsConstructor
public class RepaymentScheduleOverdueJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final LoggingJobExecutionListener loggingJobExecutionListener;

    @Bean
    public Job repaymentScheduleOverdueJob(Step repaymentScheduleOverdueStep) {
        return new JobBuilder("repaymentScheduleOverdueJob", jobRepository)
                .start(repaymentScheduleOverdueStep)
                .listener(loggingJobExecutionListener)
                .build();
    }

    @Bean
    public Step repaymentScheduleOverdueStep(RepaymentScheduleService repaymentScheduleService) {
        return new StepBuilder("repaymentScheduleOverdueStep", jobRepository)
                .tasklet(repaymentScheduleOverdueTasklet(repaymentScheduleService), transactionManager)
                .build();
    }

    private Tasklet repaymentScheduleOverdueTasklet(RepaymentScheduleService repaymentScheduleService) {
        return (contribution, chunkContext) -> {
            LocalDate baseDate = resolveBaseDate(chunkContext);
            int updated = repaymentScheduleService.markSchedulesOverdue(baseDate);
            contribution.incrementWriteCount(updated);
            return RepeatStatus.FINISHED;
        };
    }

    private LocalDate resolveBaseDate(ChunkContext chunkContext) {
        String baseDateParam = (String) chunkContext.getStepContext()
                .getJobParameters().get("baseDate");
        return LocalDate.parse(baseDateParam);
    }
}