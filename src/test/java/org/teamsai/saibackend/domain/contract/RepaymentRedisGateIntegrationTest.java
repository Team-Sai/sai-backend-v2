package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisCache;
import org.teamsai.saibackend.domain.contract.service.RepaymentRedisGate;
import java.util.concurrent.CountDownLatch;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(
        named = "RUN_REPAYMENT_REDIS_IT",
        matches = "true"
)
class RepaymentRedisGateIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private ThreadPoolTaskScheduler scheduler;

    private RepaymentRedisGate serverA;
    private RepaymentRedisGate serverB;
    private RepaymentAnalysisCache cache;

    private String key;

    @BeforeEach
    void setUp() {
        connectionFactory =
                new LettuceConnectionFactory("localhost", 6379);

        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redis = new StringRedisTemplate(connectionFactory);

        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();

        serverA = gate();
        serverB = gate();

        cache = new RepaymentAnalysisCache(
                redis,
                Duration.ofMinutes(5)
        );

        key = "repayment:it:{" + UUID.randomUUID() + "}";
    }

    @AfterEach
    void tearDown() {
        try {
            redis.delete(List.of(
                    key,
                    key + ":lock",
                    key + ":failure"
            ));
        } finally {
            scheduler.shutdown();
            connectionFactory.destroy();
        }
    }

    @Test
    void onlyOneServerCanAcquireSameAnalysisLock() {
        try (var first = serverA.tryAcquire(key).orElseThrow()) {
            assertThat(serverB.tryAcquire(key)).isEmpty();
        }

        try (var next = serverB.tryAcquire(key).orElseThrow()) {
            assertThat(next.token()).isNotBlank();
        }
    }

    @Test
    void oldOwnerCannotDeleteNewOwnersLock() {
        var oldOwner = serverA.tryAcquire(key).orElseThrow();

        try {
            // TTL 만료 후 다른 서버가 획득한 상황을 재현한다.
            redis.opsForValue().set(
                    oldOwner.lockKey(),
                    "new-owner",
                    Duration.ofMinutes(2)
            );

            oldOwner.close();

            assertThat(
                    redis.opsForValue().get(oldOwner.lockKey())
            ).isEqualTo("new-owner");
        } finally {
            oldOwner.close();
        }
    }

    @Test
    void oldOwnerCannotOverwriteCache() {
        try (var lease = serverA.tryAcquire(key).orElseThrow()) {
            redis.opsForValue().set(
                    lease.lockKey(),
                    "new-owner",
                    Duration.ofMinutes(2)
            );

            assertThat(
                    cache.putIfOwned(key, entry(), lease)
            ).isFalse();

            assertThat(redis.hasKey(key)).isFalse();
        }
    }

    @Test
    void ownerCanSaveTimestampAndCacheHasTtl() {
        CachedRepaymentAnalysis entry = entry();

        try (var lease = serverA.tryAcquire(key).orElseThrow()) {
            assertThat(
                    cache.putIfOwned(key, entry, lease)
            ).isTrue();

            assertThat(cache.get(key)).contains(entry);

            assertThat(
                    redis.getExpire(key, TimeUnit.SECONDS)
            ).isPositive();
        }
    }

    @Test
    void cooldownIsSharedBetweenServers() {
        String scope = key + ":failure";

        assertThat(serverB.isCoolingDown(scope)).isFalse();

        serverA.markFailure(scope);

        assertThat(serverB.isCoolingDown(scope)).isTrue();

        assertThat(
                redis.getExpire(scope, TimeUnit.SECONDS)
        ).isPositive();
    }

    @Test
    @Timeout(10)
    void keepsLockAliveBeyondOriginalLease() throws Exception {
        RepaymentRedisGate shortLeaseGate =
                new RepaymentRedisGate(
                        redis,
                        scheduler,
                        Duration.ofSeconds(2),
                        Duration.ofMillis(200),
                        Duration.ofSeconds(30)
                );

        CountDownLatch elapsed = new CountDownLatch(1);

        try (var lease =
                     shortLeaseGate.tryAcquire(key).orElseThrow()) {

            // 최초 TTL인 2초보다 뒤의 시점까지 기다린다.
            var observation = scheduler.schedule(
                    elapsed::countDown,
                    Instant.now().plusMillis(2500)
            );

            try {
                assertThat(
                        elapsed.await(5, TimeUnit.SECONDS)
                ).isTrue();

                assertThat(lease.lost()).isFalse();
                assertThat(redis.hasKey(lease.lockKey())).isTrue();

                // 갱신이 없다면 최초 TTL 만료 후 이 서버가 획득할 수 있다.
                assertThat(serverB.tryAcquire(key)).isEmpty();
            } finally {
                if (observation != null) {
                    observation.cancel(false);
                }
            }
        }

        // 종료 후에는 다른 서버가 락을 얻을 수 있다.
        try (var next = serverB.tryAcquire(key).orElseThrow()) {
            assertThat(next.token()).isNotBlank();
        }
    }

    private RepaymentRedisGate gate() {
        return new RepaymentRedisGate(
                redis,
                scheduler,
                Duration.ofMinutes(2),
                Duration.ofSeconds(20),
                Duration.ofSeconds(30)
        );
    }

    private CachedRepaymentAnalysis entry() {
        return new CachedRepaymentAnalysis(
                new RepaymentAgentDraft(
                        "상환 안내",
                        List.of(
                                new RepaymentAgentDraft.ActionExplanation(
                                        101L,
                                        "납기일을 확인하세요."
                                )
                        ),
                        "일정에 맞춰 준비하세요."
                ),
                Instant.parse("2026-10-05T00:00:00Z")
        );
    }
}