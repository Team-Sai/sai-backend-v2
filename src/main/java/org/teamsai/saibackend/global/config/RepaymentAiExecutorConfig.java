package org.teamsai.saibackend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class RepaymentAiExecutorConfig {

    @Bean("repaymentAiExecutor")
    public ThreadPoolTaskExecutor repaymentAiExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(16);
        executor.setThreadNamePrefix("repayment-ai-");

        return executor;
    }

    @Bean("repaymentLockScheduler")
    public ThreadPoolTaskScheduler repaymentLockScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();

        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("repayment-lock-");
        scheduler.setRemoveOnCancelPolicy(true);

        return scheduler;
    }
}