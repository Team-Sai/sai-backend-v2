package org.teamsai.saibackend.domain.contract;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import org.teamsai.saibackend.domain.contract.service.RepaymentAnalysisCache;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

class RepaymentAnalysisCacheTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private RepaymentAnalysisCache cache;

    private final Duration ttl = Duration.ofHours(6);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);

        lenient().when(redis.opsForValue()).thenReturn(values);

        cache = new RepaymentAnalysisCache(redis, ttl);
    }

    @Test
    void storesJsonWithTtlAndReadsItBack() {
        RepaymentAgentDraft draft = draft();
        AtomicReference<String> storedJson = new AtomicReference<>();

        doAnswer(invocation -> {
            storedJson.set(invocation.getArgument(1, String.class));
            return null;
        }).when(values).set(
                eq("test-key"), anyString(), eq(ttl));

        when(values.get("test-key"))
                .thenAnswer(invocation -> storedJson.get());

        cache.put("test-key", draft);

        assertThat(cache.get("test-key")).contains(draft);

        verify(values).set(
                eq("test-key"), anyString(), eq(Duration.ofHours(6)));
    }

    @Test
    void returnsMissWhenRedisReadFails() {
        when(values.get("test-key"))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        assertThat(cache.get("test-key")).isEmpty();
    }

    @Test
    void doesNotThrowWhenRedisWriteFails() {
        doThrow(new IllegalStateException("Redis unavailable"))
                .when(values)
                .set(eq("test-key"), anyString(), eq(ttl));

        assertThatCode(() -> cache.put("test-key", draft()))
                .doesNotThrowAnyException();
    }

    @Test
    void deletesMalformedJsonAndReturnsMiss() {
        when(values.get("test-key")).thenReturn("not-json");

        assertThat(cache.get("test-key")).isEmpty();
        verify(redis).delete("test-key");
    }

    @Test
    void doesNotThrowWhenRedisDeleteFails() {
        when(redis.delete("test-key"))
                .thenThrow(new IllegalStateException("Redis unavailable"));

        assertThatCode(() -> cache.evict("test-key"))
                .doesNotThrowAnyException();
    }

    private RepaymentAgentDraft draft() {
        return new RepaymentAgentDraft(
                "상환 안내",
                List.of(
                        new RepaymentAgentDraft.ActionExplanation(
                                101L, "납기일을 확인하세요.")
                ),
                "납기일에 맞춰 준비하세요."
        );
    }
}