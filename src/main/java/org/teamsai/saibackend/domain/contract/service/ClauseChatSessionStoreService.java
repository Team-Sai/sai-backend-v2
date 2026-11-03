package org.teamsai.saibackend.domain.contract.service;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.teamsai.saibackend.domain.contract.dto.ClauseChatSessionDTO;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 대화형 AI 특약의 대화 상태를 Redis에 보관한다.
 *
 * 키: clause-chat:{sessionId}, 값: JSON, 30분 뒤 자동 삭제 (저장할 때마다 다시 30분)
 * 백엔드가 여러 대여도 같은 대화를 이어갈 수 있도록 서버 메모리가 아닌 Redis에 둔다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClauseChatSessionStoreService {

    private static final String KEY_PREFIX = "clause-chat:";

    static final Duration TTL = Duration.ofMinutes(30);

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final StringRedisTemplate redisTemplate;

    /** 새 대화 번호를 만들어 저장하고 번호를 돌려준다. */
    public String create(ClauseChatSessionDTO session) {
        String sessionId = UUID.randomUUID().toString();
        save(sessionId, session);
        return sessionId;
    }

    public void save(String sessionId, ClauseChatSessionDTO session) {
        redisTemplate.opsForValue().set(
                createKey(sessionId),
                MAPPER.writeValueAsString(session),
                TTL
        );
    }

    /**
     * 대화가 없거나, 30분이 지났거나, 다른 사용자의 대화면 빈 값.
     * (다른 사용자의 대화가 "있다"는 사실도 알리지 않도록 셋을 구분하지 않는다)
     */
    public Optional<ClauseChatSessionDTO> find(String sessionId, Long userId) {
        String json = redisTemplate.opsForValue().get(createKey(sessionId));

        if (json == null) {
            return Optional.empty();
        }

        ClauseChatSessionDTO session;

        try {
            session = MAPPER.readValue(json, ClauseChatSessionDTO.class);

        } catch (JacksonException e) {
            // 저장된 값이 깨졌으면 이어갈 수 없으니 새 대화를 시작하게 한다.
            log.warn("대화형 특약 상태 읽기 실패 - exceptionType={}", e.getClass().getName());
            return Optional.empty();
        }

        if (!Objects.equals(session.getOwnerUserId(), userId)) {
            return Optional.empty();
        }

        return Optional.of(session);
    }

    private String createKey(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}