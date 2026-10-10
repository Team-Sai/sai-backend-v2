package org.teamsai.saibackend.domain.batch.calendar.cleanup;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.calendar.service.PreparationProposalCleanupService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "sai.calendar.proposal-cleanup",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = false
)
public class PreparationProposalCleanupScheduler {

    private final PreparationProposalCleanupService cleanup;
    private final Clock clock;
    private final Duration retention;
    private final int chunkSize;
    private final int maxChunks;

    public PreparationProposalCleanupScheduler(
            PreparationProposalCleanupService cleanup,
            @Qualifier("repaymentClock") Clock clock,
            @Value("${sai.calendar.proposal-cleanup.retention:P7D}")
            Duration retention,
            @Value("${sai.calendar.proposal-cleanup.chunk-size:200}")
            int chunkSize,
            @Value("${sai.calendar.proposal-cleanup.max-chunks:10}")
            int maxChunks
    ) {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException(
                    "제안 보존 기간은 0보다 커야 합니다."
            );
        }

        if (chunkSize < 1 || chunkSize > 1000) {
            throw new IllegalArgumentException(
                    "정리 청크 크기는 1~1000이어야 합니다."
            );
        }

        if (maxChunks < 1 || maxChunks > 100) {
            throw new IllegalArgumentException(
                    "한 번에 처리할 청크 수는 1~100이어야 합니다."
            );
        }

        this.cleanup = cleanup;
        this.clock = clock;
        this.retention = retention;
        this.chunkSize = chunkSize;
        this.maxChunks = maxChunks;
    }

    @Scheduled(
            cron = "${sai.calendar.proposal-cleanup.cron:0 30 3 * * *}",
            zone = "Asia/Seoul"
    )
    public void runCleanup() {
        Instant cutoff = clock.instant().minus(retention);
        long startedAt = System.nanoTime();

        int deletedCount = 0;
        int processedChunks = 0;

        try {
            for (int index = 0; index < maxChunks; index++) {
                int deleted = cleanup.deleteChunk(
                        cutoff,
                        chunkSize
                );

                deletedCount += deleted;
                processedChunks++;

                if (deleted < chunkSize) {
                    break;
                }
            }

            log.info(
                    "event=PREPARATION_PROPOSAL_CLEANUP"
                            + " deletedCount={} processedChunks={}"
                            + " elapsedMs={}",
                    deletedCount,
                    processedChunks,
                    TimeUnit.NANOSECONDS.toMillis(
                            System.nanoTime() - startedAt
                    )
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "event=PREPARATION_PROPOSAL_CLEANUP_FAILURE"
                            + " deletedCount={} processedChunks={}"
                            + " exceptionType={}",
                    deletedCount,
                    processedChunks,
                    exception.getClass().getSimpleName(),
                    exception
            );
        }
    }
}