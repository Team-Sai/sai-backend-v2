package org.teamsai.saibackend.domain.contract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;

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
            CompletableFuture<Optional<RepaymentAgentDraft>>
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

    public CompletableFuture<Optional<RepaymentAgentDraft>> submit(
            String analysisKey,
            String failureScope,
            Supplier<RepaymentAgentDraft> generation
    ) {
        CompletableFuture<Optional<RepaymentAgentDraft>> mine =
                new CompletableFuture<>();

        CompletableFuture<Optional<RepaymentAgentDraft>> existing =
                inFlight.putIfAbsent(analysisKey, mine);

        if (existing != null) {
            log.debug("상환 AI 진행 중 작업 공유");
            return existing;
        }

        Instant now = clock.instant();

        // 만료된 실패 기록을 제거한다.
        failures.entrySet().removeIf(
                entry -> !entry.getValue().isAfter(now));

        Instant blockedUntil = failures.get(failureScope);

        if (blockedUntil != null && blockedUntil.isAfter(now)) {
            log.debug("상환 AI cooldown 적용");

            mine.complete(Optional.empty());
            inFlight.remove(analysisKey, mine);
            return mine;
        }

        try {
            executor.execute(() -> {
                try {
                    RepaymentAgentDraft draft = generation.get();

                    if (draft == null) {
                        throw new IllegalStateException(
                                "AI draft must not be null");
                    }

                    mine.complete(Optional.of(draft));
                } catch (RuntimeException exception) {
                    failures.put(
                            failureScope,
                            clock.instant().plus(cooldown)
                    );

                    log.warn(
                            "상환 AI 작업 실패: exceptionType={}",
                            exception.getClass().getSimpleName()
                    );

                    mine.complete(Optional.empty());
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

            mine.complete(Optional.empty());
            inFlight.remove(analysisKey, mine);

            log.warn("상환 AI 작업 등록 실패");
        }

        return mine;
    }
}