package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.Test;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.service.RepaymentGenerationCoordinator;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class RepaymentGenerationCoordinatorTest {

    @Test
    void concurrentRequestsShareOneGeneration() throws Exception {
        Queue<Runnable> queued = new ConcurrentLinkedQueue<>();
        AtomicInteger calls = new AtomicInteger();

        RepaymentGenerationCoordinator coordinator =
                new RepaymentGenerationCoordinator(
                        task -> queued.add(task),
                        Clock.systemUTC(),
                        Duration.ofSeconds(30)
                );

        ExecutorService callers = Executors.newFixedThreadPool(8);

        try {
            List<Callable<
                    CompletableFuture<Optional<RepaymentAgentDraft>>
                    >> requests = new ArrayList<>();

            for (int index = 0; index < 20; index++) {
                requests.add(() -> coordinator.submit(
                        "same-analysis",
                        "same-user",
                        () -> {
                            calls.incrementAndGet();
                            return draft();
                        }
                ));
            }

            List<Future<
                    CompletableFuture<Optional<RepaymentAgentDraft>>
                    >> results = callers.invokeAll(requests);

            CompletableFuture<Optional<RepaymentAgentDraft>> shared =
                    results.get(0).get();

            for (var result : results) {
                assertThat(result.get()).isSameAs(shared);
            }

            // 호출자가 20명이어도 생성 작업은 하나만 등록된다.
            assertThat(queued).hasSize(1);
            assertThat(calls.get()).isZero();

            Runnable task = queued.poll();
            assertThat(task).isNotNull();
            task.run();

            assertThat(shared.join()).contains(draft());
            assertThat(calls.get()).isEqualTo(1);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void failureBlocksNewGenerationUntilCooldownExpires() {
        MutableClock clock = new MutableClock();
        AtomicInteger calls = new AtomicInteger();

        RepaymentGenerationCoordinator coordinator =
                new RepaymentGenerationCoordinator(
                        Runnable::run,
                        clock,
                        Duration.ofSeconds(30)
                );

        var first = coordinator.submit("analysis-a", "user-a", () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("failure");
        });

        // 분석 내용이 달라도 같은 사용자의 실패 범위이면 억제된다.
        var blocked = coordinator.submit("analysis-b", "user-a", () -> {
            calls.incrementAndGet();
            return draft();
        });

        assertThat(first.join()).isEmpty();
        assertThat(blocked.join()).isEmpty();
        assertThat(calls.get()).isEqualTo(1);

        clock.advance(Duration.ofSeconds(31));

        var recovered = coordinator.submit("analysis-b", "user-a", () -> {
            calls.incrementAndGet();
            return draft();
        });

        assertThat(recovered.join()).contains(draft());
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void failureDoesNotBlockAnotherUser() {
        RepaymentGenerationCoordinator coordinator =
                new RepaymentGenerationCoordinator(
                        Runnable::run,
                        Clock.systemUTC(),
                        Duration.ofSeconds(30)
                );

        coordinator.submit("analysis-a", "user-a", () -> {
            throw new IllegalStateException("failure");
        }).join();

        var result = coordinator.submit(
                "analysis-b", "user-b", this::draft);

        assertThat(result.join()).contains(draft());
    }

    @Test
    void completedWorkIsRemovedFromInFlightMap() {
        AtomicInteger calls = new AtomicInteger();

        RepaymentGenerationCoordinator coordinator =
                new RepaymentGenerationCoordinator(
                        Runnable::run,
                        Clock.systemUTC(),
                        Duration.ofSeconds(30)
                );

        for (int index = 0; index < 2; index++) {
            coordinator.submit("same-key", "user-a", () -> {
                calls.incrementAndGet();
                return draft();
            }).join();
        }

        // 조정기는 진행 중 작업만 공유한다.
        // 완료 후 재사용은 Redis 캐시 서비스의 역할이다.
        assertThat(calls.get()).isEqualTo(2);
    }

    private RepaymentAgentDraft draft() {
        return new RepaymentAgentDraft(
                "상환 안내",
                List.of(
                        new RepaymentAgentDraft.ActionExplanation(
                                101L, "납기일을 확인하세요.")
                ),
                "일정에 맞춰 준비하세요."
        );
    }

    private static class MutableClock extends Clock {

        private final AtomicReference<Instant> instant =
                new AtomicReference<>(
                        Instant.parse("2026-10-05T00:00:00Z")
                );

        void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}