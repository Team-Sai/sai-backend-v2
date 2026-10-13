package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisCache;
import org.teamsai.saibackend.domain.contract.service.RepaymentRedisGate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepaymentRedisGateTest {

    private static final String KEY = "analysis:{test}";
    private static final Duration LEASE = Duration.ofMinutes(2);
    private static final Duration RENEW = Duration.ofSeconds(20);

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> scheduled;

    private AtomicReference<Runnable> renewalTask;
    private RepaymentRedisGate gate;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        scheduler = mock(ThreadPoolTaskScheduler.class);
        scheduled = mock(ScheduledFuture.class);

        renewalTask = new AtomicReference<>();

        when(redis.opsForValue()).thenReturn(values);

        when(values.setIfAbsent(
                eq(KEY + ":lock"),
                anyString(),
                eq(LEASE)
        )).thenReturn(true);

        when(scheduler.scheduleAtFixedRate(
                any(Runnable.class),
                eq(RENEW)
        )).thenAnswer(invocation -> {
            renewalTask.set(
                    invocation.getArgument(0, Runnable.class)
            );

            return scheduled;
        });

        gate = new RepaymentRedisGate(
                redis,
                scheduler,
                LEASE,
                RENEW,
                Duration.ofSeconds(30)
        );
    }

    @Test
    void renewsOnlyWithItsOwnTokenAndConfiguredTtl() {
        var lease = gate.tryAcquire(KEY).orElseThrow();

        try {
            when(redis.execute(
                    org.mockito.ArgumentMatchers
                            .<RedisScript<Long>>any(),
                    eq(List.of(lease.lockKey())),
                    eq(lease.token()),
                    eq("120000")
            )).thenReturn(1L);

            renewalTask.get().run();

            assertThat(lease.lost()).isFalse();

            verify(redis).execute(
                    org.mockito.ArgumentMatchers
                            .<RedisScript<Long>>any(),
                    eq(List.of(lease.lockKey())),
                    eq(lease.token()),
                    eq("120000")
            );
        } finally {
            lease.close();
        }
    }

    @Test
    void marksLeaseLostWhenRedisReportsAnotherOwner() {
        var lease = gate.tryAcquire(KEY).orElseThrow();

        try {
            when(redis.execute(
                    org.mockito.ArgumentMatchers
                            .<RedisScript<Long>>any(),
                    anyList(),
                    anyString(),
                    anyString()
            )).thenReturn(0L);

            renewalTask.get().run();

            assertThat(lease.lost()).isTrue();

            // 소유권 상실 후에는 다시 갱신하지 않는다.
            renewalTask.get().run();

            verify(redis, times(1)).execute(
                    org.mockito.ArgumentMatchers
                            .<RedisScript<Long>>any(),
                    anyList(),
                    anyString(),
                    anyString()
            );
        } finally {
            lease.close();
        }
    }

    @Test
    void marksLeaseLostAndBlocksSaveAfterRenewalFailure() {
        var lease = gate.tryAcquire(KEY).orElseThrow();

        try {
            when(redis.execute(
                    org.mockito.ArgumentMatchers
                            .<RedisScript<Long>>any(),
                    anyList(),
                    anyString(),
                    anyString()
            )).thenThrow(
                    new IllegalStateException("Redis unavailable")
            );

            renewalTask.get().run();

            assertThat(lease.lost()).isTrue();

            // 저장용 Redis를 분리해 저장 시도 자체가 없는지 확인한다.
            StringRedisTemplate cacheRedis =
                    mock(StringRedisTemplate.class);

            RepaymentAnalysisCache cache =
                    new RepaymentAnalysisCache(
                            cacheRedis,
                            Duration.ofHours(6)
                    );

            assertThat(
                    cache.putIfOwned(KEY, entry(), lease)
            ).isFalse();

            verifyNoInteractions(cacheRedis);
        } finally {
            lease.close();
        }
    }

    @Test
    void closeCancelsRenewalAndReleasesOnlyOnce() {
        var lease = gate.tryAcquire(KEY).orElseThrow();

        when(redis.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of(lease.lockKey())),
                eq(lease.token())
        )).thenReturn(1L);

        lease.close();
        lease.close();

        verify(scheduled, times(1)).cancel(false);

        verify(redis, times(1)).execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of(lease.lockKey())),
                eq(lease.token())
        );

        // 이미 취소된 콜백이 호출되어도 Redis 갱신은 없어야 한다.
        clearInvocations(redis);

        renewalTask.get().run();

        verifyNoInteractions(redis);
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