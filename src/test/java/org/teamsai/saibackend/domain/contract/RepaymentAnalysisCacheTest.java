package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisCache;
import org.teamsai.saibackend.domain.contract.service.RepaymentRedisGate;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RepaymentAnalysisCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RepaymentRedisGate.Lease lease;
    private RepaymentAnalysisCache cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        lease = mock(RepaymentRedisGate.Lease.class);

        when(redis.opsForValue()).thenReturn(values);

        lenient().when(lease.lockKey())
                .thenReturn("analysis:lock");
        lenient().when(lease.token())
                .thenReturn("owner-token");

        cache = new RepaymentAnalysisCache(
                redis,
                Duration.ofHours(6)
        );
    }

    @Test
    void readsDraftAndOriginalTimestamp() {
        CachedRepaymentAnalysis entry = entry();

        String json = JsonMapper.builder().build()
                .writeValueAsString(entry);

        when(values.get("analysis")).thenReturn(json);

        assertThat(cache.get("analysis")).contains(entry);
    }

    @Test
    void rejectsInvalidJson() {
        when(values.get("analysis"))
                .thenReturn("{broken");

        assertThat(cache.get("analysis")).isEmpty();

        verify(redis).delete("analysis");
    }

    @Test
    void readFailureReturnsCacheMiss() {
        when(values.get("analysis"))
                .thenThrow(new IllegalStateException("Redis down"));

        assertThat(cache.get("analysis")).isEmpty();
    }

    @Test
    void savesWithOwnershipCheckedScript() {
        when(redis.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of("analysis:lock", "analysis")),
                eq("owner-token"),
                anyString(),
                eq("21600000")
        )).thenReturn(1L);

        assertThat(
                cache.putIfOwned("analysis", entry(), lease)
        ).isTrue();

        verify(redis).execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                eq(List.of("analysis:lock", "analysis")),
                eq("owner-token"),
                anyString(),
                eq("21600000")
        );
    }

    @Test
    void doesNotSaveAfterLeaseWasLost() {
        when(lease.lost()).thenReturn(true);

        assertThat(
                cache.putIfOwned("analysis", entry(), lease)
        ).isFalse();

        verifyNoInteractions(redis);
    }

    @Test
    void rejectsSaveWhenRedisReportsAnotherOwner() {
        when(redis.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                anyList(),
                anyString(),
                anyString(),
                anyString()
        )).thenReturn(0L);

        assertThat(
                cache.putIfOwned("analysis", entry(), lease)
        ).isFalse();
    }

    @Test
    void writeFailureReturnsFalse() {
        when(redis.execute(
                org.mockito.ArgumentMatchers
                        .<RedisScript<Long>>any(),
                anyList(),
                anyString(),
                anyString(),
                anyString()
        )).thenThrow(new IllegalStateException("Redis down"));

        assertThat(
                cache.putIfOwned("analysis", entry(), lease)
        ).isFalse();
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