package org.teamsai.saibackend.domain.contract.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.teamsai.saibackend.domain.contract.dto.response.RepaymentAgentDraft;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Component
public class RepaymentAnalysisCache {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public RepaymentAnalysisCache(
            StringRedisTemplate redisTemplate,
            @Value("${sai.repayment.analysis-cache-ttl:PT6H}") Duration ttl
    ) {
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException(
                    "Cache TTL must be positive");
        }

        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    public Optional<RepaymentAgentDraft> get(String key) {
        String json;

        try {
            json = redisTemplate.opsForValue().get(key);
        } catch (RuntimeException exception) {
            log.warn(
                    "상환 분석 캐시 조회 실패: {}",
                    exception.getClass().getSimpleName()
            );
            return Optional.empty();
        }

        if (json == null) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(
                    mapper.readValue(json, RepaymentAgentDraft.class)
            );
        } catch (RuntimeException exception) {
            log.warn("상환 분석 캐시 JSON 변환 실패");
            evict(key);
            return Optional.empty();
        }
    }

    public void put(String key, RepaymentAgentDraft draft) {
        try {
            String json = mapper.writeValueAsString(draft);

            redisTemplate.opsForValue().set(key, json, ttl);
        } catch (RuntimeException exception) {
            log.warn(
                    "상환 분석 캐시 저장 실패: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }

    public void evict(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            log.warn(
                    "상환 분석 캐시 삭제 실패: {}",
                    exception.getClass().getSimpleName()
            );
        }
    }
}