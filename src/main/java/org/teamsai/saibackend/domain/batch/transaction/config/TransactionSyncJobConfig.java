package org.teamsai.saibackend.domain.batch.transaction.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.batch.infrastructure.item.database.JpaPagingItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.teamsai.saibackend.domain.account.dto.LinkedAccountSyncTargetDTO;
import org.teamsai.saibackend.domain.batch.common.listener.BaseSkipListener;
import org.teamsai.saibackend.domain.batch.common.listener.LoggingJobExecutionListener;
import org.teamsai.saibackend.domain.batch.common.reader.LinkedAccountSyncTargetReaderFactory;
import org.teamsai.saibackend.domain.transaction.service.TransactionSyncFacade;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class TransactionSyncJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final LoggingJobExecutionListener loggingJobExecutionListener;
    private final LinkedAccountSyncTargetReaderFactory linkedAccountReaderFactory;

    @Bean
    public Job transactionSyncJob(Step transactionSyncStep) {
        return new JobBuilder("transactionSyncJob", jobRepository)
                .start(transactionSyncStep)
                .listener(loggingJobExecutionListener)
                .build();
    }

    @Bean
    public Step transactionSyncStep(
            BaseSkipListener<LinkedAccountSyncTargetDTO, LinkedAccountSyncTargetDTO> skipListener,
            TransactionSyncFacade transactionSyncFacade) {

        return new StepBuilder("transactionSyncStep", jobRepository)
                .<LinkedAccountSyncTargetDTO, LinkedAccountSyncTargetDTO>chunk(50)
                .transactionManager(transactionManager)
                .reader(transactionSyncReader())
                .processor(processor(transactionSyncFacade))
                .writer(noOpWriter())
                .faultTolerant()
                .skip(Exception.class)
                .skipLimit(50)
                .listener(skipListener)
                .build();
    }

    @Bean
    public JpaPagingItemReader<LinkedAccountSyncTargetDTO> transactionSyncReader() {
        return linkedAccountReaderFactory.create("transactionSyncReader");
    }

    private ItemProcessor<LinkedAccountSyncTargetDTO, LinkedAccountSyncTargetDTO> processor(
            TransactionSyncFacade transactionSyncFacade) {

        return target -> {
            transactionSyncFacade.syncAndMatch(
                    target.userId(),
                    target.linkedAccountId(),
                    true
            );

            return target;
        };
    }

    private ItemWriter<LinkedAccountSyncTargetDTO> noOpWriter() {
        return chunk -> log.info(
                "[transactionSync] 동기화 완료 계좌 {}건: {}",
                chunk.size(),
                chunk.getItems()
                        .stream()
                        .map(LinkedAccountSyncTargetDTO::linkedAccountId)
                        .toList()
        );
    }
}