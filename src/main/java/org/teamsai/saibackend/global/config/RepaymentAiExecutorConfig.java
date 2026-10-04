package org.teamsai.saibackend.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
}