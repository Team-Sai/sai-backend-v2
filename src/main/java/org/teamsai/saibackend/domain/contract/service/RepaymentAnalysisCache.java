package org.teamsai.saibackend.domain.contract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.contract.dto.response.CachedRepaymentAnalysis;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class RepaymentAnalysisCache {

    private static final DefaultRedisScript<Long> SAVE_IF_OWNED =
            new DefaultRedisScript<>("""
                    if redis.call('get', KEYS[1]) == ARGV[1] then
                        redis.call(
                            'set', KEYS[2], ARGV[2], 'PX', ARGV[3]
                        )
                        return 1
                    end
                    return 0
                    """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public RepaymentAnalysisCache(
            StringRedisTemplate redisTemplate,
            @Value("${sai.repayment.analysis-cache-ttl:PT6H}")
            Duration ttl
    ) {
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException(
                    "Cache TTL must be positive");
        }

        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    public Optional<CachedRepaymentAnalysis> get(String key) {
        String json;

        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_CACHE_FAILURE operation=read exceptionType={}",
                    exception.getClass().getSimpleName()
            );

            return Optional.empty();
        }

        if (json == null) {
            return Optional.empty();
        }

        try {
            CachedRepaymentAnalysis cached =
                    mapper.readValue(
                            json,
                            CachedRepaymentAnalysis.class
                    );

            if (cached == null
                    || cached.draft() == null
                    || cached.analyzedAt() == null) {
                throw new IllegalArgumentException(
                        "Invalid cached analysis");
            }

            return Optional.of(cached);
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_CACHE_INVALID key={}",
                    key
            );

            evict(key);
            return Optional.empty();
        }
    }

    public boolean putIfOwned(
            String key,
            CachedRepaymentAnalysis cached,
            RepaymentRedisGate.Lease lease
    ) {
        if (lease.lost()) {
            return false;
        }

        try {
            String json = mapper.writeValueAsString(cached);

            Long result = redisTemplate.execute(
                    SAVE_IF_OWNED,
                    List.of(lease.lockKey(), key),
                    lease.token(),
                    json,
                    Long.toString(ttl.toMillis())
            );

            return Long.valueOf(1).equals(result);
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_CACHE_FAILURE operation=write exceptionType={}",
                    exception.getClass().getSimpleName()
            );

            return false;
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn(
                    "event=REPAYMENT_CACHE_FAILURE operation=delete exceptionType={}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}