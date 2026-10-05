package org.teamsai.saibackend.domain.contract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

@Slf4j
@Component
public class RepaymentGenerationCoordinator {

    private final Executor executor;
    private final Clock clock;
    private final Duration cooldown;

    private final ConcurrentHashMap<
            String,
            CompletableFuture<Optional<CachedRepaymentAnalysis>>
            > inFlight = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Instant> failures =
            new ConcurrentHashMap<>();

    public RepaymentGenerationCoordinator(
            @Qualifier("repaymentAiExecutor") Executor executor,
            @Qualifier("repaymentClock") Clock clock,
            @Value("${sai.repayment.failure-cooldown:PT30S}") Duration cooldown
    ) {
        if (cooldown.isZero() || cooldown.isNegative()) {
            throw new IllegalArgumentException(
                    "Failure cooldown must be positive");
        }

        this.executor = executor;
        this.clock = clock;
        this.cooldown = cooldown;
    }

    public CompletableFuture<Optional<CachedRepaymentAnalysis>> submit(
            String analysisKey,
            String failureScope,
            Supplier<CachedRepaymentAnalysis> generation
    ) {
        CompletableFuture<Optional<CachedRepaymentAnalysis>> mine =
                new CompletableFuture<>();

        CompletableFuture<Optional<CachedRepaymentAnalysis>> existing =
                inFlight.putIfAbsent(analysisKey, mine);

        if (existing != null) {
            log.info(
                    "event=REPAYMENT_LOCAL_SHARED key={}",
                    analysisKey
            );
            return existing;
        }

        Instant now = clock.instant();

        // 만료된 실패 기록을 제거한다.
        failures.entrySet().removeIf(
                entry -> !entry.getValue().isAfter(now));

        Instant blockedUntil = failures.get(failureScope);

        if (blockedUntil != null && blockedUntil.isAfter(now)) {
            log.info(
                    "event=REPAYMENT_LOCAL_COOLDOWN scope={}",
                    failureScope
            );

            long milliseconds =
                    Duration.between(now, blockedUntil).toMillis();

            int retryAfterSeconds = Math.toIntExact(
                    Math.max(
                            1,
                            milliseconds / 1000
                                    + (milliseconds % 1000 == 0 ? 0 : 1)
                    )
            );

            mine.completeExceptionally(
                    new RepaymentGenerationDeferredException(
                            "COOLDOWN",
                            retryAfterSeconds
                    )
            );
            inFlight.remove(analysisKey, mine);
            return mine;
        }

        try {
            executor.execute(() -> {
                try {
                    CachedRepaymentAnalysis draft = generation.get();

                    if (draft == null) {
                        throw new IllegalStateException(
                                "AI draft must not be null");
                    }

                    mine.complete(Optional.of(draft));
                } catch (RepaymentGenerationDeferredException exception) {
                    // 다른 서버의 작업 진행, 공유 cooldown, 락 소유권 상실 등.
                    // AI 장애로 기록하지 않는다.
                    log.info(
                            "event=REPAYMENT_GENERATION_DEFERRED key={} reason={}",
                            analysisKey,
                            exception.getMessage()
                    );

                    mine.completeExceptionally(exception);
                }catch (RuntimeException exception) {
                    failures.put(
                            failureScope,
                            clock.instant().plus(cooldown)
                    );

                    log.warn(
                            "event=REPAYMENT_JOB_FAILURE key={} exceptionType={}",
                            analysisKey,
                            exception.getClass().getSimpleName()
                    );

                    mine.completeExceptionally(
                            new RepaymentGenerationDeferredException(
                                    "AI_UNAVAILABLE"
                            )
                    );
                } catch (Error error) {
                    mine.completeExceptionally(error);
                    throw error;
                } finally {
                    inFlight.remove(analysisKey, mine);
                }
            });
        } catch (RuntimeException exception) {
            // 실행기 큐 포화 등으로 등록하지 못한 경우.
            failures.put(
                    failureScope,
                    clock.instant().plus(cooldown)
            );

            mine.completeExceptionally(
                    new RepaymentGenerationDeferredException(
                            "SERVICE_BUSY"
                    )
            );
            inFlight.remove(analysisKey, mine);

            log.warn(
                    "event=REPAYMENT_JOB_REJECTED key={} exceptionType={}",
                    analysisKey,
                    exception.getClass().getSimpleName()
            );
        }

        return mine;
    }
}